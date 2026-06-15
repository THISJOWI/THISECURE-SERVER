package service

import (
	"context"
	"crypto/subtle"
	"fmt"
	"log"
	"strconv"
	"strings"
	"time"

	"github.com/google/uuid"
	"github.com/thisuite/thisecure/otp/internal/model"
	"github.com/thisuite/thisecure/otp/internal/repository"
	"github.com/thisuite/thisecure/pkg/crypto"
	"github.com/thisuite/thisecure/pkg/kafka"
	"github.com/thisuite/thisecure/pkg/models"
)

type OtpService struct {
	repo       *repository.OtpRepo
	encKey     []byte
	eventProd  *kafka.Producer
	syncProd   *kafka.Producer
}

func NewOtpService(repo *repository.OtpRepo, encKey []byte, eventProd, syncProd *kafka.Producer) *OtpService {
	return &OtpService{repo: repo, encKey: encKey, eventProd: eventProd, syncProd: syncProd}
}

func (s *OtpService) GetAll(ctx context.Context, userID string) ([]model.Otp, error) {
	otps, err := s.repo.FindByUserID(ctx, userID)
	if err != nil {
		return nil, err
	}
	for i := range otps {
		s.decryptSecret(&otps[i])
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
	s.decryptSecret(o)
	return o, nil
}

func (s *OtpService) Create(ctx context.Context, req model.CreateOtpRequest, userID string) (*model.Otp, error) {
	expiresAt := strconv.FormatInt(time.Now().UnixMilli()+int64(req.Period)*1000, 10)
	secret := req.Secret
	if secret == "" {
		secret = generateRandomSecret()
	}

	o := &model.Otp{
		UserID:    userID,
		Email:     "",
		Secret:    secret,
		ExpiresAt: expiresAt,
		Type:      req.Type,
		Issuer:    strPtr(req.Issuer),
		Digits:    intPtr(req.Digits),
		Period:    intPtr(req.Period),
		Algorithm: strPtr(req.Algorithm),
		Valid:     "true",
	}

	s.encryptSecret(o)
	if err := s.repo.Insert(ctx, o); err != nil {
		return nil, err
	}
	s.decryptSecret(o)
	s.publishEvents(o, "created")
	return o, nil
}

func (s *OtpService) Update(ctx context.Context, id int64, req model.CreateOtpRequest, userID string) (*model.Otp, error) {
	existing, err := s.repo.FindByID(ctx, id)
	if err != nil {
		return nil, err
	}
	if existing == nil || existing.UserID != userID {
		return nil, fmt.Errorf("otp not found or not owned")
	}

	existing.Email = ""
	existing.Secret = req.Secret
	existing.Type = req.Type
	existing.Issuer = strPtr(req.Issuer)
	existing.Digits = intPtr(req.Digits)
	existing.Period = intPtr(req.Period)
	existing.Algorithm = strPtr(req.Algorithm)
	existing.ExpiresAt = strconv.FormatInt(time.Now().UnixMilli()+int64(req.Period)*1000, 10)

	s.encryptSecret(existing)
	if err := s.repo.Update(ctx, existing); err != nil {
		return nil, err
	}
	s.decryptSecret(existing)
	s.publishEvents(existing, "updated")
	return existing, nil
}

func (s *OtpService) Delete(ctx context.Context, id int64, userID string) error {
	existing, err := s.repo.FindByID(ctx, id)
	if err != nil {
		return err
	}
	if existing == nil || existing.UserID != userID {
		return fmt.Errorf("otp not found or not owned")
	}
	if err := s.repo.Remove(ctx, id, userID); err != nil {
		return err
	}
	s.publishEvents(existing, "deleted")
	return nil
}

func (s *OtpService) Validate(ctx context.Context, id int64, code string) (bool, error) {
	o, err := s.repo.FindByID(ctx, id)
	if err != nil {
		return false, err
	}
	if o == nil {
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

	s.decryptSecret(o)
	if subtle.ConstantTimeCompare([]byte(code), []byte(o.Secret)) == 0 {
		return false, nil
	}
	return true, nil
}

func (s *OtpService) encryptSecret(o *model.Otp) {
	if s.encKey == nil || o.Secret == "" {
		return
	}
	enc, err := crypto.Encrypt([]byte(o.Secret), s.encKey)
	if err == nil {
		o.Secret = enc
	}
}

func (s *OtpService) decryptSecret(o *model.Otp) {
	if s.encKey == nil || o.Secret == "" {
		return
	}
	dec, err := crypto.Decrypt(o.Secret, s.encKey)
	if err == nil {
		o.Secret = string(dec)
	}
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
	return strings.ReplaceAll(uuid.New().String(), "-", "")[:20]
}

func strPtr(s string) *string {
	if s == "" {
		return nil
	}
	return &s
}

func intPtr(i int) *int {
	return &i
}
