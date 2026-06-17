package service

import (
	"context"
	"errors"
	"fmt"
	"log"
	"time"

	"github.com/google/uuid"
	"github.com/thisuite/thisecure/passkey/internal/model"
	"github.com/thisuite/thisecure/passkey/internal/repository"
	"github.com/thisuite/thisecure/pkg/crypto"
	"github.com/thisuite/thisecure/pkg/kafka"
	"github.com/thisuite/thisecure/pkg/models"
)

var ErrNotFound = errors.New("not found")

type PasskeyService struct {
	repo       *repository.PasskeyRepo
	encKey     []byte
	syncEvents *kafka.Producer
}

func NewPasskeyService(repo *repository.PasskeyRepo, encKey []byte, syncEvents *kafka.Producer) *PasskeyService {
	return &PasskeyService{repo: repo, encKey: encKey, syncEvents: syncEvents}
}

func (s *PasskeyService) GetAll(ctx context.Context, userID string) ([]model.Passkey, error) {
	pks, err := s.repo.FindByUserID(ctx, userID)
	if err != nil {
		return nil, err
	}
	for i := range pks {
		if err := s.decrypt(&pks[i]); err != nil {
			log.Printf("ERROR: %v (skipping entry %d)", err, pks[i].ID)
		}
	}
	return pks, nil
}

func (s *PasskeyService) GetByID(ctx context.Context, id int64, userID string) (*model.Passkey, error) {
	pk, err := s.repo.FindByID(ctx, id)
	if err != nil {
		return nil, err
	}
	if pk == nil || pk.UserID != userID {
		return nil, nil
	}
	if err := s.decrypt(pk); err != nil {
		return nil, err
	}
	return pk, nil
}

func (s *PasskeyService) Create(ctx context.Context, req model.PasskeyRequest, userID string) (*model.Passkey, error) {
	pk := &model.Passkey{
		CredentialID:    req.CredentialID,
		PublicKey:       req.PublicKey,
		RpID:            req.RpID,
		RpName:          req.RpName,
		UserHandle:      req.UserHandle,
		UserDisplayName: req.UserDisplayName,
		SignCount:       req.SignCount,
		Name:            req.Name,
		Transports:      req.Transports,
		CredentialType:  req.CredentialType,
		BackupEligible:  req.BackupEligible,
		BackupState:     req.BackupState,
		UserID:          userID,
	}
	if err := s.encrypt(pk); err != nil {
		return nil, err
	}
	if err := s.repo.Insert(ctx, pk); err != nil {
		return nil, err
	}
	if err := s.decrypt(pk); err != nil {
		log.Printf("ERROR: decrypt after create: %v", err)
	}
	s.publishEvent(pk, "created")
	return pk, nil
}

func (s *PasskeyService) Update(ctx context.Context, id int64, req model.PasskeyRequest, userID string) (*model.Passkey, error) {
	existing, err := s.repo.FindByID(ctx, id)
	if err != nil {
		return nil, err
	}
	if existing == nil || existing.UserID != userID {
		return nil, ErrNotFound
	}
	existing.CredentialID = req.CredentialID
	existing.PublicKey = req.PublicKey
	existing.RpID = req.RpID
	existing.RpName = req.RpName
	existing.UserHandle = req.UserHandle
	existing.UserDisplayName = req.UserDisplayName
	existing.SignCount = req.SignCount
	existing.Name = req.Name
	existing.Transports = req.Transports
	existing.CredentialType = req.CredentialType
	existing.BackupEligible = req.BackupEligible
	existing.BackupState = req.BackupState
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

func (s *PasskeyService) Delete(ctx context.Context, id int64, userID string) error {
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

func (s *PasskeyService) encrypt(pk *model.Passkey) error {
	if len(s.encKey) == 0 || pk.PublicKey == "" {
		return nil
	}
	enc, err := crypto.Encrypt([]byte(pk.PublicKey), s.encKey)
	if err != nil {
		return fmt.Errorf("encrypt public_key: %w", err)
	}
	pk.PublicKey = enc
	return nil
}

func (s *PasskeyService) decrypt(pk *model.Passkey) error {
	if len(s.encKey) == 0 || pk.PublicKey == "" {
		return nil
	}
	dec, err := crypto.Decrypt(pk.PublicKey, s.encKey)
	if err != nil {
		pk.PublicKey = ""
		return fmt.Errorf("decrypt public_key: %w", err)
	}
	pk.PublicKey = string(dec)
	return nil
}

func (s *PasskeyService) publishEvent(pk *model.Passkey, action string) {
	if s.syncEvents == nil {
		return
	}
	event := models.SyncEvent{
		EventID:     uuid.New().String(),
		UserID:      pk.UserID,
		ServiceName: "passkey",
		Action:      action,
		Payload: map[string]interface{}{
			"id":           pk.ID,
			"credentialId": pk.CredentialID,
			"rpId":         pk.RpID,
			"name":         pk.Name,
		},
		Timestamp: time.Now().UnixMilli(),
	}
	if err := s.syncEvents.Publish(context.Background(), pk.UserID, event); err != nil {
		log.Printf("WARN: failed to publish sync event: %v", err)
	}
}
