package repository

import (
	"context"
	"errors"
	"fmt"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/thisuite/thisecure/password/internal/model"
)

var (
	ErrNotFound = errors.New("not found")
	ErrConflict = errors.New("version conflict")
)

type PasswordRepo struct {
	pool *pgxpool.Pool
}

func NewPasswordRepo(pool *pgxpool.Pool) *PasswordRepo {
	return &PasswordRepo{pool: pool}
}

func (r *PasswordRepo) FindByUserID(ctx context.Context, userID string) ([]model.Password, error) {
	rows, err := r.pool.Query(ctx, `SELECT id, password, name, website, username, user_id FROM password WHERE user_id = $1 ORDER BY id`, userID)
	if err != nil {
		return nil, fmt.Errorf("query: %w", err)
	}
	defer rows.Close()
	return pgx.CollectRows(rows, pgx.RowToStructByName[model.Password])
}

func (r *PasswordRepo) FindByID(ctx context.Context, id int64) (*model.Password, error) {
	rows, err := r.pool.Query(ctx, `SELECT id, password, name, website, username, user_id FROM password WHERE id = $1`, id)
	if err != nil {
		return nil, fmt.Errorf("query: %w", err)
	}
	defer rows.Close()
	pw, err := pgx.CollectOneRow(rows, pgx.RowToStructByName[model.Password])
	if err != nil {
		if err == pgx.ErrNoRows {
			return nil, nil
		}
		return nil, fmt.Errorf("collect: %w", err)
	}
	return &pw, nil
}

func (r *PasswordRepo) FindByUserIDAndNameAndWebsite(ctx context.Context, userID, name, website string) (*model.Password, error) {
	rows, err := r.pool.Query(ctx, `SELECT id, password, name, website, username, user_id FROM password WHERE user_id = $1 AND name = $2 AND website = $3`, userID, name, website)
	if err != nil {
		return nil, fmt.Errorf("query: %w", err)
	}
	defer rows.Close()
	pw, err := pgx.CollectOneRow(rows, pgx.RowToStructByName[model.Password])
	if err != nil {
		if err == pgx.ErrNoRows {
			return nil, nil
		}
		return nil, fmt.Errorf("collect: %w", err)
	}
	return &pw, nil
}

func (r *PasswordRepo) Insert(ctx context.Context, pw *model.Password) error {
	err := r.pool.QueryRow(ctx,
		`INSERT INTO password (password, name, website, username, user_id) VALUES ($1,$2,$3,$4,$5) RETURNING id`,
		pw.Password, pw.Name, pw.Website, pw.Username, pw.UserID,
	).Scan(&pw.ID)
	if err != nil {
		return fmt.Errorf("insert: %w", err)
	}
	return nil
}

func (r *PasswordRepo) Upsert(ctx context.Context, pw *model.Password) error {
	tx, err := r.pool.Begin(ctx)
	if err != nil {
		return fmt.Errorf("upsert begin: %w", err)
	}
	defer tx.Rollback(ctx)

	var existingID int64
	err = tx.QueryRow(ctx,
		`SELECT id FROM password WHERE user_id = $1 AND name = $2 AND website = $3 FOR UPDATE`,
		pw.UserID, pw.Name, pw.Website,
	).Scan(&existingID)

	if err == nil {
		pw.ID = existingID
		_, err = tx.Exec(ctx,
			`UPDATE password SET password=$1, username=$2 WHERE id=$3 AND user_id=$4`,
			pw.Password, pw.Username, pw.ID, pw.UserID,
		)
		if err != nil {
			return fmt.Errorf("upsert update: %w", err)
		}
	} else if errors.Is(err, pgx.ErrNoRows) {
		err = tx.QueryRow(ctx,
			`INSERT INTO password (password, name, website, username, user_id) VALUES ($1,$2,$3,$4,$5) RETURNING id`,
			pw.Password, pw.Name, pw.Website, pw.Username, pw.UserID,
		).Scan(&pw.ID)
		if err != nil {
			return fmt.Errorf("upsert insert: %w", err)
		}
	} else {
		return fmt.Errorf("upsert select: %w", err)
	}

	return tx.Commit(ctx)
}

func (r *PasswordRepo) Update(ctx context.Context, pw *model.Password) error {
	tag, err := r.pool.Exec(ctx,
		`UPDATE password SET password=$1, name=$2, website=$3, username=$4 WHERE id=$5 AND user_id=$6`,
		pw.Password, pw.Name, pw.Website, pw.Username, pw.ID, pw.UserID,
	)
	if err != nil {
		return fmt.Errorf("update: %w", err)
	}
	if tag.RowsAffected() == 0 {
		return ErrNotFound
	}
	return nil
}

func (r *PasswordRepo) Delete(ctx context.Context, id int64, userID string) error {
	tag, err := r.pool.Exec(ctx, `DELETE FROM password WHERE id = $1 AND user_id = $2`, id, userID)
	if err != nil {
		return fmt.Errorf("delete: %w", err)
	}
	if tag.RowsAffected() == 0 {
		return ErrNotFound
	}
	return nil
}
