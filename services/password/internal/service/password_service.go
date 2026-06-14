package service

import (
	"context"
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
		s.decrypt(&pws[i])
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
	s.decrypt(pw)
	return pw, nil
}

func (s *PasswordService) Create(ctx context.Context, req model.PasswordRequest, userID string) (*model.Password, error) {
	existing, err := s.repo.FindByUserIDAndNameAndWebsite(ctx, userID, req.Name, req.Website)
	if err != nil {
		return nil, err
	}

	pw := &model.Password{
		Password: req.Password,
		Name:     req.Name,
		Website:  req.Website,
		Username: req.Username,
		UserID:   userID,
	}
	s.encrypt(pw)

	if existing != nil {
		pw.ID = existing.ID
		if err := s.repo.Update(ctx, pw); err != nil {
			return nil, err
		}
	} else {
		if err := s.repo.Insert(ctx, pw); err != nil {
			return nil, err
		}
	}
	s.decrypt(pw)
	s.publishEvent(pw, "created")
	return pw, nil
}

func (s *PasswordService) Update(ctx context.Context, id int64, req model.PasswordRequest, userID string) (*model.Password, error) {
	existing, err := s.repo.FindByID(ctx, id)
	if err != nil {
		return nil, err
	}
	if existing == nil || existing.UserID != userID {
		return nil, fmt.Errorf("password not found or not owned")
	}
	existing.Password = req.Password
	existing.Name = req.Name
	existing.Website = req.Website
	existing.Username = req.Username
	s.encrypt(existing)
	if err := s.repo.Update(ctx, existing); err != nil {
		return nil, err
	}
	s.decrypt(existing)
	s.publishEvent(existing, "updated")
	return existing, nil
}

func (s *PasswordService) Delete(ctx context.Context, id int64, userID string) error {
	existing, err := s.repo.FindByID(ctx, id)
	if err != nil {
		return err
	}
	if existing == nil || existing.UserID != userID {
		return fmt.Errorf("password not found or not owned")
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

func (s *PasswordService) encrypt(pw *model.Password) {
	if s.encKey == nil {
		return
	}
	fields := []*string{&pw.Password, &pw.Name, &pw.Website, &pw.Username}
	for _, f := range fields {
		if *f != "" {
			enc, err := crypto.Encrypt([]byte(*f), s.encKey)
			if err == nil {
				*f = enc
			}
		}
	}
}

func (s *PasswordService) decrypt(pw *model.Password) {
	if s.encKey == nil {
		return
	}
	fields := []*string{&pw.Password, &pw.Name, &pw.Website, &pw.Username}
	for _, f := range fields {
		if *f != "" {
			dec, err := crypto.Decrypt(*f, s.encKey)
			if err == nil {
				*f = string(dec)
			}
		}
	}
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
