# Spring Boot → Go Migration Design

**Date:** 2026-06-14
**Status:** Draft
**Designer:** Architecture Agent

## Overview

Migrate 3 Spring Boot microservices (`note`, `otp`, `password`) from `backup/` to independent Go microservices in a monorepo workspace, replicating the architecture pattern established in `~/Workspace/thisuite/core/`.

## Source Services

| Service | Path | Port | DB | Tables |
|---------|------|------|----|--------|
| note | `backup/notes/` | 8083 | PostgreSQL | `notes` |
| otp | `backup/otp/` | 8085 | PostgreSQL | `otp`, `otp_key` |
| password | `backup/password/` | 8084 | PostgreSQL | `password` |

## Architecture

### Monorepo Structure

```
backend/
├── go.work                          # workspace: pkg + 3 services
├── Makefile                         # build, test, lint, clean
├── docker-compose.yaml              # 3x PostgreSQL + Kafka + ZK + 3 services
├── pkg/                             # shared module (github.com/thisuite/thisecure/pkg)
│   ├── go.mod
│   ├── crypto/crypto.go             # AES-256-GCM encrypt/decrypt
│   ├── jwt/jwt.go                   # JWT validation (HS256)
│   ├── kafka/
│   │   ├── producer.go              # HMAC-signed Kafka producer
│   │   ├── consumer.go              # HMAC-verified Kafka consumer
│   │   └── hmac.go                  # HMAC-SHA256 sign/verify
│   ├── middleware/auth.go           # Gin JWT auth middleware
│   ├── database/postgres.go         # pgx connection factory
│   ├── models/events.go             # Shared event/command structs
│   └── middleware/ratelimit.go       # Per-IP token bucket
│
└── services/
    ├── note/                        # Port 8083
    │   ├── go.mod                   # module github.com/thisuite/thisecure/note
    │   ├── cmd/server/main.go
    │   ├── internal/
    │   │   ├── config/config.go
    │   │   ├── handler/
    │   │   │   └── note_handler.go
    │   │   ├── model/
    │   │   │   └── note.go
    │   │   ├── repository/
    │   │   │   ├── note_repo.go
    │   │   │   └── note_repo_integration_test.go
    │   │   └── service/
    │   │       └── note_service.go
    │   ├── migrations/
    │   │   ├── 001_create_notes.up.sql
    │   │   └── 001_create_notes.down.sql
    │   └── Dockerfile
    │
    ├── otp/                         # Port 8085
    │   ├── go.mod                   # module github.com/thisuite/thisecure/otp
    │   ├── cmd/server/main.go
    │   ├── internal/
    │   │   ├── config/config.go
    │   │   ├── handler/
    │   │   │   └── otp_handler.go
    │   │   ├── model/
    │   │   │   ├── otp.go
    │   │   │   └── otp_key.go
    │   │   ├── repository/
    │   │   │   ├── otp_repo.go
    │   │   │   └── otp_repo_integration_test.go
    │   │   └── service/
    │   │       ├── otp_service.go
    │   │       └── qr_service.go
    │   ├── migrations/
    │   │   ├── 001_create_otp.up.sql
    │   │   ├── 001_create_otp.down.sql
    │   │   ├── 002_create_otp_key.up.sql
    │   │   └── 002_create_otp_key.down.sql
    │   └── Dockerfile
    │
    └── password/                    # Port 8084
        ├── go.mod                   # module github.com/thisuite/thisecure/password
        ├── cmd/server/main.go
        ├── internal/
        │   ├── config/config.go
        │   ├── handler/
        │   │   └── password_handler.go
        │   ├── model/
        │   │   └── password.go
        │   ├── repository/
        │   │   ├── password_repo.go
        │   │   └── password_repo_integration_test.go
        │   └── service/
        │       ├── password_service.go
        │       └── dedup_service.go
        ├── migrations/
        │   ├── 001_create_passwords.up.sql
        │   └── 001_create_passwords.down.sql
        └── Dockerfile
```

### Service Independence

- **No direct imports** between services
- **No HTTP calls** between services
- Communication only via **Kafka async events** (HMAC-SHA256 signed)
- Each service has its own `go.mod`, `Dockerfile`, database, migrations
- `pkg/` is shared via `replace` directive in each service's `go.mod`

### Data Flow

```
Client → Gin HTTP → Middleware (JWT auth) → Handler → Service → Repository (pgx) → PostgreSQL
                                                              ↓
                                                        Kafka Producer (HMAC signed)
                                                              ↓
                                                        Kafka Topic
                                                              ↓
                                                        Kafka Consumer (HMAC verified)
```

## Database Schemas

### notes table
```sql
CREATE TABLE notes (
    id BIGSERIAL PRIMARY KEY,
    content TEXT,
    title TEXT NOT NULL,
    created_at TIMESTAMP DEFAULT NOW(),
    user_id TEXT NOT NULL,
    version BIGINT DEFAULT 0 NOT NULL,
    CONSTRAINT uk_title_user UNIQUE (title, user_id)
);
```

### otp tables
```sql
CREATE TABLE otp (
    id BIGSERIAL PRIMARY KEY,
    user_id VARCHAR(255),
    email TEXT NOT NULL,
    secret TEXT NOT NULL,
    expires_at TEXT NOT NULL,
    type TEXT NOT NULL,
    issuer TEXT,
    digits TEXT,
    period INTEGER,
    algorithm TEXT,
    valid TEXT NOT NULL
);

CREATE TABLE otp_key (
    id BIGSERIAL PRIMARY KEY,
    user_id VARCHAR(255),
    otp VARCHAR(255),
    created_at TIMESTAMP
);
```

### password table
```sql
CREATE TABLE password (
    id BIGSERIAL PRIMARY KEY,
    password TEXT,
    name TEXT,
    website TEXT,
    username TEXT,
    user_id TEXT
);
```

## API Contracts (exact match with Spring Boot)

### note: `/v1/notes`
| Method | Path | Auth | Notes |
|--------|------|------|-------|
| POST | `/v1/notes` | Bearer | Create, dedup by title+userId |
| POST | `/v1/notes/import` | Bearer | Batch import with dedup stats |
| GET | `/v1/notes` | Bearer | All notes for user (decrypted) |
| GET | `/v1/notes/search?title=` | Bearer | Search by title |
| GET | `/v1/notes/{title}` | Bearer | Get by exact title |
| GET | `/v1/notes/id/{id}` | Bearer | Get by id |
| PUT | `/v1/notes/{id}` | Bearer | Update (ownership check) |
| DELETE | `/v1/notes/{id}` | Bearer | Delete (ownership check) |

### otp: `/v1/otp`
| Method | Path | Auth | Notes |
|--------|------|------|-------|
| POST | `/v1/otp/decode-qr` | Public | Decode Base64 QR → otpauth URI |
| GET | `/v1/otp` | Bearer | All OTPs for user |
| GET | `/v1/otp/{id}` | Bearer | Get by id (ownership) |
| POST | `/v1/otp` | Bearer | Create, dedup by secret |
| PUT | `/v1/otp/{id}` | Bearer | Update (ownership) |
| DELETE | `/v1/otp/{id}` | Bearer | Delete (ownership) |
| POST | `/v1/otp/{id}/validate?code=` | Public | Validate TOTP code |

### password: `/v1/passwords`
| Method | Path | Auth | Notes |
|--------|------|------|-------|
| GET | `/v1/passwords` | Bearer | All passwords (decrypted) |
| GET | `/v1/passwords/{id}` | Bearer | Get by id (ownership) |
| POST | `/v1/passwords` | Bearer | Create, dedup by name+website+userId |
| PUT | `/v1/passwords/{id}` | Bearer | Update (ownership) |
| DELETE | `/v1/passwords/{id}` | Bearer | Delete (ownership) |
| POST | `/v1/passwords/import` | Bearer | Batch import with dedup stats |
| POST | `/v1/passwords/admin/analyze-duplicates` | Bearer | Analyze duplicates |
| POST | `/v1/passwords/admin/remove-duplicates` | Bearer | Remove duplicates |

## Encryption

- Algorithm: AES-256-GCM
- Single unified key from `ENCRYPTION_KEY` env var
- All 3 services use the same `pkg/crypto` package
- Encrypted fields in each service:
  - **note**: `title`, `content`
  - **otp**: `secret`
  - **password**: `password`, `name`, `website`, `username`

## Kafka Events

### Topics
| Topic | Producers | Consumers |
|-------|-----------|-----------|
| `auth-events` | (external auth service) | **otp** (auto-create OTP on USER_REGISTERED), note (log), password (log) |
| `otp-events` | **otp** (OTP_CREATED) | **otp** (own events) |
| `sync-events` | **note**, **otp**, **password** | (sync-hub, future) |

### Event Schemas (shared in `pkg/models/events.go`)

- `SyncEvent` — eventId, userId, serviceName, action (created/updated/deleted), payload, timestamp
- `UserRegisteredEvent` — userId, email, eventType, timestamp
- `OtpCreatedEvent` — otpId, userId, email, type, eventType, timestamp, expiresAt

## Testing Strategy

### Unit Tests (no external deps)
- Repository mocks (or test with in-memory)
- Service + handler logic
- Crypto round-trip
- JWT validation
- Kafka HMAC sign/verify

### Integration Tests (`-tags=integration`)
- Testcontainers for real PostgreSQL
- One test suite per service repository
- Full CRUD verification
- Constraint/duplicate validation

### Test targets in Makefile
```makefile
test:               # go test ./... (unit only)
test-integration:   # go test -tags=integration ./... (requires Docker)
test-all:           # test + test-integration
```

## Migration Order (service by service)

1. **pkg/** shared module (crypto, JWT, Kafka, middleware, database, models)
2. **note/** — simplest service, establishes patterns
3. **otp/** — adds QR decoding + Kafka consumer logic + OTP validation
4. **password/** — adds deduplication analysis + import features
5. Root infra: Makefile, docker-compose.yaml, go.work, CI

## Commit Convention

Per service, atomic commits following conventional commits:
```
feat(pkg): add crypto, jwt, kafka shared packages
feat(note): add HTTP handlers with Gin
feat(note): add pgx repository layer
feat(note): add kafka sync event publishing
test(note): add repository integration tests
```
