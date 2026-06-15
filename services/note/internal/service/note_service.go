package service

import (
	"context"
	"errors"
	"fmt"
	"log"
	"strings"
	"time"

	"github.com/google/uuid"
	"github.com/thisuite/thisecure/note/internal/model"
	"github.com/thisuite/thisecure/note/internal/repository"
	"github.com/thisuite/thisecure/pkg/crypto"
	"github.com/thisuite/thisecure/pkg/kafka"
	"github.com/thisuite/thisecure/pkg/models"
)

var ErrNotFound = errors.New("not found")

type NoteService struct {
	repo       *repository.NoteRepo
	encKey     []byte
	syncEvents *kafka.Producer
}

func NewNoteService(repo *repository.NoteRepo, encKey []byte, syncEvents *kafka.Producer) *NoteService {
	return &NoteService{repo: repo, encKey: encKey, syncEvents: syncEvents}
}

func (s *NoteService) GetAll(ctx context.Context, userID string) ([]model.Note, error) {
	notes, err := s.repo.FindByUserID(ctx, userID)
	if err != nil {
		return nil, err
	}
	for i := range notes {
		if err := s.decryptNote(&notes[i]); err != nil {
			log.Printf("ERROR: %v (skipping entry %d)", err, notes[i].ID)
		}
	}
	return notes, nil
}

func (s *NoteService) GetByID(ctx context.Context, id int64, userID string) (*model.Note, error) {
	note, err := s.repo.FindByID(ctx, id)
	if err != nil {
		return nil, err
	}
	if note == nil || note.UserID != userID {
		return nil, nil
	}
	if err := s.decryptNote(note); err != nil {
		return nil, err
	}
	return note, nil
}

func (s *NoteService) GetByTitle(ctx context.Context, title, userID string) (*model.Note, error) {
	note, err := s.repo.FindByTitleAndUser(ctx, title, userID)
	if err != nil {
		return nil, err
	}
	if note == nil {
		return nil, nil
	}
	if err := s.decryptNote(note); err != nil {
		return nil, err
	}
	return note, nil
}

func (s *NoteService) SearchByTitle(ctx context.Context, title, userID string) ([]model.Note, error) {
	notes, err := s.repo.SearchByTitle(ctx, title, userID)
	if err != nil {
		return nil, err
	}
	for i := range notes {
		if err := s.decryptNote(&notes[i]); err != nil {
			log.Printf("ERROR: %v (skipping entry %d)", err, notes[i].ID)
		}
	}
	return notes, nil
}

func (s *NoteService) Create(ctx context.Context, req model.NoteRequest, userID string) (*model.Note, error) {
	note := &model.Note{
		Title:     req.Title,
		Content:   req.Content,
		UserID:    userID,
		CreatedAt: time.Now(),
	}
	if err := s.encryptNote(note); err != nil {
		return nil, err
	}
	if err := s.repo.Upsert(ctx, note); err != nil {
		return nil, err
	}
	if err := s.decryptNote(note); err != nil {
		log.Printf("ERROR: decrypt after create: %v", err)
	}
	s.publishEvent(note, "created")
	return note, nil
}

func (s *NoteService) Update(ctx context.Context, id int64, req model.NoteRequest, userID string) (*model.Note, error) {
	existing, err := s.repo.FindByID(ctx, id)
	if err != nil {
		return nil, err
	}
	if existing == nil || existing.UserID != userID {
		return nil, ErrNotFound
	}
	existing.Title = req.Title
	existing.Content = req.Content
	if err := s.encryptNote(existing); err != nil {
		return nil, err
	}
	if err := s.repo.Update(ctx, existing); err != nil {
		if errors.Is(err, repository.ErrConflict) {
			return nil, fmt.Errorf("conflict: note was modified by another request")
		}
		return nil, err
	}
	if err := s.decryptNote(existing); err != nil {
		log.Printf("ERROR: decrypt after update: %v", err)
	}
	s.publishEvent(existing, "updated")
	return existing, nil
}

func (s *NoteService) Delete(ctx context.Context, id int64, userID string) error {
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

func (s *NoteService) Import(ctx context.Context, notes []model.NoteRequest, userID string) (*model.ImportResult, error) {
	result := &model.ImportResult{Total: len(notes)}
	for _, req := range notes {
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

func (s *NoteService) encryptNote(n *model.Note) error {
	if len(s.encKey) == 0 {
		return nil
	}
	if n.Content != "" {
		enc, err := crypto.Encrypt([]byte(n.Content), s.encKey)
		if err != nil {
			return fmt.Errorf("encrypt note: %w", err)
		}
		n.Content = enc
	}
	return nil
}

func (s *NoteService) decryptNote(n *model.Note) error {
	if len(s.encKey) == 0 {
		return nil
	}
	if n.Content != "" {
		dec, err := crypto.Decrypt(n.Content, s.encKey)
		if err != nil {
			n.Content = ""
			return fmt.Errorf("decrypt note: %w", err)
		}
		n.Content = string(dec)
	}
	return nil
}

func (s *NoteService) publishEvent(note *model.Note, action string) {
	if s.syncEvents == nil {
		return
	}
	event := models.SyncEvent{
		EventID:     uuid.New().String(),
		UserID:      note.UserID,
		ServiceName: "note",
		Action:      action,
		Payload: map[string]interface{}{
			"id":      note.ID,
			"title":   note.Title,
			"version": note.Version,
		},
		Timestamp: time.Now().UnixMilli(),
	}
	if err := s.syncEvents.Publish(context.Background(), note.UserID, event); err != nil {
		log.Printf("WARN: failed to publish sync event: %v", err)
	}
}
