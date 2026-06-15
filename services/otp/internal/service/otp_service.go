package service

import (
	"context"
	"crypto/rand"
	"crypto/subtle"
	"encoding/base32"
	"errors"
	"fmt"
	"log"
	"strconv"
	"sync"
	"time"

	"github.com/google/uuid"
	"github.com/thisuite/thisecure/otp/internal/model"
	"github.com/thisuite/thisecure/otp/internal/repository"
	"github.com/thisuite/thisecure/pkg/crypto"
	"github.com/thisuite/thisecure/pkg/kafka"
	"github.com/thisuite/thisecure/pkg/models"
)

var ErrNotFound = errors.New("not found")

type failedEntry struct {
	count     int
	firstFail time.Time
}

type OtpService struct {
	repo       *repository.OtpRepo
	encKey     []byte
	eventProd  *kafka.Producer
	syncProd   *kafka.Producer
	failedMu   sync.Mutex
	failed     map[string]*failedEntry
}

func NewOtpService(repo *repository.OtpRepo, encKey []byte, eventProd, syncProd *kafka.Producer) *OtpService {
	return &OtpService{
		repo:      repo,
		encKey:    encKey,
		eventProd: eventProd,
		syncProd:  syncProd,
		failed:    make(map[string]*failedEntry),
	}
}

func (s *OtpService) GetAll(ctx context.Context, userID string) ([]model.Otp, error) {
	otps, err := s.repo.FindByUserID(ctx, userID)
	if err != nil {
		return nil, err
	}
	for i := range otps {
		if err := s.decryptSecret(&otps[i]); err != nil {
			log.Printf("ERROR: %v (skipping entry %d)", err, otps[i].ID)
		}
	}
	return otps, nil
}

func (s *OtpService) GetByID(ctx context.Context, id int64, userID string) (*model.Otp, error) {
	o, err := s.repo.FindByID(ctx, id)
	if err != nil {
		return nil, err
	}
	if o == nil || o.UserID != userID {
		return nil, nil
	}
	if err := s.decryptSecret(o); err != nil {
		return nil, err
	}
	return o, nil
}

func (s *OtpService) Create(ctx context.Context, req model.CreateOtpRequest, userID string) (*model.Otp, error) {
	expiresAt := strconv.FormatInt(time.Now().UnixMilli()+int64(req.Period)*1000, 10)
	secret := req.Secret
	if secret == "" {
		secret = generateRandomSecret()
	}

	existing, err := s.repo.FindByUserIDAndEmail(ctx, userID, req.Name)
	if err != nil {
		return nil, err
	}
	if existing != nil {
		return nil, fmt.Errorf("otp with this name already exists")
	}

	o := &model.Otp{
		UserID:    userID,
		Email:     req.Name,
		Secret:    secret,
		ExpiresAt: expiresAt,
		Type:      req.Type,
		Issuer:    strPtr(req.Issuer),
		Digits:    req.Digits,
		Period:    req.Period,
		Algorithm: strPtr(req.Algorithm),
		Valid:     "true",
	}

	if err := s.encryptSecret(o); err != nil {
		return nil, err
	}
	if err := s.repo.Insert(ctx, o); err != nil {
		return nil, err
	}
	if err := s.decryptSecret(o); err != nil {
		log.Printf("ERROR: decrypt after create: %v", err)
	}
	s.publishEvents(o, "created")
	return o, nil
}

func (s *OtpService) Update(ctx context.Context, id int64, req model.CreateOtpRequest, userID string) (*model.Otp, error) {
	existing, err := s.repo.FindByID(ctx, id)
	if err != nil {
		return nil, err
	}
	if existing == nil || existing.UserID != userID {
		return nil, ErrNotFound
	}

	existing.Email = req.Name
	existing.Secret = req.Secret
	existing.Type = req.Type
	existing.Issuer = strPtr(req.Issuer)
	existing.Digits = req.Digits
	existing.Period = req.Period
	existing.Algorithm = strPtr(req.Algorithm)
	existing.ExpiresAt = strconv.FormatInt(time.Now().UnixMilli()+int64(req.Period)*1000, 10)

	if err := s.encryptSecret(existing); err != nil {
		return nil, err
	}
	if err := s.repo.Update(ctx, existing); err != nil {
		return nil, err
	}
	if err := s.decryptSecret(existing); err != nil {
		log.Printf("ERROR: decrypt after update: %v", err)
	}
	s.publishEvents(existing, "updated")
	return existing, nil
}

func (s *OtpService) Delete(ctx context.Context, id int64, userID string) error {
	existing, err := s.repo.FindByID(ctx, id)
	if err != nil {
		return err
	}
	if existing == nil || existing.UserID != userID {
		return ErrNotFound
	}
	if err := s.repo.Remove(ctx, id, userID); err != nil {
		return err
	}
	s.publishEvents(existing, "deleted")
	return nil
}

func (s *OtpService) Validate(ctx context.Context, id int64, userID string, code string) (bool, error) {
	failKey := fmt.Sprintf("%s:%d", userID, id)
	s.failedMu.Lock()
	entry, exists := s.failed[failKey]
	if exists && time.Since(entry.firstFail) > 5*time.Minute {
		delete(s.failed, failKey)
		entry = nil
		exists = false
	}
	if exists && entry.count >= 5 {
		s.failedMu.Unlock()
		return false, fmt.Errorf("too many failed attempts")
	}
	s.failedMu.Unlock()

	o, err := s.repo.FindByID(ctx, id)
	if err != nil {
		return false, err
	}
	if o == nil || o.UserID != userID {
		return false, fmt.Errorf("otp not found")
	}
	if o.Valid != "true" {
		return false, fmt.Errorf("otp is not valid")
	}

	expiresAt, err := strconv.ParseInt(o.ExpiresAt, 10, 64)
	if err != nil {
		return false, fmt.Errorf("invalid expires_at")
	}
	if time.Now().UnixMilli() > expiresAt {
		return false, fmt.Errorf("otp expired")
	}

	if err := s.decryptSecret(o); err != nil {
		return false, fmt.Errorf("invalid secret")
	}
	if subtle.ConstantTimeCompare([]byte(code), []byte(o.Secret)) == 0 {
		s.failedMu.Lock()
		e, ok := s.failed[failKey]
		if !ok {
			e = &failedEntry{firstFail: time.Now()}
			s.failed[failKey] = e
		}
		e.count++
		s.failedMu.Unlock()
		return false, nil
	}

	s.failedMu.Lock()
	delete(s.failed, failKey)
	s.failedMu.Unlock()
	return true, nil
}

func (s *OtpService) encryptSecret(o *model.Otp) error {
	if len(s.encKey) == 0 || o.Secret == "" {
		return nil
	}
	enc, err := crypto.Encrypt([]byte(o.Secret), s.encKey)
	if err != nil {
		return fmt.Errorf("encrypt OTP secret: %w", err)
	}
	o.Secret = enc
	return nil
}

func (s *OtpService) decryptSecret(o *model.Otp) error {
	if len(s.encKey) == 0 || o.Secret == "" {
		return nil
	}
	dec, err := crypto.Decrypt(o.Secret, s.encKey)
	if err != nil {
		o.Secret = ""
		return fmt.Errorf("decrypt OTP secret: %w", err)
	}
	o.Secret = string(dec)
	return nil
}

func (s *OtpService) publishEvents(o *model.Otp, action string) {
	if s.syncProd != nil {
		event := models.SyncEvent{
			EventID:     uuid.New().String(),
			UserID:      o.UserID,
			ServiceName: "otp",
			Action:      action,
			Payload: map[string]interface{}{
				"id":     o.ID,
				"issuer": o.Issuer,
				"label":  o.Email,
			},
			Timestamp: time.Now().UnixMilli(),
		}
		if err := s.syncProd.Publish(context.Background(), o.UserID, event); err != nil {
			log.Printf("WARN: failed to publish sync event: %v", err)
		}
	}
	if s.eventProd != nil && action == "created" {
		evt := models.OtpCreatedEvent{
			OtpID:     o.ID,
			UserID:    o.UserID,
			Email:     o.Email,
			Type:      o.Type,
			EventType: "OTP_CREATED",
			Timestamp: time.Now().UnixMilli(),
		}
		if expiresAt, err := strconv.ParseInt(o.ExpiresAt, 10, 64); err == nil {
			evt.ExpiresAt = expiresAt
		}
		if err := s.eventProd.Publish(context.Background(), o.UserID, evt); err != nil {
			log.Printf("WARN: failed to publish otp-created event: %v", err)
		}
	}
}

func generateRandomSecret() string {
	b := make([]byte, 20)
	rand.Read(b)
	return base32.StdEncoding.WithPadding(base32.NoPadding).EncodeToString(b)
}

func strPtr(s string) *string {
	if s == "" {
		return nil
	}
	return &s
}
