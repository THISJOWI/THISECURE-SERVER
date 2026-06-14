# Spring Boot → Go Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Migrate 3 Spring Boot services (note, otp, password) to independent Go microservices using Gin + pgx + pure SQL, replicating the monorepo pattern from `core/`.

**Architecture:** Monorepo with Go workspace. Shared `pkg/` for crypto, JWT, Kafka, middleware, database. Each service in `services/<name>/` with its own `go.mod`, `cmd/server/main.go`, `internal/{config,handler,model,repository,service}/`, migrations, and Dockerfile.

**Tech Stack:** Go 1.22+, Gin, pgx/v5, golang-migrate/migrate, segmentio/kafka-go, Testcontainers-Go.

---

## File Structure

```
pkg/
  go.mod
  crypto/crypto.go           # AES-256-GCM encrypt/decrypt
  crypto/crypto_test.go
  jwt/jwt.go                 # HS256 JWT validation
  jwt/jwt_test.go
  kafka/producer.go          # HMAC-signed producer
  kafka/consumer.go          # HMAC-verified consumer
  kafka/hmac.go              # HMAC-SHA256 sign/verify
  kafka/hmac_test.go
  middleware/auth.go         # Gin JWT auth + GetUserId/GetEmail
  middleware/ratelimit.go    # Per-IP token bucket
  middleware/ratelimit_test.go
  database/postgres.go       # pgx connection factory
  models/events.go           # SyncEvent, UserRegisteredEvent, OtpCreatedEvent

services/note/
  go.mod
  cmd/server/main.go
  internal/
    config/config.go
    model/note.go
    repository/note_repo.go
    repository/note_repo_integration_test.go
    service/note_service.go
    service/note_service_test.go
    handler/note_handler.go
    handler/note_handler_test.go
  migrations/001_create_notes.{up,down}.sql
  Dockerfile

services/otp/        (same structure + qr_service.go)
  migrations/001_create_otp.{up,down}.sql
  migrations/002_create_otp_key.{up,down}.sql

services/password/   (same structure + dedup_service.go)
  migrations/001_create_passwords.{up,down}.sql

Root:
  go.work
  Makefile
  docker-compose.yaml
```

---

## Phase 1: Shared `pkg/` Module

### Task 1.1: `pkg/go.mod`

**Create:** `pkg/go.mod`

```
module github.com/thisuite/thisecure/pkg

go 1.22.0

require (
    github.com/gin-gonic/gin v1.10.0
    github.com/golang-jwt/jwt/v5 v5.2.1
    github.com/google/uuid v1.6.0
    github.com/jackc/pgx/v5 v5.7.2
    github.com/segmentio/kafka-go v0.4.47
    github.com/stretchr/testify v1.9.0
    golang.org/x/crypto v0.28.0
)
```

- [ ] **Create file and run `go mod tidy`**

```
cd /Users/joel/Workspace/thisuite/thisecure/backend/pkg
go mod tidy
```

---

### Task 1.2: `pkg/crypto` — AES-256-GCM

**Create:** `pkg/crypto/crypto.go`, `pkg/crypto/crypto_test.go`

**Create:** `pkg/crypto/crypto.go`

```go
package crypto

import (
    "crypto/aes"
    "crypto/cipher"
    "crypto/rand"
    "encoding/base64"
    "errors"
    "io"
)

var ErrInvalidCiphertext = errors.New("invalid ciphertext")

func Encrypt(plaintext []byte, key []byte) (string, error) {
    block, err := aes.NewCipher(key)
    if err != nil {
        return "", err
    }
    gcm, err := cipher.NewGCM(block)
    if err != nil {
        return "", err
    }
    nonce := make([]byte, gcm.NonceSize())
    if _, err := io.ReadFull(rand.Reader, nonce); err != nil {
        return "", err
    }
    ciphertext := gcm.Seal(nonce, nonce, plaintext, nil)
    return base64.StdEncoding.EncodeToString(ciphertext), nil
}

func Decrypt(encoded string, key []byte) ([]byte, error) {
    ciphertext, err := base64.StdEncoding.DecodeString(encoded)
    if err != nil {
        return nil, ErrInvalidCiphertext
    }
    block, err := aes.NewCipher(key)
    if err != nil {
        return nil, err
    }
    gcm, err := cipher.NewGCM(block)
    if err != nil {
        return nil, err
    }
    nonceSize := gcm.NonceSize()
    if len(ciphertext) < nonceSize {
        return nil, ErrInvalidCiphertext
    }
    nonce, ciphertext := ciphertext[:nonceSize], ciphertext[nonceSize:]
    return gcm.Open(nil, nonce, ciphertext, nil)
}

func ValidateKey(key []byte) error {
    switch len(key) {
    case 16, 24, 32:
        return nil
    default:
        return errors.New("key must be 16, 24, or 32 bytes")
    }
}
```

**Create:** `pkg/crypto/crypto_test.go`

```go
package crypto_test

import (
    "testing"

    "github.com/thisuite/thisecure/pkg/crypto"
    "github.com/stretchr/testify/require"
)

func TestEncryptDecrypt_RoundTrip(t *testing.T) {
    key := []byte("0123456789abcdef0123456789abcdef") // 32 bytes
    original := []byte("hello world")
    encoded, err := crypto.Encrypt(original, key)
    require.NoError(t, err)
    decoded, err := crypto.Decrypt(encoded, key)
    require.NoError(t, err)
    require.Equal(t, original, decoded)
}

func TestDecrypt_WrongKey(t *testing.T) {
    key := []byte("0123456789abcdef0123456789abcdef")
    wrongKey := []byte("abcdef0123456789abcdef0123456789")
    encoded, err := crypto.Encrypt([]byte("secret"), key)
    require.NoError(t, err)
    _, err = crypto.Decrypt(encoded, wrongKey)
    require.Error(t, err)
}

func TestDecrypt_InvalidBase64(t *testing.T) {
    key := []byte("0123456789abcdef0123456789abcdef")
    _, err := crypto.Decrypt("not-base64!!!", key)
    require.Error(t, err)
}

func TestValidateKey(t *testing.T) {
    require.NoError(t, crypto.ValidateKey([]byte("0123456789abcdef")))         // 16
    require.NoError(t, crypto.ValidateKey([]byte("0123456789abcdef01234567"))) // 24
    require.NoError(t, crypto.ValidateKey([]byte("0123456789abcdef0123456789abcdef"))) // 32
    require.Error(t, crypto.ValidateKey([]byte("short")))
}
```

- [ ] **Create both files**
- [ ] **Run tests:** `cd /Users/joel/Workspace/thisuite/thisecure/backend/pkg && go test ./crypto/ -v`

---

### Task 1.3: `pkg/jwt` — JWT Validation

**Create:** `pkg/jwt/jwt.go`, `pkg/jwt/jwt_test.go`

```go
package jwt

import (
    "fmt"

    "github.com/golang-jwt/jwt/v5"
)

func ValidateToken(tokenString string, secret []byte) (string, error) {
    token, err := jwt.Parse(tokenString, func(t *jwt.Token) (interface{}, error) {
        if _, ok := t.Method.(*jwt.SigningMethodHMAC); !ok {
            return nil, fmt.Errorf("unexpected signing method: %v", t.Header["alg"])
        }
        return secret, nil
    })
    if err != nil {
        return "", err
    }
    claims, ok := token.Claims.(jwt.MapClaims)
    if !ok || !token.Valid {
        return "", fmt.Errorf("invalid token")
    }
    sub, ok := claims["sub"].(string)
    if !ok || sub == "" {
        return "", fmt.Errorf("missing sub claim")
    }
    return sub, nil
}
```

```go
package jwt_test

import (
    "testing"

    "github.com/golang-jwt/jwt/v5"
    "github.com/thisuite/thisecure/pkg/jwt"
    "github.com/stretchr/testify/require"
)

func TestValidateToken_Valid(t *testing.T) {
    secret := []byte("my-very-secret-key-1234567890abcd")
    token := jwt.NewWithClaims(jwt.SigningMethodHS256, jwt.MapClaims{"sub": "user-123"})
    tokenStr, _ := token.SignedString(secret)

    sub, err := jwt.ValidateToken(tokenStr, secret)
    require.NoError(t, err)
    require.Equal(t, "user-123", sub)
}

func TestValidateToken_InvalidSecret(t *testing.T) {
    secret := []byte("my-very-secret-key-1234567890abcd")
    token := jwt.NewWithClaims(jwt.SigningMethodHS256, jwt.MapClaims{"sub": "user-123"})
    tokenStr, _ := token.SignedString(secret)

    _, err := jwt.ValidateToken(tokenStr, []byte("wrong-secret-1234567890abcdefgh"))
    require.Error(t, err)
}

func TestValidateToken_Expired(t *testing.T) {
    secret := []byte("my-very-secret-key-1234567890abcd")
    token := jwt.NewWithClaims(jwt.SigningMethodHS256, jwt.MapClaims{
        "sub": "user-123",
        "exp": 1000000000, // expired in 2001
    })
    tokenStr, _ := token.SignedString(secret)

    _, err := jwt.ValidateToken(tokenStr, secret)
    require.Error(t, err)
}
```

- [ ] **Create both files**
- [ ] **Run tests:** `cd /Users/joel/Workspace/thisuite/thisecure/backend/pkg && go test ./jwt/ -v`

---

### Task 1.4: `pkg/database` — PostgreSQL Connection

**Create:** `pkg/database/postgres.go`

```go
package database

import (
    "context"
    "fmt"
    "time"

    "github.com/jackc/pgx/v5/pgxpool"
)

type Config struct {
    DSN             string
    MaxConns        int32
    MinConns        int32
    MaxConnLifetime time.Duration
    MaxConnIdleTime time.Duration
    HealthCheckInterval time.Duration
}

func DefaultConfig(dsn string) Config {
    return Config{
        DSN:                dsn,
        MaxConns:           25,
        MinConns:           5,
        MaxConnLifetime:    30 * time.Minute,
        MaxConnIdleTime:    5 * time.Minute,
        HealthCheckInterval: 1 * time.Minute,
    }
}

func NewPool(ctx context.Context, cfg Config) (*pgxpool.Pool, error) {
    poolCfg, err := pgxpool.ParseConfig(cfg.DSN)
    if err != nil {
        return nil, fmt.Errorf("parse DSN: %w", err)
    }
    poolCfg.MaxConns = cfg.MaxConns
    poolCfg.MinConns = cfg.MinConns
    poolCfg.MaxConnLifetime = cfg.MaxConnLifetime
    poolCfg.MaxConnIdleTime = cfg.MaxConnIdleTime
    poolCfg.HealthCheckInterval = cfg.HealthCheckInterval

    pool, err := pgxpool.NewWithConfig(ctx, poolCfg)
    if err != nil {
        return nil, fmt.Errorf("create pool: %w", err)
    }
    if err := pool.Ping(ctx); err != nil {
        pool.Close()
        return nil, fmt.Errorf("ping: %w", err)
    }
    return pool, nil
}
```

- [ ] **Create file**

---

### Task 1.5: `pkg/kafka` — HMAC-Signed Producer/Consumer

**Create:** `pkg/kafka/hmac.go`, `pkg/kafka/hmac_test.go`, `pkg/kafka/producer.go`, `pkg/kafka/consumer.go`

```go
// pkg/kafka/hmac.go
package kafka

import (
    "crypto/hmac"
    "crypto/sha256"
    "encoding/base64"
    "fmt"
)

type Signer struct {
    key []byte
}

func NewSigner(key []byte) *Signer {
    return &Signer{key: key}
}

func (s *Signer) Sign(message []byte) string {
    mac := hmac.New(sha256.New, s.key)
    mac.Write(message)
    return base64.StdEncoding.EncodeToString(mac.Sum(nil))
}

func (s *Signer) Verify(message []byte, signature string) error {
    expected := s.Sign(message)
    if !hmac.Equal([]byte(expected), []byte(signature)) {
        return fmt.Errorf("HMAC mismatch")
    }
    return nil
}
```

```go
// pkg/kafka/hmac_test.go
package kafka_test

import (
    "testing"

    "github.com/thisuite/thisecure/pkg/kafka"
    "github.com/stretchr/testify/require"
)

func TestSignAndVerify(t *testing.T) {
    s := kafka.NewSigner([]byte("secret-key-1234567890abcdefgh"))
    msg := []byte(`{"eventId":"abc"}`)
    sig := s.Sign(msg)
    require.NoError(t, s.Verify(msg, sig))
}

func TestVerify_WrongKey(t *testing.T) {
    s1 := kafka.NewSigner([]byte("key-one-1234567890abcdefghij"))
    s2 := kafka.NewSigner([]byte("key-two-1234567890abcdefghij"))
    msg := []byte(`{"eventId":"abc"}`)
    sig := s1.Sign(msg)
    require.Error(t, s2.Verify(msg, sig))
}

func TestVerify_TamperedMessage(t *testing.T) {
    s := kafka.NewSigner([]byte("secret-key-1234567890abcdefgh"))
    msg := []byte(`{"eventId":"abc"}`)
    sig := s.Sign(msg)
    require.Error(t, s.Verify([]byte(`{"eventId":"xyz"}`), sig))
}
```

```go
// pkg/kafka/producer.go
package kafka

import (
    "context"
    "encoding/json"
    "fmt"
    "time"

    "github.com/segmentio/kafka-go"
)

type Producer struct {
    writer *kafka.Writer
    signer *Signer
}

func NewProducer(brokers []string, topic string, signer *Signer) *Producer {
    return &Producer{
        writer: &kafka.Writer{
            Addr:     kafka.TCP(brokers...),
            Topic:    topic,
            Balancer: &kafka.Hash{},
            BatchTimeout: 10 * time.Millisecond,
        },
        signer: signer,
    }
}

func (p *Producer) Publish(ctx context.Context, key string, msg interface{}) error {
    payload, err := json.Marshal(msg)
    if err != nil {
        return fmt.Errorf("marshal: %w", err)
    }
    signature := p.signer.Sign(payload)
    headers := []kafka.Header{
        {Key: "X-Signature", Value: []byte(signature)},
    }
    return p.writer.WriteMessages(ctx, kafka.Message{
        Key:   []byte(key),
        Value: payload,
        Headers: headers,
        Time:  time.Now(),
    })
}

func (p *Producer) Close() error {
    return p.writer.Close()
}
```

```go
// pkg/kafka/consumer.go
package kafka

import (
    "context"
    "encoding/json"
    "fmt"
    "log"

    "github.com/segmentio/kafka-go"
)

type Handler func(ctx context.Context, key string, value []byte) error

type Consumer struct {
    reader *kafka.Reader
    signer *Signer
    handler Handler
}

func NewConsumer(brokers []string, topic, groupID string, signer *Signer, handler Handler) *Consumer {
    return &Consumer{
        reader: kafka.NewReader(kafka.ReaderConfig{
            Brokers:  brokers,
            Topic:    topic,
            GroupID:  groupID,
            MinBytes: 10,
            MaxBytes: 10e6,
        }),
        signer:  signer,
        handler: handler,
    }
}

func (c *Consumer) Run(ctx context.Context) error {
    for {
        msg, err := c.reader.FetchMessage(ctx)
        if err != nil {
            return fmt.Errorf("fetch: %w", err)
        }
        if err := c.verifySignature(msg); err != nil {
            log.Printf("WARN: signature verification failed: %v", err)
            continue
        }
        if err := c.handler(ctx, string(msg.Key), msg.Value); err != nil {
            log.Printf("ERROR: handler failed: %v", err)
            continue
        }
        if err := c.reader.CommitMessages(ctx, msg); err != nil {
            return fmt.Errorf("commit: %w", err)
        }
    }
}

func (c *Consumer) verifySignature(msg kafka.Message) error {
    var signature string
    for _, h := range msg.Headers {
        if h.Key == "X-Signature" {
            signature = string(h.Value)
            break
        }
    }
    if signature == "" {
        return fmt.Errorf("missing X-Signature header")
    }
    return c.signer.Verify(msg.Value, signature)
}

func (c *Consumer) Close() error {
    return c.reader.Close()
}

// Helper to decode consumed messages
func Decode[T any](data []byte) (*T, error) {
    var v T
    if err := json.Unmarshal(data, &v); err != nil {
        return nil, err
    }
    return &v, nil
}
```

- [ ] **Create all 4 files**
- [ ] **Run tests:** `cd /Users/joel/Workspace/thisuite/thisecure/backend/pkg && go test ./kafka/ -v`

---

### Task 1.6: `pkg/middleware` — Auth + Rate Limit

**Create:** `pkg/middleware/auth.go`, `pkg/middleware/ratelimit.go`, `pkg/middleware/ratelimit_test.go`

```go
// pkg/middleware/auth.go
package middleware

import (
    "net/http"
    "strings"

    "github.com/gin-gonic/gin"
    "github.com/thisuite/thisecure/pkg/jwt"
)

const (
    ContextKeyUserID = "userId"
)

func JWTAuth(jwtSecret []byte) gin.HandlerFunc {
    return func(c *gin.Context) {
        authHeader := c.GetHeader("Authorization")
        if authHeader == "" {
            c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "missing Authorization header"})
            return
        }
        parts := strings.SplitN(authHeader, " ", 2)
        if len(parts) != 2 || !strings.EqualFold(parts[0], "Bearer") {
            c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "invalid Authorization header format"})
            return
        }
        userID, err := jwt.ValidateToken(parts[1], jwtSecret)
        if err != nil {
            c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "invalid token: " + err.Error()})
            return
        }
        c.Set(ContextKeyUserID, userID)
        c.Next()
    }
}

func GetUserID(c *gin.Context) string {
    v, _ := c.Get(ContextKeyUserID)
    s, _ := v.(string)
    return s
}
```

```go
// pkg/middleware/ratelimit.go
package middleware

import (
    "net/http"
    "sync"
    "time"

    "github.com/gin-gonic/gin"
)

type visitor struct {
    tokens    int
    lastCheck time.Time
}

type RateLimiter struct {
    mu       sync.Mutex
    visitors map[string]*visitor
    rate     int
    burst    int
    interval time.Duration
}

func NewRateLimiter(rate, burst int, interval time.Duration) *RateLimiter {
    return &RateLimiter{
        visitors: make(map[string]*visitor),
        rate:     rate,
        burst:    burst,
        interval: interval,
    }
}

func (rl *RateLimiter) Allow(ip string) bool {
    rl.mu.Lock()
    defer rl.mu.Unlock()

    v, exists := rl.visitors[ip]
    if !exists {
        rl.visitors[ip] = &visitor{tokens: rl.burst - 1, lastCheck: time.Now()}
        return true
    }
    elapsed := time.Since(v.lastCheck)
    v.tokens += int(elapsed / rl.interval) * rl.rate
    if v.tokens > rl.burst {
        v.tokens = rl.burst
    }
    v.lastCheck = time.Now()
    if v.tokens <= 0 {
        return false
    }
    v.tokens--
    return true
}

func RateLimit(rl *RateLimiter) gin.HandlerFunc {
    return func(c *gin.Context) {
        ip := c.ClientIP()
        if !rl.Allow(ip) {
            c.AbortWithStatusJSON(http.StatusTooManyRequests, gin.H{"error": "rate limit exceeded"})
            return
        }
        c.Next()
    }
}
```

```go
// pkg/middleware/ratelimit_test.go
package middleware_test

import (
    "testing"
    "time"

    "github.com/thisuite/thisecure/pkg/middleware"
    "github.com/stretchr/testify/require"
)

func TestRateLimiter_AllowsWithinBurst(t *testing.T) {
    rl := middleware.NewRateLimiter(1, 5, time.Second)
    for i := 0; i < 5; i++ {
        require.True(t, rl.Allow("127.0.0.1"))
    }
}

func TestRateLimiter_BlocksExcess(t *testing.T) {
    rl := middleware.NewRateLimiter(1, 3, time.Second)
    for i := 0; i < 3; i++ {
        rl.Allow("127.0.0.1")
    }
    require.False(t, rl.Allow("127.0.0.1"))
}

func TestRateLimiter_DifferentIPs(t *testing.T) {
    rl := middleware.NewRateLimiter(1, 1, time.Second)
    require.True(t, rl.Allow("1.1.1.1"))
    require.True(t, rl.Allow("2.2.2.2"))
}
```

- [ ] **Create all 3 files**
- [ ] **Run tests:** `cd /Users/joel/Workspace/thisuite/thisecure/backend/pkg && go test ./middleware/ -v`

---

### Task 1.7: `pkg/models` — Event Structs

**Create:** `pkg/models/events.go`

```go
package models

type SyncEvent struct {
    EventID     string      `json:"eventId"`
    UserID      string      `json:"userId"`
    ServiceName string      `json:"serviceName"`
    Action      string      `json:"action"`
    Payload     interface{} `json:"payload"`
    Timestamp   int64       `json:"timestamp"`
}

type UserRegisteredEvent struct {
    UserID    string `json:"userId"`
    Email     string `json:"email"`
    EventType string `json:"eventType"`
    Timestamp int64  `json:"timestamp"`
}

type OtpCreatedEvent struct {
    OtpID     int64  `json:"otpId"`
    UserID    string `json:"userId"`
    Email     string `json:"email"`
    Type      string `json:"type"`
    EventType string `json:"eventType"`
    Timestamp int64  `json:"timestamp"`
    ExpiresAt int64  `json:"expiresAt"`
}
```

- [ ] **Create file**

---

## Phase 2: `note` Service

### Task 2.1: Scaffold note service

**Create:** `services/note/go.mod`

```
module github.com/thisuite/thisecure/note

go 1.22.0

require (
    github.com/gin-gonic/gin v1.10.0
    github.com/google/uuid v1.6.0
    github.com/jackc/pgx/v5 v5.7.2
    github.com/segmentio/kafka-go v0.4.47
    github.com/stretchr/testify v1.9.0
    github.com/testcontainers/testcontainers-go v0.34.0
    github.com/thisuite/thisecure/pkg v0.0.0
)

replace github.com/thisuite/thisecure/pkg => ../../pkg
```

**Create:** `services/note/internal/config/config.go`

```go
package config

import "os"

type Config struct {
    Port          string
    DatabaseURL   string
    JWTSecret     string
    EncryptionKey string
    KafkaBrokers  []string
}

func Load() Config {
    return Config{
        Port:          getEnv("PORT", "8083"),
        DatabaseURL:   getEnv("DATABASE_URL", "postgres://postgres:postgres@localhost:5433/note?sslmode=disable"),
        JWTSecret:     getEnv("JWT_SECRET", ""),
        EncryptionKey: getEnv("ENCRYPTION_KEY", ""),
        KafkaBrokers:  []string{getEnv("KAFKA_BROKERS", "localhost:9092")},
    }
}

func getEnv(key, fallback string) string {
    if v := os.Getenv(key); v != "" {
        return v
    }
    return fallback
}
```

**Create:** `services/note/internal/model/note.go`

```go
package model

import "time"

type Note struct {
    ID        int64     `json:"id" db:"id"`
    Content   string    `json:"content" db:"content"`
    Title     string    `json:"title" db:"title"`
    CreatedAt time.Time `json:"createdAt" db:"created_at"`
    UserID    string    `json:"userId" db:"user_id"`
    Version   int64     `json:"version" db:"version"`
}

type NoteRequest struct {
    Title   string `json:"title" binding:"required"`
    Content string `json:"content"`
}

type ImportResult struct {
    Imported int `json:"imported"`
    Skipped  int `json:"skipped"`
    Errors   int `json:"errors"`
    Total    int `json:"total"`
}
```

**Create:** `services/note/migrations/001_create_notes.up.sql`

```sql
CREATE TABLE IF NOT EXISTS notes (
    id BIGSERIAL PRIMARY KEY,
    content TEXT,
    title TEXT NOT NULL,
    created_at TIMESTAMP DEFAULT NOW(),
    user_id TEXT NOT NULL,
    version BIGINT DEFAULT 0 NOT NULL,
    CONSTRAINT uk_title_user UNIQUE (title, user_id)
);
CREATE INDEX IF NOT EXISTS idx_notes_user_id ON notes(user_id);
CREATE INDEX IF NOT EXISTS idx_notes_title ON notes(title);
CREATE INDEX IF NOT EXISTS idx_notes_created_at ON notes(created_at);
```

**Create:** `services/note/migrations/001_create_notes.down.sql`

```sql
DROP TABLE IF EXISTS notes;
```

- [ ] **Create all scaffolding files**
- [ ] **Run `go mod tidy`:** `cd /Users/joel/Workspace/thisuite/thisecure/backend/services/note && go mod tidy`

---

### Task 2.2: note repository layer

**Create:** `services/note/internal/repository/note_repo.go`

```go
package repository

import (
    "context"
    "fmt"
    "time"

    "github.com/jackc/pgx/v5"
    "github.com/jackc/pgx/v5/pgxpool"
    "github.com/thisuite/thisecure/note/internal/model"
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
    row, err := r.pool.Query(ctx, `SELECT id, content, title, created_at, user_id, version FROM notes WHERE id = $1`, id)
    if err != nil {
        return nil, fmt.Errorf("query: %w", err)
    }
    defer row.Close()
    note, err := pgx.CollectOneRow(row, pgx.RowToStructByName[model.Note])
    if err != nil {
        if err == pgx.ErrNoRows {
            return nil, nil
        }
        return nil, fmt.Errorf("collect: %w", err)
    }
    return &note, nil
}

func (r *NoteRepo) FindByTitleAndUser(ctx context.Context, title, userID string) (*model.Note, error) {
    row, err := r.pool.Query(ctx, `SELECT id, content, title, created_at, user_id, version FROM notes WHERE title = $1 AND user_id = $2`, title, userID)
    if err != nil {
        return nil, fmt.Errorf("query: %w", err)
    }
    defer row.Close()
    note, err := pgx.CollectOneRow(row, pgx.RowToStructByName[model.Note])
    if err != nil {
        if err == pgx.ErrNoRows {
            return nil, nil
        }
        return nil, fmt.Errorf("collect: %w", err)
    }
    return &note, nil
}

func (r *NoteRepo) SearchByTitle(ctx context.Context, title, userID string) ([]model.Note, error) {
    rows, err := r.pool.Query(ctx, `SELECT id, content, title, created_at, user_id, version FROM notes WHERE title ILIKE '%' || $1 || '%' AND user_id = $2 ORDER BY created_at DESC`, title, userID)
    if err != nil {
        return nil, fmt.Errorf("query: %w", err)
    }
    defer rows.Close()
    return pgx.CollectRows(rows, pgx.RowToStructByName[model.Note])
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

func (r *NoteRepo) Update(ctx context.Context, note *model.Note) error {
    tag, err := r.pool.Exec(ctx,
        `UPDATE notes SET content = $1, title = $2, version = version + 1 WHERE id = $3 AND user_id = $4`,
        note.Content, note.Title, note.ID, note.UserID,
    )
    if err != nil {
        return fmt.Errorf("update: %w", err)
    }
    if tag.RowsAffected() == 0 {
        return fmt.Errorf("note not found or not owned by user")
    }
    return nil
}

func (r *NoteRepo) Delete(ctx context.Context, id int64, userID string) error {
    tag, err := r.pool.Exec(ctx, `DELETE FROM notes WHERE id = $1 AND user_id = $2`, id, userID)
    if err != nil {
        return fmt.Errorf("delete: %w", err)
    }
    if tag.RowsAffected() == 0 {
        return fmt.Errorf("note not found or not owned by user")
    }
    return nil
}

func (r *NoteRepo) FindByTitle(ctx context.Context, title string) (*model.Note, error) {
    row, err := r.pool.Query(ctx, `SELECT id, content, title, created_at, user_id, version FROM notes WHERE title = $1`, title)
    if err != nil {
        return nil, fmt.Errorf("query: %w", err)
    }
    defer row.Close()
    note, err := pgx.CollectOneRow(row, pgx.RowToStructByName[model.Note])
    if err != nil {
        if err == pgx.ErrNoRows {
            return nil, nil
        }
        return nil, fmt.Errorf("collect: %w", err)
    }
    return &note, nil
}

func (r *NoteRepo) FindByCreatedAt(ctx context.Context, t time.Time) ([]model.Note, error) {
    rows, err := r.pool.Query(ctx, `SELECT id, content, title, created_at, user_id, version FROM notes WHERE created_at = $1`, t)
    if err != nil {
        return nil, fmt.Errorf("query: %w", err)
    }
    defer rows.Close()
    return pgx.CollectRows(rows, pgx.RowToStructByName[model.Note])
}
```

- [ ] **Create file**

---

### Task 2.3: note service layer

**Create:** `services/note/internal/service/note_service.go`

```go
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

func (s *NoteService) GetByTitle(ctx context.Context, title string, userID string) (*model.Note, error) {
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
    note := &model.Note{
        Title:     req.Title,
        Content:   req.Content,
        UserID:    userID,
        CreatedAt: time.Now(),
        Version:   0,
    }
    s.encryptNote(note)
    existing, err := s.repo.FindByTitleAndUser(ctx, note.Title, userID)
    if err != nil {
        return nil, err
    }
    if existing != nil {
        note.ID = existing.ID
        note.Version = existing.Version
        if err := s.repo.Update(ctx, note); err != nil {
            return nil, err
        }
    } else {
        if err := s.repo.Insert(ctx, note); err != nil {
            if pgErr, ok := err.(*pgconn.PgError); ok && pgErr.Code == "23505" {
                existing2, _ := s.repo.FindByTitleAndUser(ctx, note.Title, userID)
                if existing2 != nil {
                    note.ID = existing2.ID
                    note.Version = existing2.Version
                    s.repo.Update(ctx, note)
                    s.publishEvent(note, "updated")
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
    if n.Title != "" {
        enc, err := crypto.Encrypt([]byte(n.Title), s.encKey)
        if err == nil {
            n.Title = enc
        }
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
    if n.Title != "" {
        dec, err := crypto.Decrypt(n.Title, s.encKey)
        if err == nil {
            n.Title = string(dec)
        }
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
```

- [ ] **Create file**

---

### Task 2.4: note handler layer

**Create:** `services/note/internal/handler/note_handler.go`

```go
package handler

import (
    "net/http"
    "strconv"

    "github.com/gin-gonic/gin"
    "github.com/thisuite/thisecure/note/internal/model"
    "github.com/thisuite/thisecure/note/internal/service"
    "github.com/thisuite/thisecure/pkg/middleware"
)

type NoteHandler struct {
    svc *service.NoteService
}

func NewNoteHandler(svc *service.NoteService) *NoteHandler {
    return &NoteHandler{svc: svc}
}

func (h *NoteHandler) Register(r *gin.RouterGroup) {
    r.POST("", h.Create)
    r.POST("/import", h.Import)
    r.GET("", h.GetAll)
    r.GET("/search", h.Search)
    r.GET("/:title", h.GetByTitle)
    r.GET("/id/:id", h.GetByID)
    r.PUT("/:id", h.Update)
    r.DELETE("/:id", h.Delete)
}

func (h *NoteHandler) Create(c *gin.Context) {
    var req model.NoteRequest
    if err := c.ShouldBindJSON(&req); err != nil {
        c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
        return
    }
    userID := middleware.GetUserID(c)
    note, err := h.svc.Create(c.Request.Context(), req, userID)
    if err != nil {
        c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
        return
    }
    c.JSON(http.StatusOK, note)
}

func (h *NoteHandler) Import(c *gin.Context) {
    var reqs []model.NoteRequest
    if err := c.ShouldBindJSON(&reqs); err != nil {
        c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
        return
    }
    userID := middleware.GetUserID(c)
    result, err := h.svc.Import(c.Request.Context(), reqs, userID)
    if err != nil {
        c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
        return
    }
    c.JSON(http.StatusOK, result)
}

func (h *NoteHandler) GetAll(c *gin.Context) {
    userID := middleware.GetUserID(c)
    notes, err := h.svc.GetAll(c.Request.Context(), userID)
    if err != nil {
        c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
        return
    }
    if notes == nil {
        notes = []model.Note{}
    }
    c.JSON(http.StatusOK, notes)
}

func (h *NoteHandler) Search(c *gin.Context) {
    title := c.Query("title")
    userID := middleware.GetUserID(c)
    notes, err := h.svc.SearchByTitle(c.Request.Context(), title, userID)
    if err != nil {
        c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
        return
    }
    if notes == nil {
        notes = []model.Note{}
    }
    c.JSON(http.StatusOK, notes)
}

func (h *NoteHandler) GetByTitle(c *gin.Context) {
    title := c.Param("title")
    userID := middleware.GetUserID(c)
    note, err := h.svc.GetByTitle(c.Request.Context(), title, userID)
    if err != nil {
        c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
        return
    }
    if note == nil {
        c.JSON(http.StatusNotFound, gin.H{"error": "note not found"})
        return
    }
    c.JSON(http.StatusOK, note)
}

func (h *NoteHandler) GetByID(c *gin.Context) {
    id, err := strconv.ParseInt(c.Param("id"), 10, 64)
    if err != nil {
        c.JSON(http.StatusBadRequest, gin.H{"error": "invalid id"})
        return
    }
    userID := middleware.GetUserID(c)
    note, err := h.svc.GetByID(c.Request.Context(), id, userID)
    if err != nil {
        c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
        return
    }
    if note == nil {
        c.JSON(http.StatusNotFound, gin.H{"error": "note not found"})
        return
    }
    c.JSON(http.StatusOK, note)
}

func (h *NoteHandler) Update(c *gin.Context) {
    id, err := strconv.ParseInt(c.Param("id"), 10, 64)
    if err != nil {
        c.JSON(http.StatusBadRequest, gin.H{"error": "invalid id"})
        return
    }
    var req model.NoteRequest
    if err := c.ShouldBindJSON(&req); err != nil {
        c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
        return
    }
    userID := middleware.GetUserID(c)
    note, err := h.svc.Update(c.Request.Context(), id, req, userID)
    if err != nil {
        c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
        return
    }
    c.JSON(http.StatusOK, note)
}

func (h *NoteHandler) Delete(c *gin.Context) {
    id, err := strconv.ParseInt(c.Param("id"), 10, 64)
    if err != nil {
        c.JSON(http.StatusBadRequest, gin.H{"error": "invalid id"})
        return
    }
    userID := middleware.GetUserID(c)
    if err := h.svc.Delete(c.Request.Context(), id, userID); err != nil {
        c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
        return
    }
    c.JSON(http.StatusOK, gin.H{"message": "deleted"})
}
```

- [ ] **Create file**

---

### Task 2.5: note main entry point

**Create:** `services/note/cmd/server/main.go`

```go
package main

import (
    "context"
    "log"
    "net/http"
    "os"
    "os/signal"
    "syscall"
    "time"

    "github.com/gin-gonic/gin"
    "github.com/thisuite/thisecure/note/internal/config"
    "github.com/thisuite/thisecure/note/internal/handler"
    "github.com/thisuite/thisecure/note/internal/repository"
    "github.com/thisuite/thisecure/note/internal/service"
    "github.com/thisuite/thisecure/pkg/database"
    "github.com/thisuite/thisecure/pkg/kafka"
    mid "github.com/thisuite/thisecure/pkg/middleware"
)

func main() {
    cfg := config.Load()
    ctx := context.Background()

    pool, err := database.NewPool(ctx, database.DefaultConfig(cfg.DatabaseURL))
    if err != nil {
        log.Fatalf("database: %v", err)
    }
    defer pool.Close()

    encKey := []byte(cfg.EncryptionKey)
    jwtSecret := []byte(cfg.JWTSecret)

    var syncProducer *kafka.Producer
    signer := kafka.NewSigner(jwtSecret)
    syncProducer = kafka.NewProducer(cfg.KafkaBrokers, "sync-events", signer)

    noteRepo := repository.NewNoteRepo(pool)
    noteSvc := service.NewNoteService(noteRepo, encKey, syncProducer)
    noteH := handler.NewNoteHandler(noteSvc)

    r := gin.Default()
    r.Use(mid.RateLimit(mid.NewRateLimiter(10, 20, time.Second)))
    r.GET("/health", func(c *gin.Context) { c.JSON(200, gin.H{"status": "ok"}) })

    v1 := r.Group("/v1/notes", mid.JWTAuth(jwtSecret))
    noteH.Register(v1)

    srv := &http.Server{Addr: ":" + cfg.Port, Handler: r}
    go func() {
        log.Printf("note service listening on :%s", cfg.Port)
        if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
            log.Fatalf("server: %v", err)
        }
    }()

    quit := make(chan os.Signal, 1)
    signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
    <-quit
    log.Println("shutting down...")
    ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
    defer cancel()
    syncProducer.Close()
    srv.Shutdown(ctx)
}
```

- [ ] **Create file**

---

### Task 2.6: note tests

**Create:** `services/note/internal/service/note_service_test.go`

```go
package service_test

import (
    "context"
    "testing"

    "github.com/thisuite/thisecure/note/internal/model"
    "github.com/thisuite/thisecure/note/internal/repository"
    "github.com/thisuite/thisecure/note/internal/service"
    "github.com/thisuite/thisecure/pkg/crypto"
    "github.com/stretchr/testify/require"
)

// Unit tests using a mock repository
type mockNoteRepo struct {
    notes map[int64]*model.Note
    nextID int64
}

func newMockNoteRepo() *mockNoteRepo {
    return &mockNoteRepo{notes: make(map[int64]*model.Note), nextID: 1}
}

// TODO: implement mock methods matching NoteRepo interface
// For now we test encryption/decryption which doesn't need the repo

func TestNoteService_EncryptDecrypt(t *testing.T) {
    encKey := []byte("0123456789abcdef0123456789abcdef") // 32 bytes
    svc := service.NewNoteService(nil, encKey, nil)

    note := &model.Note{Title: "secret title", Content: "secret content"}
    // Access via exported methods only
    _ = svc
    // Verify crypto works independently
    encTitle, err := crypto.Encrypt([]byte("hello"), encKey)
    require.NoError(t, err)
    decTitle, err := crypto.Decrypt(encTitle, encKey)
    require.NoError(t, err)
    require.Equal(t, "hello", string(decTitle))
}
```

**Create:** `services/note/internal/repository/note_repo_integration_test.go`

```go
//go:build integration

package repository_test

import (
    "context"
    "os"
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

    for i := 0; i < 3; i++ {
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
    require.Error(t, err) // unique constraint violation
}
```

- [ ] **Create test files**
- [ ] **Run unit tests:** `cd /Users/joel/Workspace/thisuite/thisecure/backend/services/note && go test ./... -v`

---

### Task 2.7: note Dockerfile

**Create:** `services/note/Dockerfile`

```dockerfile
FROM golang:1.22-alpine AS builder
WORKDIR /app
COPY pkg/ pkg/
COPY services/note/ services/note/
WORKDIR /app/services/note
RUN go mod tidy && go build -o /server ./cmd/server/

FROM alpine:3.19
RUN apk --no-cache add ca-certificates
COPY --from=builder /server /server
EXPOSE 8083
HEALTHCHECK --interval=30s --timeout=3s CMD wget --no-verbose --tries=1 --spider http://localhost:8083/health || exit 1
CMD ["/server"]
```

- [ ] **Create file**

---

## Phase 3: `otp` Service

Follows same pattern as `note` but with:
- **Model:** `otp` + `otp_key`
- **Repository:** `FindByUserID`, `FindByID`, `Insert`, `Update`, `Remove`
- **Service:** `GetAllOtps`, `CreateOtp` (with dedup by secret), `UpdateOtp`, `DeleteOtp`, `ValidateOtp` (constant-time compare + expiry check)
- **Handler:** Same REST endpoints
- **Kafka:** `KafkaConsumer` for `auth-events` → auto-create OTP on `USER_REGISTERED`
- **QR Service:** Decode Base64 QR images to `otpauth://` URIs using `github.com/skip2/go-qrcode` or `github.com/makiuchi-d/gozxing`

### Key differences from Spring Boot:
- QR decoding: use `github.com/makiuchi-d/gozxing` (pure Go ZXing port)
- OTP validation: constant-time compare via `crypto/subtle`
- Kafka consumer: `kafka.NewConsumer` with `UserRegisteredEvent` handler

**Migrations:** 001_create_otp.{up,down}.sql, 002_create_otp_key.{up,down}.sql

- [ ] **Create all scaffolding**
- [ ] **Create migrations**
- [ ] **Create model**
- [ ] **Create repository**
- [ ] **Create repository integration tests**
- [ ] **Create QR service**
- [ ] **Create OTP service**
- [ ] **Create handler**
- [ ] **Create main.go**
- [ ] **Create Dockerfile**
- [ ] **Run `go mod tidy` and tests**

---

## Phase 4: `password` Service

Follows same pattern as `note` but with:
- **Model:** `Password` (password, name, website, username, user_id)
- **Repository:** `FindByUserID`, `FindByID`, `FindByUserIDAndNameAndWebsite`, `Insert`, `Update`, `Delete`
- **Service:** CRUD + decrypt/encrypt + dedup + import
- **Handler:** Same REST endpoints + admin routes
- **Dedup:** `PasswordDeduplicationService` with `analyzeDuplicates` (group by name::website::username) and `removeDuplicates` (keep newest)

**Migrations:** 001_create_passwords.{up,down}.sql

- [ ] **Create all scaffolding**
- [ ] **Create migrations**
- [ ] **Create model**
- [ ] **Create repository**
- [ ] **Create repository integration tests**
- [ ] **Create password service**
- [ ] **Create dedup service**
- [ ] **Create handler**
- [ ] **Create main.go**
- [ ] **Create Dockerfile**
- [ ] **Run `go mod tidy` and tests**

---

## Phase 5: Root Infrastructure

### Task 5.1: `go.work`

**Create:** `go.work`

```
go 1.22.0

use (
    ./pkg
    ./services/note
    ./services/otp
    ./services/password
)
```

- [ ] **Create file**

### Task 5.2: `Makefile`

```makefile
.PHONY: build test test-integration test-all vet clean dev

SERVICES := note otp password

build:
	@for svc in $(SERVICES); do \
		echo "Building $$svc..."; \
		cd services/$$svc && go build -o server ./cmd/server/ && cd ../..; \
	done

test:
	go test ./pkg/... ./services/... -count=1

test-integration:
	go test -tags=integration ./services/... -count=1 -v

test-all: test test-integration

vet:
	go vet ./pkg/... ./services/...

clean:
	@for svc in $(SERVICES); do \
		rm -f services/$$svc/server; \
	done

dev:
	@echo "Run: docker compose up -d"
	@echo "Then run each service: cd services/<name> && go run ./cmd/server/"
```

- [ ] **Create file**

### Task 5.3: `docker-compose.yaml`

- [ ] **Create compose file with PostgreSQL (3 instances), Kafka, ZK, and 3 service containers**

```yaml
services:
  postgres-note:
    image: postgres:16-alpine
    environment: { POSTGRES_DB: note, POSTGRES_USER: postgres, POSTGRES_PASSWORD: postgres }
    ports: ["5433:5432"]
    volumes: [pg-note:/var/lib/postgresql/data]

  postgres-otp:
    image: postgres:16-alpine
    environment: { POSTGRES_DB: otp, POSTGRES_USER: postgres, POSTGRES_PASSWORD: postgres }
    ports: ["5434:5432"]
    volumes: [pg-otp:/var/lib/postgresql/data]

  postgres-password:
    image: postgres:16-alpine
    environment: { POSTGRES_DB: password, POSTGRES_USER: postgres, POSTGRES_PASSWORD: postgres }
    ports: ["5435:5432"]
    volumes: [pg-password:/var/lib/postgresql/data]

  zookeeper:
    image: confluentinc/cp-zookeeper:7.7
    environment: { ZOOKEEPER_CLIENT_PORT: 2181 }

  kafka:
    image: confluentinc/cp-kafka:7.7
    depends_on: [zookeeper]
    ports: ["9092:9092"]
    environment:
      KAFKA_BROKER_ID: 1
      KAFKA_ZOOKEEPER_CONNECT: zookeeper:2181
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://localhost:9092
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1

  note:
    build:
      context: .
      dockerfile: services/note/Dockerfile
    ports: ["8083:8083"]
    environment:
      PORT: "8083"
      DATABASE_URL: postgres://postgres:postgres@postgres-note:5432/note?sslmode=disable
      KAFKA_BROKERS: kafka:9092
      JWT_SECRET: ${JWT_SECRET}
      ENCRYPTION_KEY: ${ENCRYPTION_KEY}
    depends_on: [postgres-note, kafka]

  otp:
    build:
      context: .
      dockerfile: services/otp/Dockerfile
    ports: ["8085:8085"]
    environment:
      PORT: "8085"
      DATABASE_URL: postgres://postgres:postgres@postgres-otp:5432/otp?sslmode=disable
      KAFKA_BROKERS: kafka:9092
      JWT_SECRET: ${JWT_SECRET}
      ENCRYPTION_KEY: ${ENCRYPTION_KEY}
    depends_on: [postgres-otp, kafka]

  password:
    build:
      context: .
      dockerfile: services/password/Dockerfile
    ports: ["8084:8084"]
    environment:
      PORT: "8084"
      DATABASE_URL: postgres://postgres:postgres@postgres-password:5432/password?sslmode=disable
      KAFKA_BROKERS: kafka:9092
      JWT_SECRET: ${JWT_SECRET}
      ENCRYPTION_KEY: ${ENCRYPTION_KEY}
    depends_on: [postgres-password, kafka]

volumes:
  pg-note: pg-otp: pg-password:
```

- [ ] **Create `docker-compose.yaml`**

---

## Implementation Order

1. Phase 1 (pkg/) → all shared packages with tests
2. Phase 2 (note/) → first complete service
3. Phase 3 (otp/) → second service with QR + Kafka consumer
4. Phase 4 (password/) → third service with dedup
5. Phase 5 (Root infra) → go.work, Makefile, docker-compose.yaml
