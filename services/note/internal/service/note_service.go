package service

import (
	"context"
	"fmt"
	"log"
	"strings"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgconn"
	"github.com/thisuite/thisecure/note/internal/model"
	"github.com/thisuite/thisecure/note/internal/repository"
	"github.com/thisuite/thisecure/pkg/crypto"
	"github.com/thisuite/thisecure/pkg/kafka"
	"github.com/thisuite/thisecure/pkg/models"
)

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
		s.decryptNote(&notes[i])
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
	s.decryptNote(note)
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
	s.decryptNote(note)
	return note, nil
}

func (s *NoteService) SearchByTitle(ctx context.Context, title, userID string) ([]model.Note, error) {
	notes, err := s.repo.SearchByTitle(ctx, title, userID)
	if err != nil {
		return nil, err
	}
	for i := range notes {
		s.decryptNote(&notes[i])
	}
	return notes, nil
}

func (s *NoteService) Create(ctx context.Context, req model.NoteRequest, userID string) (*model.Note, error) {
	existing, err := s.repo.FindByTitleAndUser(ctx, req.Title, userID)
	if err != nil {
		return nil, err
	}

	note := &model.Note{
		Title:     req.Title,
		Content:   req.Content,
		UserID:    userID,
		CreatedAt: time.Now(),
		Version:   0,
	}

	if existing != nil {
		note.ID = existing.ID
		note.Version = existing.Version
		s.encryptNote(note)
		if err := s.repo.Update(ctx, note); err != nil {
			return nil, err
		}
	} else {
		s.encryptNote(note)
		if err := s.repo.Insert(ctx, note); err != nil {
			if pgErr, ok := err.(*pgconn.PgError); ok && pgErr.Code == "23505" {
				existing2, _ := s.repo.FindByTitleAndUser(ctx, req.Title, userID)
				if existing2 != nil {
					note.ID = existing2.ID
					note.Version = existing2.Version
					s.repo.Update(ctx, note)
					s.publishEvent(note, "created")
					return s.GetByID(ctx, note.ID, userID)
				}
			}
			return nil, err
		}
	}

	s.decryptNote(note)
	s.publishEvent(note, "created")
	return note, nil
}

func (s *NoteService) Update(ctx context.Context, id int64, req model.NoteRequest, userID string) (*model.Note, error) {
	existing, err := s.repo.FindByID(ctx, id)
	if err != nil {
		return nil, err
	}
	if existing == nil || existing.UserID != userID {
		return nil, fmt.Errorf("note not found or not owned")
	}
	existing.Title = req.Title
	existing.Content = req.Content
	s.encryptNote(existing)
	if err := s.repo.Update(ctx, existing); err != nil {
		return nil, err
	}
	s.decryptNote(existing)
	s.publishEvent(existing, "updated")
	return existing, nil
}

func (s *NoteService) Delete(ctx context.Context, id int64, userID string) error {
	existing, err := s.repo.FindByID(ctx, id)
	if err != nil {
		return err
	}
	if existing == nil || existing.UserID != userID {
		return fmt.Errorf("note not found or not owned")
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

func (s *NoteService) encryptNote(n *model.Note) {
	if s.encKey == nil {
		return
	}
	if n.Content != "" {
		enc, err := crypto.Encrypt([]byte(n.Content), s.encKey)
		if err == nil {
			n.Content = enc
		}
	}
}

func (s *NoteService) decryptNote(n *model.Note) {
	if s.encKey == nil {
		return
	}
	if n.Content != "" {
		dec, err := crypto.Decrypt(n.Content, s.encKey)
		if err == nil {
			n.Content = string(dec)
		}
	}
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
