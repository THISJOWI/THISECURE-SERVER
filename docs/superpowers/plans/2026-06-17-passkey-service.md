# Passkey Service Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Create a new `passkey` Go microservice for WebAuthn credential CRUD, following the existing password service architecture.

**Architecture:** Gin HTTP framework on port 8086, pgx PostgreSQL CRUD, AES-256-GCM encryption on `public_key`, Kafka sync events. Identical pattern to the `password` service.

**Tech Stack:** Go 1.25, Gin, pgx/v5, pkg/crypto, pkg/kafka, pkg/middleware, pkg/models

---

### Task 0: Service scaffolding

**Files:**
- Create: `services/passkey/.version`
- Create: `services/passkey/go.mod`
- Create: `services/passkey/Dockerfile`
- Create: `services/passkey/internal/config/config.go`
- Modify: `go.work`
- Modify: `Makefile`

- [ ] **Step 1: Create directory structure**

```bash
mkdir -p services/passkey/cmd/server services/passkey/internal/{config,handler,model,repository,service} services/passkey/migrations
```

- [ ] **Step 2: Create version file**

Write `services/passkey/.version`:
```
1.0.0
```

- [ ] **Step 3: Create go.mod**

```bash
cd services/passkey && go mod init github.com/thisuite/thisecure/passkey
```

- [ ] **Step 4: Create Dockerfile**

Write `services/passkey/Dockerfile`:
```dockerfile
FROM golang:1.25-alpine AS builder
WORKDIR /app
COPY pkg/ pkg/
COPY services/passkey/ services/passkey/
WORKDIR /app/services/passkey
RUN go mod tidy && go build -o /server ./cmd/server/

FROM alpine:3.19
RUN adduser -D appuser
COPY --from=builder /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/
COPY --from=builder /server /server
USER appuser
EXPOSE 8086
HEALTHCHECK --interval=30s --timeout=3s CMD wget --no-verbose --tries=1 --spider http://localhost:8086/health || exit 1
CMD ["/server"]
```

- [ ] **Step 5: Create config.go**

Write `services/passkey/internal/config/config.go`:
```go
package config

import (
	"fmt"
	"os"
)

type Config struct {
	Port            string
	DatabaseURL     string
	JWTSecret       string
	EncryptionKey   string
	KafkaSigningKey string
	KafkaBrokers    []string
	DBSSLMode       string
}

func Load() Config {
	cfg := Config{
		Port:            getEnv("PORT", "8086"),
		JWTSecret:       getEnv("JWT_SECRET", ""),
		EncryptionKey:   getEnv("ENCRYPTION_KEY", ""),
		KafkaSigningKey: getEnv("KAFKA_SIGNING_KEY", ""),
		DBSSLMode:       getEnv("DB_SSLMODE", "disable"),
	}

	dbHost := getEnv("DB_HOST", "localhost")
	dbPort := getEnv("DB_PORT", "5432")
	dbUser := getEnv("DB_USERNAME", "postgres")
	dbPass := getEnv("DB_PASSWORD", "postgres")
	cfg.DatabaseURL = fmt.Sprintf("postgres://%s:%s@%s:%s/passkeys?sslmode=%s", dbUser, dbPass, dbHost, dbPort, cfg.DBSSLMode)

	kafkaHost := getEnv("KAFKA_HOST", "localhost")
	kafkaPort := getEnv("KAFKA_PORT", "9092")
	cfg.KafkaBrokers = []string{fmt.Sprintf("%s:%s", kafkaHost, kafkaPort)}

	return cfg
}

func getEnv(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}
```

- [ ] **Step 6: Update go.work**

Change `go.work` from:
```
use (
	./pkg
	./services/note
	./services/otp
	./services/password
)
```
to:
```
use (
	./pkg
	./services/note
	./services/otp
	./services/passkey
	./services/password
)
```

- [ ] **Step 7: Update Makefile**

Change `SERVICES := note otp password` to `SERVICES := note otp passkey password`

- [ ] **Step 8: Resolve dependencies**

```bash
cd services/passkey && go mod edit -require github.com/gin-gonic/gin@v1.10.0 -require github.com/google/uuid@v1.6.0 -require github.com/jackc/pgx/v5@v5.7.2 -require github.com/thisuite/thisecure/pkg@v0.0.0
cd services/passkey && go mod edit -replace github.com/thisuite/thisecure/pkg=../../pkg
```

### Task 1: Data model + migrations

**Files:**
- Create: `services/passkey/internal/model/passkey.go`
- Create: `services/passkey/migrations/001_create_passkey.up.sql`
- Create: `services/passkey/migrations/001_create_passkey.down.sql`

- [ ] **Step 1: Create model file**

Write `services/passkey/internal/model/passkey.go`:
```go
package model

type Passkey struct {
	ID              int64    `json:"id" db:"id"`
	CredentialID    string   `json:"credentialId" db:"credential_id"`
	PublicKey       string   `json:"publicKey" db:"public_key"`
	RpID            string   `json:"rpId" db:"rp_id"`
	RpName          string   `json:"rpName" db:"rp_name"`
	UserHandle      string   `json:"userHandle" db:"user_handle"`
	UserDisplayName string   `json:"userDisplayName" db:"user_display_name"`
	SignCount       int64    `json:"signCount" db:"sign_count"`
	Name            string   `json:"name" db:"name"`
	Transports      []string `json:"transports" db:"transports"`
	CredentialType  string   `json:"credentialType" db:"credential_type"`
	BackupEligible  bool     `json:"backupEligible" db:"backup_eligible"`
	BackupState     bool     `json:"backupState" db:"backup_state"`
	UserID          string   `json:"userId" db:"user_id"`
}

type PasskeyRequest struct {
	CredentialID    string   `json:"credentialId" binding:"required,max=1024"`
	PublicKey       string   `json:"publicKey" binding:"required,max=8192"`
	RpID            string   `json:"rpId" binding:"max=512"`
	RpName          string   `json:"rpName" binding:"max=255"`
	UserHandle      string   `json:"userHandle" binding:"max=1024"`
	UserDisplayName string   `json:"userDisplayName" binding:"max=255"`
	SignCount       int64    `json:"signCount"`
	Name            string   `json:"name" binding:"required,max=255"`
	Transports      []string `json:"transports"`
	CredentialType  string   `json:"credentialType" binding:"max=64"`
	BackupEligible  bool     `json:"backupEligible"`
	BackupState     bool     `json:"backupState"`
}
```

- [ ] **Step 2: Create migration up**

Write `services/passkey/migrations/001_create_passkey.up.sql`:
```sql
CREATE TABLE IF NOT EXISTS passkey (
    id BIGSERIAL PRIMARY KEY,
    credential_id TEXT NOT NULL,
    public_key TEXT,
    rp_id TEXT,
    rp_name TEXT,
    user_handle TEXT,
    user_display_name TEXT,
    sign_count BIGINT DEFAULT 0,
    name TEXT,
    transports TEXT[],
    credential_type TEXT DEFAULT 'public-key',
    backup_eligible BOOLEAN DEFAULT FALSE,
    backup_state BOOLEAN DEFAULT FALSE,
    user_id TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_passkey_user_id ON passkey(user_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_passkey_user_credential ON passkey(user_id, credential_id);
```

- [ ] **Step 3: Create migration down**

Write `services/passkey/migrations/001_create_passkey.down.sql`:
```sql
DROP TABLE IF EXISTS passkey;
```

### Task 2: Repository layer

**Files:**
- Create: `services/passkey/internal/repository/passkey_repo.go`

- [ ] **Step 1: Create repository file**

Write `services/passkey/internal/repository/passkey_repo.go`:
```go
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
```

### Task 3: Service layer

**Files:**
- Create: `services/passkey/internal/service/passkey_service.go`

- [ ] **Step 1: Create service file**

Write `services/passkey/internal/service/passkey_service.go`:
```go
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
			"id":             pk.ID,
			"credentialId":   pk.CredentialID,
			"rpId":           pk.RpID,
			"name":           pk.Name,
		},
		Timestamp: time.Now().UnixMilli(),
	}
	if err := s.syncEvents.Publish(context.Background(), pk.UserID, event); err != nil {
		log.Printf("WARN: failed to publish sync event: %v", err)
	}
}
```

### Task 4: Handler layer

**Files:**
- Create: `services/passkey/internal/handler/passkey_handler.go`

- [ ] **Step 1: Create handler file**

Write `services/passkey/internal/handler/passkey_handler.go`:
```go
package handler

import (
	"net/http"
	"strconv"
	"strings"

	"github.com/gin-gonic/gin"
	"github.com/thisuite/thisecure/passkey/internal/model"
	"github.com/thisuite/thisecure/passkey/internal/service"
	"github.com/thisuite/thisecure/pkg/middleware"
)

type PasskeyHandler struct {
	svc *service.PasskeyService
}

func NewPasskeyHandler(svc *service.PasskeyService) *PasskeyHandler {
	return &PasskeyHandler{svc: svc}
}

func (h *PasskeyHandler) Register(r *gin.RouterGroup) {
	r.GET("", h.GetAll)
	r.GET("/:id", h.GetByID)
	r.POST("", h.Create)
	r.PUT("/:id", h.Update)
	r.DELETE("/:id", h.Delete)
}

func (h *PasskeyHandler) error(c *gin.Context, status int, err error) {
	if strings.Contains(err.Error(), "not found") {
		c.JSON(http.StatusNotFound, gin.H{"error": "not found"})
		return
	}
	c.JSON(status, gin.H{"error": "internal server error"})
}

func (h *PasskeyHandler) GetAll(c *gin.Context) {
	userID := middleware.GetUserID(c)
	pks, err := h.svc.GetAll(c.Request.Context(), userID)
	if err != nil {
		h.error(c, http.StatusInternalServerError, err)
		return
	}
	if pks == nil {
		pks = []model.Passkey{}
	}
	c.JSON(http.StatusOK, pks)
}

func (h *PasskeyHandler) GetByID(c *gin.Context) {
	id, err := strconv.ParseInt(c.Param("id"), 10, 64)
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid id"})
		return
	}
	userID := middleware.GetUserID(c)
	pk, err := h.svc.GetByID(c.Request.Context(), id, userID)
	if err != nil {
		h.error(c, http.StatusInternalServerError, err)
		return
	}
	if pk == nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "not found"})
		return
	}
	c.JSON(http.StatusOK, pk)
}

func (h *PasskeyHandler) Create(c *gin.Context) {
	var req model.PasskeyRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	userID := middleware.GetUserID(c)
	pk, err := h.svc.Create(c.Request.Context(), req, userID)
	if err != nil {
		h.error(c, http.StatusInternalServerError, err)
		return
	}
	c.JSON(http.StatusOK, pk)
}

func (h *PasskeyHandler) Update(c *gin.Context) {
	id, err := strconv.ParseInt(c.Param("id"), 10, 64)
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid id"})
		return
	}
	var req model.PasskeyRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	userID := middleware.GetUserID(c)
	pk, err := h.svc.Update(c.Request.Context(), id, req, userID)
	if err != nil {
		h.error(c, http.StatusInternalServerError, err)
		return
	}
	c.JSON(http.StatusOK, pk)
}

func (h *PasskeyHandler) Delete(c *gin.Context) {
	id, err := strconv.ParseInt(c.Param("id"), 10, 64)
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid id"})
		return
	}
	userID := middleware.GetUserID(c)
	if err := h.svc.Delete(c.Request.Context(), id, userID); err != nil {
		h.error(c, http.StatusInternalServerError, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"message": "deleted"})
}
```

### Task 5: Main entry point

**Files:**
- Create: `services/passkey/cmd/server/main.go`

- [ ] **Step 1: Create main.go**

Write `services/passkey/cmd/server/main.go`:
```go
package main

import (
	"context"
	"encoding/hex"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/thisuite/thisecure/passkey/internal/config"
	"github.com/thisuite/thisecure/passkey/internal/handler"
	"github.com/thisuite/thisecure/passkey/internal/repository"
	"github.com/thisuite/thisecure/passkey/internal/service"
	"github.com/thisuite/thisecure/pkg/crypto"
	"github.com/thisuite/thisecure/pkg/database"
	"github.com/thisuite/thisecure/pkg/kafka"
	mid "github.com/thisuite/thisecure/pkg/middleware"
)

func main() {
	cfg := config.Load()
	ctx := context.Background()

	if cfg.JWTSecret == "" || len(cfg.JWTSecret) < 32 {
		log.Fatal("JWT_SECRET must be set and at least 32 characters")
	}
	if cfg.EncryptionKey == "" {
		log.Fatal("ENCRYPTION_KEY must be set")
	}
	encKey, err := hex.DecodeString(cfg.EncryptionKey)
	if err != nil {
		log.Fatalf("ENCRYPTION_KEY: invalid hex: %v", err)
	}
	if err := crypto.ValidateKey(encKey); err != nil {
		log.Fatalf("ENCRYPTION_KEY: %v", err)
	}

	pool, err := database.NewPool(ctx, database.DefaultConfig(cfg.DatabaseURL))
	if err != nil {
		log.Fatalf("database: %v", err)
	}
	defer pool.Close()

	jwtSecret := []byte(cfg.JWTSecret)
	if cfg.KafkaSigningKey == "" {
		log.Fatal("KAFKA_SIGNING_KEY must be set (separate from JWT_SECRET)")
	}
	signer := kafka.NewSigner([]byte(cfg.KafkaSigningKey))
	syncProducer := kafka.NewProducer(cfg.KafkaBrokers, "sync-events", signer)
	defer syncProducer.Close()

	pkRepo := repository.NewPasskeyRepo(pool)
	pkSvc := service.NewPasskeyService(pkRepo, encKey, syncProducer)
	pkH := handler.NewPasskeyHandler(pkSvc)

	gin.SetMode(gin.ReleaseMode)
	r := gin.Default()
	r.Use(mid.SecurityHeaders())
	r.Use(mid.CORS(nil))
	r.Use(mid.RateLimit(mid.NewRateLimiter(10, 20, time.Second)))
	r.GET("/health", func(c *gin.Context) { c.JSON(200, gin.H{"status": "ok"}) })

	v1 := r.Group("/v1/passkeys", mid.JWTAuth(jwtSecret))
	pkH.Register(v1)

	srv := &http.Server{
		Addr:         ":" + cfg.Port,
		Handler:      r,
		ReadTimeout:  10 * time.Second,
		WriteTimeout: 30 * time.Second,
		IdleTimeout:  120 * time.Second,
	}
	go func() {
		log.Printf("passkey service listening on :%s", cfg.Port)
		if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Fatalf("server: %v", err)
		}
	}()

	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	<-quit
	log.Println("shutting down...")

	shutdownCtx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	srv.Shutdown(shutdownCtx)
}
```

### Task 6: Infrastructure changes

**Files:**
- Modify: `docker-compose.yaml`

- [ ] **Step 1: Add postgres-passkey and passkey services to docker-compose**

Add after the `password` service block in `docker-compose.yaml`:

```yaml
  postgres-passkey:
    image: postgres:16-alpine
    environment:
      POSTGRES_DB: passkeys
      POSTGRES_USER: postgres
      POSTGRES_PASSWORD: postgres
    ports:
      - "5436:5432"
    volumes:
      - pg-passkey:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U postgres"]
      interval: 5s
      timeout: 5s
      retries: 5

  passkey:
    build:
      context: .
      dockerfile: services/passkey/Dockerfile
    ports:
      - "8086:8086"
    environment:
      PORT: "8086"
      DB_HOST: postgres-passkey
      DB_PORT: "5432"
      DB_USERNAME: postgres
      DB_PASSWORD: postgres
      KAFKA_HOST: kafka
      KAFKA_PORT: "9092"
      JWT_SECRET: ${JWT_SECRET}
      ENCRYPTION_KEY: ${ENCRYPTION_KEY}
    depends_on:
      postgres-passkey:
        condition: service_healthy
      kafka:
        condition: service_started
```

Add `pg-passkey:` to the `volumes:` block at the end.

### Task 7: Verify build

**Files:** (none required)

- [ ] **Step 1: Resolve dependencies and build**

```bash
cd services/passkey && go mod tidy && go build ./cmd/server/
```

- [ ] **Step 2: Run go vet**

```bash
cd services/passkey && go vet ./...
```

- [ ] **Step 3: Full workspace build**

```bash
go build ./...
```
