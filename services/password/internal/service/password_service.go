package service

import (
	"context"
	"errors"
	"fmt"
	"log"
	"strings"
	"time"

	"github.com/google/uuid"
	"github.com/thisuite/thisecure/password/internal/model"
	"github.com/thisuite/thisecure/password/internal/repository"
	"github.com/thisuite/thisecure/pkg/crypto"
	"github.com/thisuite/thisecure/pkg/kafka"
	"github.com/thisuite/thisecure/pkg/models"
)

var ErrNotFound = errors.New("not found")

type PasswordService struct {
	repo       *repository.PasswordRepo
	encKey     []byte
	syncEvents *kafka.Producer
}

func NewPasswordService(repo *repository.PasswordRepo, encKey []byte, syncEvents *kafka.Producer) *PasswordService {
	return &PasswordService{repo: repo, encKey: encKey, syncEvents: syncEvents}
}

func (s *PasswordService) GetAll(ctx context.Context, userID string) ([]model.Password, error) {
	pws, err := s.repo.FindByUserID(ctx, userID)
	if err != nil {
		return nil, err
	}
	for i := range pws {
		if err := s.decrypt(&pws[i]); err != nil {
			log.Printf("ERROR: %v (skipping entry %d)", err, pws[i].ID)
		}
	}
	return pws, nil
}

func (s *PasswordService) GetByID(ctx context.Context, id int64, userID string) (*model.Password, error) {
	pw, err := s.repo.FindByID(ctx, id)
	if err != nil {
		return nil, err
	}
	if pw == nil || pw.UserID != userID {
		return nil, nil
	}
	if err := s.decrypt(pw); err != nil {
		return nil, err
	}
	return pw, nil
}

func (s *PasswordService) Create(ctx context.Context, req model.PasswordRequest, userID string) (*model.Password, error) {
	pw := &model.Password{
		Password: req.Password,
		Name:     req.Name,
		Website:  req.Website,
		Username: req.Username,
		UserID:   userID,
	}
	if err := s.encrypt(pw); err != nil {
		return nil, err
	}
	if err := s.repo.Upsert(ctx, pw); err != nil {
		return nil, err
	}
	if err := s.decrypt(pw); err != nil {
		log.Printf("ERROR: decrypt after create: %v", err)
	}
	s.publishEvent(pw, "created")
	return pw, nil
}

func (s *PasswordService) Update(ctx context.Context, id int64, req model.PasswordRequest, userID string) (*model.Password, error) {
	existing, err := s.repo.FindByID(ctx, id)
	if err != nil {
		return nil, err
	}
	if existing == nil || existing.UserID != userID {
		return nil, ErrNotFound
	}
	existing.Password = req.Password
	existing.Name = req.Name
	existing.Website = req.Website
	existing.Username = req.Username
	if err := s.encrypt(existing); err != nil {
		return nil, err
	}
	if err := s.repo.Update(ctx, existing); err != nil {
		return nil, err
	}
	if err := s.decrypt(existing); err != nil {
		log.Printf("ERROR: decrypt after update: %v", err)
	}
	s.publishEvent(existing, "updated")
	return existing, nil
}

func (s *PasswordService) Delete(ctx context.Context, id int64, userID string) error {
	existing, err := s.repo.FindByID(ctx, id)
	if err != nil {
		return err
	}
	if existing == nil || existing.UserID != userID {
		return ErrNotFound
	}
	if err := s.repo.Delete(ctx, id, userID); err != nil {
		return err
	}
	s.publishEvent(existing, "deleted")
	return nil
}

func (s *PasswordService) Import(ctx context.Context, reqs []model.PasswordRequest, userID string) (*model.ImportResult, error) {
	result := &model.ImportResult{Total: len(reqs)}
	for _, req := range reqs {
		if _, err := s.Create(ctx, req, userID); err != nil {
			if strings.Contains(err.Error(), "unique") {
				result.Skipped++
			} else {
				result.Errors++
				log.Printf("import error: %v", err)
			}
		} else {
			result.Imported++
		}
	}
	return result, nil
}

func (s *PasswordService) encrypt(pw *model.Password) error {
	if len(s.encKey) == 0 || pw.Password == "" {
		return nil
	}
	enc, err := crypto.Encrypt([]byte(pw.Password), s.encKey)
	if err != nil {
		return fmt.Errorf("encrypt password: %w", err)
	}
	pw.Password = enc
	return nil
}

func (s *PasswordService) decrypt(pw *model.Password) error {
	if len(s.encKey) == 0 || pw.Password == "" {
		return nil
	}
	dec, err := crypto.Decrypt(pw.Password, s.encKey)
	if err != nil {
		pw.Password = ""
		return fmt.Errorf("decrypt password: %w", err)
	}
	pw.Password = string(dec)
	return nil
}

func (s *PasswordService) publishEvent(pw *model.Password, action string) {
	if s.syncEvents == nil {
		return
	}
	event := models.SyncEvent{
		EventID:     uuid.New().String(),
		UserID:      pw.UserID,
		ServiceName: "password",
		Action:      action,
		Payload: map[string]interface{}{
			"id":       pw.ID,
			"title":    pw.Name,
			"website":  pw.Website,
			"username": pw.Username,
		},
		Timestamp: time.Now().UnixMilli(),
	}
	if err := s.syncEvents.Publish(context.Background(), pw.UserID, event); err != nil {
		log.Printf("WARN: failed to publish sync event: %v", err)
	}
}
