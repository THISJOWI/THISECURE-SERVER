package repository

import (
	"context"
	"errors"
	"fmt"
	"strings"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/thisuite/thisecure/note/internal/model"
)

var (
	ErrNotFound = errors.New("not found")
	ErrConflict = errors.New("version conflict")
)

type NoteRepo struct {
	pool *pgxpool.Pool
}

func NewNoteRepo(pool *pgxpool.Pool) *NoteRepo {
	return &NoteRepo{pool: pool}
}

func (r *NoteRepo) FindByUserID(ctx context.Context, userID string) ([]model.Note, error) {
	rows, err := r.pool.Query(ctx, `SELECT id, content, title, created_at, user_id, version FROM notes WHERE user_id = $1 ORDER BY created_at DESC`, userID)
	if err != nil {
		return nil, fmt.Errorf("query: %w", err)
	}
	defer rows.Close()
	return pgx.CollectRows(rows, pgx.RowToStructByName[model.Note])
}

func (r *NoteRepo) FindByID(ctx context.Context, id int64) (*model.Note, error) {
	rows, err := r.pool.Query(ctx, `SELECT id, content, title, created_at, user_id, version FROM notes WHERE id = $1`, id)
	if err != nil {
		return nil, fmt.Errorf("query: %w", err)
	}
	defer rows.Close()
	note, err := pgx.CollectOneRow(rows, pgx.RowToStructByName[model.Note])
	if err != nil {
		if err == pgx.ErrNoRows {
			return nil, nil
		}
		return nil, fmt.Errorf("collect: %w", err)
	}
	return &note, nil
}

func (r *NoteRepo) FindByTitleAndUser(ctx context.Context, title, userID string) (*model.Note, error) {
	rows, err := r.pool.Query(ctx, `SELECT id, content, title, created_at, user_id, version FROM notes WHERE title = $1 AND user_id = $2`, title, userID)
	if err != nil {
		return nil, fmt.Errorf("query: %w", err)
	}
	defer rows.Close()
	note, err := pgx.CollectOneRow(rows, pgx.RowToStructByName[model.Note])
	if err != nil {
		if err == pgx.ErrNoRows {
			return nil, nil
		}
		return nil, fmt.Errorf("collect: %w", err)
	}
	return &note, nil
}

func escapeLike(s string) string {
	s = strings.ReplaceAll(s, `\`, `\\`)
	s = strings.ReplaceAll(s, `%`, `\%`)
	s = strings.ReplaceAll(s, `_`, `\_`)
	return s
}

func (r *NoteRepo) SearchByTitle(ctx context.Context, title, userID string) ([]model.Note, error) {
	title = escapeLike(title)
	rows, err := r.pool.Query(ctx, `SELECT id, content, title, created_at, user_id, version FROM notes WHERE title ILIKE '%' || $1 || '%' AND user_id = $2 ORDER BY created_at DESC`, title, userID)
	if err != nil {
		return nil, fmt.Errorf("query: %w", err)
	}
	defer rows.Close()
	return pgx.CollectRows(rows, pgx.RowToStructByName[model.Note])
}

func (r *NoteRepo) Upsert(ctx context.Context, note *model.Note) error {
	err := r.pool.QueryRow(ctx,
		`INSERT INTO notes (content, title, created_at, user_id)
		 VALUES ($1, $2, $3, $4)
		 ON CONFLICT (title, user_id) DO UPDATE
		 SET content = EXCLUDED.content, version = notes.version + 1
		 RETURNING id, version`,
		note.Content, note.Title, note.CreatedAt, note.UserID,
	).Scan(&note.ID, &note.Version)
	if err != nil {
		return fmt.Errorf("upsert: %w", err)
	}
	return nil
}

func (r *NoteRepo) Update(ctx context.Context, note *model.Note) error {
	err := r.pool.QueryRow(ctx,
		`UPDATE notes SET content = $1, title = $2, version = version + 1 WHERE id = $3 AND user_id = $4 AND version = $5
		 RETURNING version`,
		note.Content, note.Title, note.ID, note.UserID, note.Version,
	).Scan(&note.Version)
	if err != nil {
		if err == pgx.ErrNoRows {
			return ErrConflict
		}
		return fmt.Errorf("update: %w", err)
	}
	return nil
}

func (r *NoteRepo) Delete(ctx context.Context, id int64, userID string) error {
	tag, err := r.pool.Exec(ctx, `DELETE FROM notes WHERE id = $1 AND user_id = $2`, id, userID)
	if err != nil {
		return fmt.Errorf("delete: %w", err)
	}
	if tag.RowsAffected() == 0 {
		return ErrNotFound
	}
	return nil
}

func (r *NoteRepo) Insert(ctx context.Context, note *model.Note) error {
	err := r.pool.QueryRow(ctx,
		`INSERT INTO notes (content, title, created_at, user_id, version) VALUES ($1, $2, $3, $4, $5) RETURNING id`,
		note.Content, note.Title, note.CreatedAt, note.UserID, note.Version,
	).Scan(&note.ID)
	if err != nil {
		return fmt.Errorf("insert: %w", err)
	}
	return nil
}
