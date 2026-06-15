package repository

import (
	"context"
	"errors"
	"fmt"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/thisuite/thisecure/otp/internal/model"
)

var ErrNotFound = errors.New("not found")

type OtpRepo struct {
	pool *pgxpool.Pool
}

func NewOtpRepo(pool *pgxpool.Pool) *OtpRepo {
	return &OtpRepo{pool: pool}
}

func (r *OtpRepo) FindByUserID(ctx context.Context, userID string) ([]model.Otp, error) {
	rows, err := r.pool.Query(ctx, `SELECT id, user_id, email, secret, expires_at, type, issuer, CASE WHEN digits ~ '^\d+$' THEN digits::int ELSE 0 END AS digits, CASE WHEN period ~ '^\d+$' THEN period::int ELSE 0 END AS period, algorithm, valid FROM otp WHERE user_id = $1 ORDER BY id`, userID)
	if err != nil {
		return nil, fmt.Errorf("query: %w", err)
	}
	defer rows.Close()
	return pgx.CollectRows(rows, pgx.RowToStructByName[model.Otp])
}

func (r *OtpRepo) FindByID(ctx context.Context, id int64) (*model.Otp, error) {
	rows, err := r.pool.Query(ctx, `SELECT id, user_id, email, secret, expires_at, type, issuer, CASE WHEN digits ~ '^\d+$' THEN digits::int ELSE 0 END AS digits, CASE WHEN period ~ '^\d+$' THEN period::int ELSE 0 END AS period, algorithm, valid FROM otp WHERE id = $1`, id)
	if err != nil {
		return nil, fmt.Errorf("query: %w", err)
	}
	defer rows.Close()
	otp, err := pgx.CollectOneRow(rows, pgx.RowToStructByName[model.Otp])
	if err != nil {
		if err == pgx.ErrNoRows {
			return nil, nil
		}
		return nil, fmt.Errorf("collect: %w", err)
	}
	return &otp, nil
}

func (r *OtpRepo) Insert(ctx context.Context, o *model.Otp) error {
	err := r.pool.QueryRow(ctx,
		`INSERT INTO otp (user_id, email, secret, expires_at, type, issuer, digits, period, algorithm, valid) VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10) RETURNING id`,
		o.UserID, o.Email, o.Secret, o.ExpiresAt, o.Type, o.Issuer, o.Digits, o.Period, o.Algorithm, o.Valid,
	).Scan(&o.ID)
	if err != nil {
		return fmt.Errorf("insert: %w", err)
	}
	return nil
}

func (r *OtpRepo) Update(ctx context.Context, o *model.Otp) error {
	tag, err := r.pool.Exec(ctx,
		`UPDATE otp SET email=$1, secret=$2, expires_at=$3, type=$4, issuer=$5, digits=$6, period=$7, algorithm=$8, valid=$9 WHERE id=$10 AND user_id=$11`,
		o.Email, o.Secret, o.ExpiresAt, o.Type, o.Issuer, o.Digits, o.Period, o.Algorithm, o.Valid, o.ID, o.UserID,
	)
	if err != nil {
		return fmt.Errorf("update: %w", err)
	}
	if tag.RowsAffected() == 0 {
		return ErrNotFound
	}
	return nil
}

func (r *OtpRepo) FindByUserIDAndEmail(ctx context.Context, userID, email string) (*model.Otp, error) {
	rows, err := r.pool.Query(ctx, `SELECT id, user_id, email, secret, expires_at, type, issuer, CASE WHEN digits ~ '^\d+$' THEN digits::int ELSE 0 END AS digits, CASE WHEN period ~ '^\d+$' THEN period::int ELSE 0 END AS period, algorithm, valid FROM otp WHERE user_id = $1 AND email = $2`, userID, email)
	if err != nil {
		return nil, fmt.Errorf("query: %w", err)
	}
	defer rows.Close()
	otp, err := pgx.CollectOneRow(rows, pgx.RowToStructByName[model.Otp])
	if err != nil {
		if err == pgx.ErrNoRows {
			return nil, nil
		}
		return nil, fmt.Errorf("collect: %w", err)
	}
	return &otp, nil
}

func (r *OtpRepo) Remove(ctx context.Context, id int64, userID string) error {
	tag, err := r.pool.Exec(ctx, `DELETE FROM otp WHERE id = $1 AND user_id = $2`, id, userID)
	if err != nil {
		return fmt.Errorf("delete: %w", err)
	}
	if tag.RowsAffected() == 0 {
		return ErrNotFound
	}
	return nil
}
