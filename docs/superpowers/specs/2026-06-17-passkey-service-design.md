# Passkey Service Design

## Overview

New Go microservice `passkey` for storing and managing WebAuthn/FIDO2 passkey credentials alongside the existing password manager ecosystem.

## Architecture

Standard pattern used by all services (note, otp, password):

```
services/passkey/
├── cmd/server/main.go              # Entry point, port 8086
├── internal/
│   ├── config/config.go            # Env-based configuration
│   ├── handler/passkey_handler.go  # Gin HTTP handlers
│   ├── model/passkey.go            # Domain models + request/response types
│   ├── repository/passkey_repo.go  # pgx-based PostgreSQL repository
│   └── service/passkey_service.go  # Business logic + AES-GCM encrypt + Kafka events
├── migrations/
│   ├── 001_create_passkey.up.sql
│   └── 001_create_passkey.down.sql
├── Dockerfile
├── go.mod                          # Module: github.com/thisuite/thisecure/passkey
└── go.sum
```

Shared library: `github.com/thisuite/thisecure/pkg` (go.workspace ref).

## Data Model

### Table: `passkey`

| Column | Type | Encrypted | Notes |
|--------|------|-----------|-------|
| id | BIGSERIAL PK | No | Internal auto-increment |
| credential_id | TEXT NOT NULL | No | base64url, unique per user |
| public_key | TEXT | **Yes (AES-256-GCM)** | COSE-encoded public key |
| rp_id | TEXT | No | Relying Party ID (e.g., "google.com") |
| rp_name | TEXT | No | Relying Party display name |
| user_handle | TEXT | No | base64url user handle |
| user_display_name | TEXT | No | Display name |
| sign_count | BIGINT DEFAULT 0 | No | Signature counter |
| name | TEXT | No | User-friendly label |
| transports | TEXT[] | No | e.g., {"internal","hybrid","usb"} |
| credential_type | TEXT DEFAULT 'public-key' | No | |
| backup_eligible | BOOLEAN DEFAULT FALSE | No | |
| backup_state | BOOLEAN DEFAULT FALSE | No | |
| user_id | TEXT NOT NULL | No | JWT "sub" claim |
| created_at | TIMESTAMPTZ DEFAULT NOW() | No | |
| updated_at | TIMESTAMPTZ DEFAULT NOW() | No | |

### Indexes

- `idx_passkey_user_id` on `user_id`
- Unique index `idx_passkey_user_credential` on `(user_id, credential_id)`

### Encryption

Only `public_key` is encrypted with AES-256-GCM (same `pkg/crypto` as other services). Public metadata is stored in plain text for searchability and indexing.

## API Endpoints

All under `/v1/passkeys`, protected by `JWTAuth` middleware:

| Method | Path | Handler | Description |
|--------|------|---------|-------------|
| GET | `/` | GetAll | List all passkeys for authenticated user |
| GET | `/:id` | GetByID | Get single passkey (ownership check) |
| POST | `/` | Create | Create new passkey entry |
| PUT | `/:id` | Update | Update passkey (ownership check) |
| DELETE | `/:id` | Delete | Delete passkey (ownership check) |

### Request/Response Model

```json
{
  "id": 1,
  "credentialId": "Aag0E6s...",
  "publicKey": "pQECAyYg...",
  "rpId": "google.com",
  "rpName": "Google",
  "userHandle": "MpCQ9E...",
  "userDisplayName": "Joel",
  "signCount": 5,
  "name": "Mi Passkey de Google",
  "transports": ["internal", "hybrid"],
  "credentialType": "public-key",
  "backupEligible": true,
  "backupState": false,
  "createdAt": "2026-06-17T12:00:00Z",
  "updatedAt": "2026-06-17T12:00:00Z"
}
```

Create request omits `id`, `createdAt`, `updatedAt`.

## Kafka Events

Same pattern as password service — `SyncEvent` with `serviceName: "passkey"` published on every CRUD operation:

- `action: "created"` on Create
- `action: "updated"` on Update
- `action: "deleted"` on Delete

## Infrastructure Changes

- **docker-compose.yaml**: Add `postgres-passkey` (port 5436) + `passkey` service (port 8086)
- **go.work**: Add `./services/passkey`
- **Makefile**: Add `build-passkey`, `test-passkey`, `run-passkey` targets
- **CI/CD** (`.github/workflows/main.yaml`): Path-based detection for `services/passkey/*`
- **`.version`**: `1.0.0`
