package repository

import (
	"context"
	"errors"
	"fmt"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/thisuite/thisecure/passkey/internal/model"
)

var ErrNotFound = errors.New("not found")

type PasskeyRepo struct {
	pool *pgxpool.Pool
}

func NewPasskeyRepo(pool *pgxpool.Pool) *PasskeyRepo {
	return &PasskeyRepo{pool: pool}
}

func (r *PasskeyRepo) FindByUserID(ctx context.Context, userID string) ([]model.Passkey, error) {
	rows, err := r.pool.Query(ctx, `
		SELECT id, credential_id, public_key, rp_id, rp_name, user_handle,
		       user_display_name, sign_count, name, transports, credential_type,
		       backup_eligible, backup_state, user_id
		FROM passkey WHERE user_id = $1 ORDER BY id`, userID)
	if err != nil {
		return nil, fmt.Errorf("query: %w", err)
	}
	defer rows.Close()
	return pgx.CollectRows(rows, pgx.RowToStructByName[model.Passkey])
}

func (r *PasskeyRepo) FindByID(ctx context.Context, id int64) (*model.Passkey, error) {
	rows, err := r.pool.Query(ctx, `
		SELECT id, credential_id, public_key, rp_id, rp_name, user_handle,
		       user_display_name, sign_count, name, transports, credential_type,
		       backup_eligible, backup_state, user_id
		FROM passkey WHERE id = $1`, id)
	if err != nil {
		return nil, fmt.Errorf("query: %w", err)
	}
	defer rows.Close()
	pk, err := pgx.CollectOneRow(rows, pgx.RowToStructByName[model.Passkey])
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, nil
		}
		return nil, fmt.Errorf("collect: %w", err)
	}
	return &pk, nil
}

func (r *PasskeyRepo) Insert(ctx context.Context, pk *model.Passkey) error {
	err := r.pool.QueryRow(ctx, `
		INSERT INTO passkey (credential_id, public_key, rp_id, rp_name, user_handle,
		                     user_display_name, sign_count, name, transports,
		                     credential_type, backup_eligible, backup_state, user_id)
		VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13) RETURNING id`,
		pk.CredentialID, pk.PublicKey, pk.RpID, pk.RpName, pk.UserHandle,
		pk.UserDisplayName, pk.SignCount, pk.Name, pk.Transports,
		pk.CredentialType, pk.BackupEligible, pk.BackupState, pk.UserID,
	).Scan(&pk.ID)
	if err != nil {
		return fmt.Errorf("insert: %w", err)
	}
	return nil
}

func (r *PasskeyRepo) Update(ctx context.Context, pk *model.Passkey) error {
	tag, err := r.pool.Exec(ctx, `
		UPDATE passkey SET
			credential_id=$1, public_key=$2, rp_id=$3, rp_name=$4,
			user_handle=$5, user_display_name=$6, sign_count=$7, name=$8,
			transports=$9, credential_type=$10, backup_eligible=$11, backup_state=$12
		WHERE id=$13 AND user_id=$14`,
		pk.CredentialID, pk.PublicKey, pk.RpID, pk.RpName,
		pk.UserHandle, pk.UserDisplayName, pk.SignCount, pk.Name,
		pk.Transports, pk.CredentialType, pk.BackupEligible, pk.BackupState,
		pk.ID, pk.UserID,
	)
	if err != nil {
		return fmt.Errorf("update: %w", err)
	}
	if tag.RowsAffected() == 0 {
		return ErrNotFound
	}
	return nil
}

func (r *PasskeyRepo) Delete(ctx context.Context, id int64, userID string) error {
	tag, err := r.pool.Exec(ctx, `DELETE FROM passkey WHERE id = $1 AND user_id = $2`, id, userID)
	if err != nil {
		return fmt.Errorf("delete: %w", err)
	}
	if tag.RowsAffected() == 0 {
		return ErrNotFound
	}
	return nil
}
