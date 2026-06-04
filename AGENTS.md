# AGENTS.md

## Architecture

- **4 independent services** in the repo root: `note`, `otp`, `password` (Spring Boot 3.5.9 / Java 21 / Gradle), `messages` (NestJS 10 / TypeScript / Cassandra)
- Each Java service is a standalone Gradle project with its own `gradlew`, `settings.gradle.kts`, `build.gradle.kts`. There is no root-level build tool or wrapper.
- README mentions `auth`, `config` services and `infrastructure/` directory — **none of these exist yet**. CONTRIBUTING.md references `./mvnw` — ignore; services use Gradle exclusively.

## Commands

### Java services (note, otp, password)
```
./gradlew bootRun          # run locally
./gradlew test             # run tests
./gradlew compileJava      # compile only (CI does this)
./gradlew clean build      # full build
```
Run from inside the service directory (each has its own `gradlew`).

### Messages (NestJS)
```
npm ci                     # clean install
npm run build              # compile TypeScript
npm test                   # unit tests (Jest)
npm run test:e2e           # e2e tests
```

## CI/CD

- CI runs on **every push and PR** (`.github/workflows/main.yaml`)
- **Feature branches**: security scans only (Hadolint, Trivy Gitleaks, CodeQL) + compile/CodeQL analyze
- **`master` / `develop`**: also build + push multi-arch Docker images (`linux/amd64,linux/arm64`) to Docker Hub as `thisjowi/<service>:*`
- Service detection is **path-based** — only changed services are built. Manual dispatch builds all 4.
- Security scans use `continue-on-error: true` — they produce reports/issues but don't block the pipeline.

## Conventions

- **Java package**: `com.thisjowi.<service>` (e.g. `com.thisjowi.note`)
- **Commits**: Conventional Commits (`feat:`, `fix:`, `docs:`, `refactor:`, `test:`, `chore:`, `perf:`)
- **Branch names**: `feature/`, `fix/`, `docs/`, `refactor/`, `test/`, `chore/` prefixes
- **Casing inconsistency**: `password` uses PascalCase package dirs (`Config/`, `Controller/`, `Service/`); `note` and `otp` use lowercase (`config/`, `controller/`, `service/`). Follow the convention per service.

## Infrastructure

- Each Java service connects to `http://config.core:8888` via Spring Cloud Config (`bootstrap.yaml`)
- **Database**: PostgreSQL (CockroachDB) for Java services, Cassandra for `messages`
- **Messaging**: Kafka for inter-service events (Spring Cloud Bus + Kafka)
- **Migrations**: Liquibase for Java services
- `messages` has `compose.yaml`, `password` and `otp` have `compose.yaml`; `note` does **not** have a compose file.

## Security

- All pushes trigger Gitleaks secrets scanning (config at repo root: `.gitleaks.toml`)
- `SECURITY_AUDIT.md` documents 34 known vulnerabilities (13 CRITICAL, 12 HIGH, 9 MEDIUM) as of 2026-06-03 — focused on `otp`, `password`, `note` services
- CI security scans are non-blocking (`continue-on-error: true`); failures create GitHub issues with `security` and `registry` labels
