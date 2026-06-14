# AGENTS.md

## Architecture

- **3 independent Go services** in `services/`: `note`, `otp`, `password` (Go 1.25)
- **Shared library** in `pkg/` (`crypto`, `database`, `jwt`, `kafka`, `middleware`, `models`)
- **Go workspace** (`go.work`) ties root `pkg/` with all services
- Each service has its own `go.mod`, `cmd/server/`, `internal/`, `Dockerfile`, `migrations/`
- **No messages service** — removed

## Commands

All commands run from repo root (use Go workspace):

```
go build ./...              # build all
go test ./...               # test all
go vet ./...                # vet all
```

Or use `Makefile`:

```
make build                  # build all services
make test                   # test all
make vet                    # vet all
make dev                    # show dev run commands
```

Per service:

```
go run ./services/note/cmd/server/
go run ./services/otp/cmd/server/
go run ./services/password/cmd/server/
```

## CI/CD

- CI runs on **every push and PR** (`.github/workflows/main.yaml`)
- **Feature branches**: security scans only (Hadolint, Trivy, Gitleaks, CodeQL) + compile
- **`master` / `develop`**: also build + push multi-arch Docker images (`linux/amd64,linux/arm64`) to Docker Hub as `thisjowi/<service>:*`
- Service detection is **path-based** (`services/*`) — only changed services are built. Manual dispatch builds all 3.
- Security scans use `continue-on-error: true` — they produce reports/issues but don't block the pipeline.

## Conventions

- **Go module**: `github.com/thisuite/thisecure/<service>` (e.g. `github.com/thisuite/thisecure/note`)
- **Commits**: Conventional Commits (`feat:`, `fix:`, `docs:`, `refactor:`, `test:`, `chore:`, `perf:`)
- **Branch names**: `feature/`, `fix/`, `docs/`, `refactor/`, `test/`, `chore/` prefixes

## Infrastructure

- **Database**: CockroachDB (PostgreSQL-compatible) for all services
- **Messaging**: Kafka for inter-service events
- **Migrations**: Each service has its own `migrations/` directory

## Security

- All pushes trigger Gitleaks secrets scanning (config at repo root: `.gitleaks.toml`)
- CI security scans are non-blocking (`continue-on-error: true`); failures create GitHub issues with `security` and `registry` labels
