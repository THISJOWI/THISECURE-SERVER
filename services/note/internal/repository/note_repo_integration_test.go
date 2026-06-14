//go:build integration

package repository_test

import (
	"context"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/thisuite/thisecure/note/internal/model"
	"github.com/thisuite/thisecure/note/internal/repository"
	"github.com/stretchr/testify/require"
	"github.com/testcontainers/testcontainers-go"
	"github.com/testcontainers/testcontainers-go/wait"
)

func setupTestDB(t *testing.T) (*pgxpool.Pool, func()) {
	ctx := context.Background()
	req := testcontainers.ContainerRequest{
		Image:        "postgres:16-alpine",
		ExposedPorts: []string{"5432/tcp"},
		Env: map[string]string{
			"POSTGRES_USER":     "test",
			"POSTGRES_PASSWORD": "test",
			"POSTGRES_DB":       "test",
		},
		WaitingFor: wait.ForLog("database system is ready to accept connections"),
	}
	container, err := testcontainers.GenericContainer(ctx, testcontainers.GenericContainerRequest{
		ContainerRequest: req,
		Started:          true,
	})
	require.NoError(t, err)

	port, err := container.MappedPort(ctx, "5432")
	require.NoError(t, err)
	dsn := "postgres://test:test@localhost:" + port.Port() + "/test?sslmode=disable"

	pool, err := pgxpool.New(ctx, dsn)
	require.NoError(t, err)

	_, err = pool.Exec(ctx, `CREATE TABLE IF NOT EXISTS notes (
		id BIGSERIAL PRIMARY KEY,
		content TEXT,
		title TEXT NOT NULL,
		created_at TIMESTAMP DEFAULT NOW(),
		user_id TEXT NOT NULL,
		version BIGINT DEFAULT 0 NOT NULL,
		CONSTRAINT uk_title_user UNIQUE (title, user_id)
	)`)
	require.NoError(t, err)

	return pool, func() {
		pool.Close()
		container.Terminate(ctx)
	}
}

func TestNoteRepo_InsertAndFind(t *testing.T) {
	pool, cleanup := setupTestDB(t)
	defer cleanup()
	ctx := context.Background()
	repo := repository.NewNoteRepo(pool)

	note := &model.Note{Title: "Test Title", Content: "Test Content", UserID: "user-1", CreatedAt: time.Now()}
	err := repo.Insert(ctx, note)
	require.NoError(t, err)
	require.NotZero(t, note.ID)

	found, err := repo.FindByID(ctx, note.ID)
	require.NoError(t, err)
	require.NotNil(t, found)
	require.Equal(t, "Test Title", found.Title)
	require.Equal(t, "Test Content", found.Content)
	require.Equal(t, "user-1", found.UserID)
}

func TestNoteRepo_FindByUserID(t *testing.T) {
	pool, cleanup := setupTestDB(t)
	defer cleanup()
	ctx := context.Background()
	repo := repository.NewNoteRepo(pool)

	for i := range 3 {
		n := &model.Note{Title: "Note " + string(rune('0'+i)), UserID: "user-1", CreatedAt: time.Now()}
		repo.Insert(ctx, n)
	}

	notes, err := repo.FindByUserID(ctx, "user-1")
	require.NoError(t, err)
	require.Len(t, notes, 3)
}

func TestNoteRepo_UniqueConstraint(t *testing.T) {
	pool, cleanup := setupTestDB(t)
	defer cleanup()
	ctx := context.Background()
	repo := repository.NewNoteRepo(pool)

	n1 := &model.Note{Title: "Unique Title", UserID: "user-1", CreatedAt: time.Now()}
	err := repo.Insert(ctx, n1)
	require.NoError(t, err)

	n2 := &model.Note{Title: "Unique Title", UserID: "user-1", CreatedAt: time.Now()}
	err = repo.Insert(ctx, n2)
	require.Error(t, err)
}

func TestNoteRepo_UpdateAndDelete(t *testing.T) {
	pool, cleanup := setupTestDB(t)
	defer cleanup()
	ctx := context.Background()
	repo := repository.NewNoteRepo(pool)

	note := &model.Note{Title: "Original", Content: "Original", UserID: "user-1", CreatedAt: time.Now()}
	repo.Insert(ctx, note)

	note.Content = "Updated"
	note.Title = "Updated"
	err := repo.Update(ctx, note)
	require.NoError(t, err)

	found, _ := repo.FindByID(ctx, note.ID)
	require.NotNil(t, found)
	require.Equal(t, "Updated", found.Content)

	err = repo.Delete(ctx, note.ID, "user-1")
	require.NoError(t, err)

	found, _ = repo.FindByID(ctx, note.ID)
	require.Nil(t, found)
}
