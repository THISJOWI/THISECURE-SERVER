# AI-SPEC: Spring Data JPA/Hibernate → JdbcTemplate Migration Evaluation Strategy

> **System Type:** Code / Hybrid (Structured Data Migration)
> **Framework:** Spring Boot 3.5.9 + JdbcTemplate (replacing Spring Data JPA/Hibernate)
> **Model Provider:** N/A (non-AI code migration)
> **Phase:** Evaluation Planning
> **Phase Goal:** Define measurable criteria to verify migration correctness, performance, and safety across all 3 microservices

---

## 1. Critical Failure Modes

This section catalogs the 6 behaviors that **cannot go wrong** during or after the migration. Each is derived from concrete analysis of the current codebase.

### 1.1 Data Consistency Loss — Encrypted Fields

**Current state:** Three different encryption implementations exist across services, with different algorithms and key derivation:

| Service | Algorithm | IV Size | Key Derivation | Mode | Risk |
|---------|-----------|---------|----------------|------|------|
| password | AES-256-GCM (NoPadding) | 12 bytes (96-bit) | First 32 bytes of `jwt.secret` | Manual in Service layer | **Medium** — GCM is authenticated, safest |
| note | AES-256-CBC (PKCS5Padding) | 16 bytes (128-bit) | SHA-256 hash of `jwt.secret` | Manual in Service layer | **Medium** — CBC ok, static singleton pattern fragile |
| otp | AES-128-ECB (PKCS5Padding) | None (ECB mode!) | SHA-1 of `app.encryption.key` | JPA @Convert converters | **CRITICAL** — ECB is insecure, SHA-1 is weak, transparent via @Convert |

**Critical risk:** OTP service's `@Convert` converters (`StringCryptoConverter`, `IntegerCryptoConverter`, `LongCryptoConverter`, `BooleanCryptoConverter`) transparently encrypt on write and decrypt on read. After removing JPA, these conversions **must be manually re-applied** at every JdbcTemplate read/write boundary. **One missed encrypt/decrypt call produces corrupted data** — either plaintext stored (violating encryption contract) or encrypted data returned to API consumers (garbage output).

**Concrete failure example:** `OtpRepository.save(o)` currently auto-encrypts `o.secret`, `o.email`, `o.type`, `o.expiresAt`, `o.digits`, `o.algorithm`, `o.valid` via converters. A JdbcTemplate `INSERT` that forgets to call `EncryptionUtil.encrypt()` on `o.digits` would store the integer `6` as plaintext, while the converter path would have stored an AES-ECB encrypted Base64 string. Subsequent reads expecting encrypted data would fail with `NumberFormatException` on `Integer.valueOf(decrypt(...))`.

### 1.2 Optimistic Locking Regression

**Current state:** `Note` entity uses `@Version private Long version = 0L`. Hibernate automatically:
1. Includes `version` in WHERE clause of UPDATE: `WHERE id = ? AND version = ?`
2. Increments `version` in SET clause: `SET version = version + 1`
3. Checks rows affected; throws `OptimisticLockException` if 0

**Failure mode:** A JdbcTemplate `UPDATE` that omits the `WHERE version = ?` clause or fails to increment the version field enables **silent lost updates**. Two concurrent requests updating the same note would succeed, with the second overwriting the first — a data corruption bug that produces no error and no log entry.

**Occurrence likelihood:** HIGH. The codebase's existing `NoteService.saveNoteWithDeduplication()` already handles `DataIntegrityViolationException` from the unique constraint on `(title, user_id)`, demonstrating that concurrent access is a real production scenario.

### 1.3 Transaction Integrity — Dirty Checking → Explicit UPDATEs

**Current state:** Hibernate's persistence context tracks entity changes. When `@Transactional` commits, Hibernate auto-flushes dirty entities. Service code mutates entity objects freely:
```java
// PasswordService.updatePasswordByToken
existing.setName(encryption.encrypt(passwordData.getName().trim()));
existing.setPassword(encryption.encrypt(passwordData.getPassword().trim()));
existing.setWebsite(encryption.encrypt(passwordData.getWebsite().trim()));
return updatePassword(existing); // save() re-encrypts, causing double-encryption!
```

**Actual bug already present:** `PasswordService.updatePassword()` encrypts fields before `repository.save()`, but `updatePasswordByToken()` already encrypted them before calling `updatePassword()`. This means fields are **double-encrypted** on update. After migration, this must be fixed OR the same double-encryption must be replicated for behavioral equivalence.

**Failure mode:** With JdbcTemplate, every state change requires an explicit `UPDATE` statement. If a field is mutated in the service layer but the corresponding JdbcTemplate call forgets that field, the change is silently lost — no error, no rollback. With Hibernate's dirty checking, it would have been caught.

### 1.4 Deduplication Logic Regression

**Current state:** Two different deduplication patterns exist:

1. **Password:** `savePasswordForTokenWithDeduplication()` encrypts the search parameters first (because database stores encrypted data), then queries `findByUserIdAndNameAndWebsite(userId, encryptedName, encryptedWebsite)`. If found, updates; if not, creates.

2. **Note:** `saveNoteWithDeduplication()` uses `findByTitleIgnoreCaseAndUserId(title, userId)` — but this queries by **plaintext** title, not encrypted. The database stores encrypted titles, so this query actually compares the plaintext title against encrypted database values. This works because titles are unique per user and the `IgnoreCase` clause effectively does a compare against whatever was passed in.

**Critical insight:** After migration, JdbcTemplate queries must match the exact comparison semantics. The Password service encrypts before lookup (correct for encrypted-at-rest pattern). The Note service's case-insensitive search is **functionally broken** for encrypted data — `ILIKE` on AES ciphertext produces random matches. This is either a pre-existing bug or relies on the fact that unique constraints on encrypted data ensure uniqueness regardless.

**Failure mode:** A JdbcTemplate SQL query that compares plaintext against encrypted columns produces zero results, causing duplicate creation on every request. The `uk_title_user` unique constraint on notes would then cause `DataIntegrityViolationException` on the second request.

### 1.5 JPQL/SQL Query Mismatch

**Current JPQL queries that must be preserved:**

| Service | JPQL/SDG Method | Logic | Migration Risk |
|---------|----------------|-------|----------------|
| password | `findByUserIdAndNameAndWebsite(userId, name, website)` | Custom JPQL `@Query` with 3 params | Need to pass **pre-encrypted** params to SQL — params must match the encrypted DB values |
| password | `findByUserId(userId)` | Spring Data derived query | Simple `SELECT * FROM password WHERE user_id = ?` |
| password | `findByName(name)` | Spring Data derived query | Name is encrypted in DB — this query is **broken** unless `name` is pre-encrypted |
| note | `findByTitleIgnoreCaseContainingAndUserId(title, userId)` | Case-insensitive LIKE on encrypted field | **Impossible** — LIKE on AES ciphertext is meaningless. Must either: (a) decrypt all rows and filter in Java, or (b) change the encryption strategy |
| note | `findByTitleIgnoreCaseAndUserId(title, userId)` | Exact match, case-insensitive | Same issue — exact match on encrypted field only works if same plaintext encrypts to same ciphertext (it does NOT with random IV) |
| otp | `findByUserId(userId)` | Simple derived query | Straightforward, no encryption involved in WHERE |

**Critical finding:** The Note service's `findByTitleIgnoreCase` queries are **architecturally incompatible with field-level encryption using random IVs**. Each encryption of the same plaintext produces different ciphertext. These queries only "work" because they're comparing the plaintext parameter against encrypted DB values and returning nothing, falling through to create new records. After migration, this must be explicitly addressed — likely by fetching all notes for a user and filtering in Java, or by adding a deterministic hash column for lookups.

### 1.6 Crypto Converter Replacement

**Current state:** OTP service uses `jakarta.persistence.AttributeConverter` implementations that are **transparent** — service code never calls encrypt/decrypt; JPA does it at the entity-attribute boundary.

**Migrated state:** Every repository operation must explicitly call encrypt/decrypt for all 7 encrypted fields on the `otp` entity: `email`, `secret`, `expiresAt`, `type`, `digits`, `algorithm`, `valid`.

**Failure mode count:** With 7 encrypted fields and 4 CRUD operations (Create, Read, Update, Delete), there are **~28 encrypt/decrypt call sites** that must be correct. Missing one produces a bug. Additionally, the `OtpService.createOtp()` duplicate detection loop (lines 44-54) iterates over `existing` entities whose fields are already decrypted by converters — after migration, the JdbcTemplate read must decrypt these same fields for the comparison.

---

## 2. Architecture Summary (Migration Target)

### 2.1 What Changes

| Layer | Before | After |
|-------|--------|-------|
| Repository | Spring Data JPA interfaces (`JpaRepository`) | Custom `@Repository` classes using `JdbcTemplate` / `NamedParameterJdbcTemplate` |
| Entity Mapping | `@Entity`, `@Table`, `@Column`, `@Convert` annotations | Plain POJOs (no JPA annotations) + explicit RowMapper |
| Query Language | JPQL / Spring Data derived queries | Raw SQL strings |
| ID Generation | `@GeneratedValue(IDENTITY)` | `GeneratedKeyHolder` after INSERT |
| Dirty Checking | Automatic (Hibernate persistence context) | Manual — explicit UPDATE for every mutation |
| Optimistic Locking | `@Version` (automatic) | Manual `WHERE version = ?` and `SET version = version + 1` |
| Field Encryption (OTP) | `@Convert(converter = ...)` auto-apply | Manual encrypt/decrypt at every read/write boundary |
| Transactions | `@Transactional` (unchanged — Spring manages) | Same `@Transactional`, but must use `DataSourceTransactionManager` instead of `JpaTransactionManager` |

### 2.2 What Stays

- Spring Boot framework (controllers, DI, configuration)
- `@Transactional` boundaries (same service methods)
- Encryption algorithms (same AES keys, same cipher configs)
- Database schema (same tables, same columns, same types)
- Flyway migrations (no schema changes required for the migration itself)
- Kafka integration (unchanged)
- JWT authentication (unchanged)
- API contracts (same endpoints, same request/response shapes)

---

## 3. Pydantic Data Contracts (Test Input Validation)

The following are the implicit data contracts that JdbcTemplate RowMapper and SQL queries must satisfy.

### 3.1 Password Row Structure
```python
class PasswordRow:
    id: int          # BIGSERIAL PRIMARY KEY
    password: str    # AES-256-GCM Base64 encrypted
    name: str        # AES-256-GCM Base64 encrypted
    website: str     # AES-256-GCM Base64 encrypted
    user_id: int     # FK to auth service user
```

### 3.2 OTP Row Structure
```python
class OtpRow:
    id: int              # BIGSERIAL PRIMARY KEY
    user_id: int | None
    email: str           # AES-128-ECB Base64 encrypted
    secret: str          # AES-128-ECB Base64 encrypted
    expires_at: str      # AES-128-ECB Base64 encrypted (Long value)
    type: str            # AES-128-ECB Base64 encrypted ("TOTP"|"HOTP")
    issuer: str | None   # plaintext
    digits: str | None   # AES-128-ECB Base64 encrypted (Integer value)
    period: int | None   # plaintext
    algorithm: str | None  # AES-128-ECB Base64 encrypted
    valid: str           # AES-128-ECB Base64 encrypted ("true"|"false")
```

### 3.3 OtpKey Row Structure
```python
class OtpKeyRow:
    id: int              # BIGSERIAL PRIMARY KEY
    user_id: str
    otp: str
    created_at: datetime
```

### 3.4 Note Row Structure
```python
class NoteRow:
    id: int              # BIGSERIAL PRIMARY KEY
    content: str         # AES-256-CBC Base64 encrypted (TEXT column)
    title: str           # AES-256-CBC Base64 encrypted
    created_at: datetime | None
    user_id: int
    version: int         # Optimistic lock counter, NOT NULL DEFAULT 0
```

---

## 4. Pydantic API Contracts (Response Validation)

### 4.1 Password API Contract
```python
class PasswordResponse:
    id: int
    title: str        # @JsonProperty("title") → Password.name (DECRYPTED)
    website: str      # DECRYPTED
    password: str     # DECRYPTED
    userId: int

class PasswordCreateRequest:
    password: str     # REQUIRED, non-empty
    title: str        # REQUIRED, non-empty
    website: str | None  # Must match ^https?:// if provided
```

### 4.2 Note API Contract
```python
class NoteResponse:
    id: int
    content: str      # DECRYPTED
    title: str        # DECRYPTED
    createdAt: str | None  # ISO datetime
    userId: int
    # version is internal, NOT exposed in API
```

### 4.3 OTP API Contract
```python
class OtpResponse:
    id: int
    userId: int | None
    email: str        # DECRYPTED (this is actually the "name" field in the UI)
    secret: str       # DECRYPTED
    expiresAt: str    # timestamp, DECRYPTED
    type: str         # DECRYPTED ("TOTP"|"HOTP")
    issuer: str | None
    digits: int | None  # DECRYPTED
    period: int | None
    algorithm: str | None  # DECRYPTED
    valid: bool       # DECRYPTED
```

---

## 5. Evaluation Strategy

### 5.1 Eval Dimensions with Rubrics

#### DIM-01: Functional Correctness — CRUD Operations

| | |
|---|---|
| **Priority** | **CRITICAL** |
| **Description** | All CRUD operations produce identical results pre- and post-migration for the same inputs and database state |
| **PASS** | For every API endpoint in all 3 services: same HTTP status code, same response body (field-level equivalence excluding auto-generated timestamps), same side effects (rows inserted/updated/deleted) |
| **FAIL** | Any endpoint returns a different status code, different response body, misses an encryption/decryption step, inserts incorrect data, or produces a different number of affected rows |
| **Measurement** | Code-based: integration test with dual-write comparison (write via JPA, write via JdbcTemplate to separate tables, compare). Automated with JUnit 5 + AssertJ. |

#### DIM-02: Encryption Integrity

| | |
|---|---|
| **Priority** | **CRITICAL** |
| **Description** | All encrypted fields stored via JdbcTemplate can be decrypted correctly, and all fields that should be encrypted are NOT stored as plaintext |
| **PASS** | (1) Write a record via JdbcTemplate, read directly from DB with a raw SQL query — all encrypted columns contain valid Base64 ciphertext (not the original plaintext). (2) Decrypt each field using the appropriate EncryptionUtil and verify the value matches the original input. (3) Round-trip: write via JPA, read via JdbcTemplate RowMapper — decrypted values match. (4) Round-trip: write via JdbcTemplate, read via JPA — decrypted values match. |
| **FAIL** | Any encrypted column contains plaintext, or decryption produces an exception, or decrypted value does not match original input |
| **Measurement** | Code-based: parameterized JUnit tests with Testcontainers PostgreSQL. For each service, test every encrypted field with: null, empty string, max-length string, special characters, emoji, SQL injection strings. |

#### DIM-03: Concurrent Safety — Optimistic Locking

| | |
|---|---|
| **Priority** | **CRITICAL** |
| **Description** | Note entity's optimistic locking behaves identically post-migration: concurrent updates to the same note are detected and one fails |
| **PASS** | (1) Thread-A reads a note (version=N). (2) Thread-B reads same note (version=N). (3) Thread-A updates (version check passes, version becomes N+1). (4) Thread-B's update fails because version is now N+1 but Thread-B has N. (5) System correctly reports update failure (rows affected = 0). (6) Failed updater can re-read and retry. |
| **FAIL** | Thread-B's update succeeds (silent lost update), or incorrect version increment, or version check omitted entirely |
| **Measurement** | Code-based: `CountDownLatch`-based concurrent test with 2 threads attempting simultaneous update of the same note. Assert one succeeds, one fails. Test with an ExecutorService spawning 10 concurrent updates — exactly one wins on version 10, others fail. |

#### DIM-04: Query Equivalence — JPQL → SQL

| | |
|---|---|
| **Priority** | **HIGH** |
| **Description** | All custom queries return identical result sets pre- and post-migration for identical database states |
| **PASS** | For each custom query: (1) Insert identical test data into separate tables. (2) Execute JPA query on table A, JdbcTemplate SQL on table B. (3) Result sets contain same rows (same IDs), same ordering, same decrypted field values within a tolerance for timestamp precision. |
| **FAIL** | Row count differs, any row missing or extra, any decrypted value differs, ordering differs when expected to match |
| **Measurement** | Code-based: dedicated query comparison test class. Each JPA query method gets a corresponding JdbcTemplate SQL test with the same input parameters and assertion on output equivalence. |

#### DIM-05: Transaction Behavior

| | |
|---|---|
| **Priority** | **HIGH** |
| **Description** | Transaction isolation, propagation, and rollback semantics are preserved |
| **PASS** | (1) Exception during JdbcTemplate update → transaction rolls back, no data persisted. (2) Multiple JdbcTemplate operations in one `@Transactional` method → all succeed or all roll back. (3) Read-only transactions work. (4) Transaction timeout works. (5) Nested transaction propagation matches previous behavior. |
| **FAIL** | Partial commit (some operations persist despite exception), read-only transaction allows writes, transaction timeout not honored |
| **Measurement** | Code-based: `@Transactional` + intentional exception tests. Insert a row, then throw — assert row not found after TX boundary. Test with `@Transactional(propagation = Propagation.REQUIRES_NEW)`. |

#### DIM-06: Performance — No Regression

| | |
|---|---|
| **Priority** | **MEDIUM** |
| **Description** | JdbcTemplate operations do not introduce significant latency regression vs. JPA |
| **PASS** | (1) Bulk fetch of 100 passwords: P95 latency ≤ 110% of JPA baseline. (2) Bulk fetch of 100 notes: P95 latency ≤ 110% of JPA baseline. (3) Single CREATE/UPDATE/DELETE: P95 latency ≤ 120% of JPA baseline (JdbcTemplate may legitimately be slightly faster — regression is the concern). (4) No N+1 queries (JdbcTemplate naturally avoids these, but verify). |
| **FAIL** | Any operation exceeds 2x JPA baseline, or any N+1 pattern detected in JdbcTemplate code |
| **Measurement** | Code-based: JMH microbenchmarks or a dedicated performance test class with warmup iterations and P95 measurement across 1000 iterations. Also: check HikariCP connection pool metrics for unusual patterns. |

#### DIM-07: API Compatibility

| | |
|---|---|
| **Priority** | **CRITICAL** |
| **Description** | All HTTP endpoints return identical responses for identical inputs and database state |
| **PASS** | For every endpoint across all 3 services, with identical database state: same HTTP status code, same JSON structure (keys present/absent), same JSON values, same Content-Type header |
| **FAIL** | Any difference in status code, JSON keys (added/missing), JSON values (numeric precision differs by >0, string differs by any character), or response headers |
| **Measurement** | Code-based: Spring MockMvc integration tests. Run each endpoint test twice — once with JPA config, once with JdbcTemplate config — and compare the full JSON response using JSONAssert (LENIENT mode for timestamp fields, STRICT for all other fields). |

#### DIM-08: Deduplication Correctness

| | |
|---|---|
| **Priority** | **HIGH** |
| **Description** | Password and Note deduplication logic behaves identically |
| **PASS** | Password dedup: (1) Create password (title="Gmail", website="https://gmail.com") → created. (2) Create again with same title+website but different password value → first password's password field is UPDATED, no new row created. (3) Create password with same title but different website → new row created. Note dedup: (1) Create note (title="Shopping List") → created. (2) Create again with same title → existing note UPDATED. (3) Create with different title → new note created. |
| **FAIL** | Duplicate row created (2 rows with same dedup key for same user), or wrong row updated, or DataIntegrityViolationException not handled |
| **Measurement** | Code-based: integration test. Insert first entity, attempt second insert with same dedup key — verify row count unchanged and fields updated. Test concurrent dedup: 5 threads simultaneously creating the same password — exactly 1 row exists afterward. |

### 5.2 Reference Dataset Specification

| Attribute | Specification |
|-----------|---------------|
| **Size** | **20 examples per service (60 total)** minimum. Expand to 50 per service (150 total) for production cutover. |
| **Composition** | |
| Happy path | 8 examples — standard CRUD: create valid entity, read by ID, read all by user, update field, delete, verify cascade |
| Edge cases | 6 examples — edge values: null optional fields, max-length strings (10KB content), empty strings, special characters (unicode, emoji, SQL injection strings), boundary timestamp values (epoch, year 2038, null), extreme numeric values (Long.MAX_VALUE, negative values) |
| Concurrent scenarios | 3 examples — optimistic locking collision, dedup race condition, transaction rollback on partial failure |
| Encryption edge cases | 3 examples — already-encrypted value passed as input, decryption of corrupted Base64, encryption with empty key (should fail with clear error) |
| **Labeling** | Automated — dual-write comparison. For each test case: (1) Execute against JPA implementation → capture full HTTP response + DB state snapshot. (2) Execute same input against JdbcTemplate implementation → capture full HTTP response + DB state snapshot. (3) Automated assertion: HTTP responses match (JSONAssert), DB states match (row-by-row comparison on decrypted values). |
| **Creation Timeline** | Start during `@Repository` → `JdbcTemplate` rewrite. Build test cases concurrently with each repository migration. Run after each service migration is complete. Full dataset ready before integration testing. |

### 5.3 Eval Tooling

| Concern | Tool | Rationale |
|---------|------|-----------|
| **Integration testing** | JUnit 5 + Spring Boot Test + Testcontainers (PostgreSQL 16) | Real PostgreSQL, not H2 — encryption behaves differently across DBs and H2 doesn't support pgcrypto. Testcontainers provides disposable containers. |
| **API comparison** | Spring MockMvc + JSONAssert | Compare JPA vs JdbcTemplate endpoint responses for identical inputs |
| **DB state comparison** | Raw JdbcTemplate queries in tests | Bypass repository layer to read raw tables and verify encrypted/decrypted state |
| **Performance benchmarking** | JMH (Java Microbenchmark Harness) | Warmup iterations, P95 measurement, GC accounting |
| **Schema diff validation** | Flyway migration state + `pg_dump --schema-only` diff | Verify both implementations produce compatible schema. Since no schema change is planned, this validates that JdbcTemplate doesn't accidentally require schema changes |
| **Connection pool monitoring** | HikariCP metrics (built into Spring Boot Actuator) | Track active/idle connections, wait times, timeouts to detect connection leaks from manual connection management |
| **Tracing / Observability** | Spring Boot Actuator + Micrometer (existing) | Already in build.gradle.kts — add custom metrics for JdbcTemplate operation latency per query type |
| **CI/CD eval command** | `./gradlew test --tests "com.thisjowi.*.MigrationComparisonTest"` | Dedicated test profile that runs side-by-side comparison tests. Add to CI pipeline as a required check before merge. |

### 5.4 CI/CD Integration

```bash
# Run migration-specific comparison tests (all services)
./gradlew :password:test :otp:test :note:test \
  -Dspring.profiles.active=migration-test \
  --tests "com.thisjowi.*.MigrationComparisonTest" \
  --tests "com.thisjowi.*.JdbcTemplateIntegrationTest"

# Run performance regression suite
./gradlew :password:jmh :otp:jmh :note:jmh

# Run encryption integrity tests with Testcontainers
./gradlew :password:test :otp:test :note:test \
  --tests "com.thisjowi.*.EncryptionIntegrityTest"
```

Required CI checks before merge:
1. **migration-comparison-tests** (must pass) — dual-write comparison
2. **encryption-integrity-tests** (must pass) — round-trip encrypt/decrypt
3. **performance-benchmark** (must not exceed threshold) — P95 latency ≤ 120% baseline
4. **api-compatibility-tests** (must pass) — MockMvc response comparison

---

## 6. Guardrails

### 6.1 Online Guardrails (Run on Every Request — Catastrophic Failures)

These guardrails are **code-based validators** that run in-request (low latency) and prevent data corruption.

| Guardrail | Applies To | What It Checks | Action on Failure | Latency Impact |
|-----------|-----------|----------------|-------------------|----------------|
| **Decryption result type validation** | All 3 services | After decrypting a field read from DB, validate the result is the expected type: `Long.valueOf(decrypt(...))` must not throw `NumberFormatException`; `Boolean.valueOf(decrypt(...))` must produce "true" or "false" | Log ERROR with record ID, return HTTP 500 with a generic "Data integrity error" message. Do NOT expose decrypted content in logs. | <1ms per field |
| **Optimistic lock check** | note | After UPDATE on notes table, assert `rowsAffected == 1`. If 0, the version check failed. | Throw `OptimisticLockingFailureException` (Spring's standard exception) → controller catches and returns HTTP 409 Conflict with retry instructions | <1ms |
| **Encryption round-trip verification** | password, note | On SELECT: decrypt field, re-encrypt, verify re-encrypted value != original plaintext (sanity check that encryption was applied) | Log WARN (not ERROR — false positives possible with different IVs). Primarily a development guardrail; disable in production. | ~0.5ms per field |
| **Encrypted column length sanity** | all | On SELECT: verify encrypted column length > plaintext length + IV overhead. If encrypted column is too short, decryption will fail. | Skip decryption, log ERROR, return encrypted value as-is (fail-safe: return garbled data rather than crash). Better to return HTTP 500. | <1ms |

### 6.2 Offline Flywheel (Sampled Batch — Quality Signals)

These run as periodic batch jobs on sampled production data and feed improvement loops.

| Flywheel Metric | Sampling Strategy | What It Measures | Action |
|-----------------|-------------------|-----------------|--------|
| **Decryption failure rate** | 1% of all reads, weighted toward rows with short encrypted columns | Percentage of SELECT operations where decryption throws an exception | If >0.01%, alert on-call and scan affected rows for data corruption |
| **Double-encryption detection** | 5% of UPDATE operations | Length of encrypted field after UPDATE: if it's ~2x the expected encrypted length, double encryption occurred | Log WARN with table + column. Auto-fix: re-read the row, decrypt twice, re-encrypt once, update. |
| **Optimistic lock collision rate** | All UPDATE failures on notes | Rate of HTTP 409 responses from note update endpoints | If sudden spike (>10x normal), investigate potential race condition in client code |
| **Query performance drift** | Every 5th query per endpoint, 1000-sample rolling window | P95 latency of each JdbcTemplate operation | If drift >20% beyond deployment baseline, trigger performance investigation |
| **Connection pool saturation** | Continuous via HikariCP metrics | Active connections / max pool size ratio | If >80% for >5 minutes, alert — potential connection leak from unclosed ResultSets or manual connections |
| **Plaintext detection** | 0.1% of encrypted columns, daily scan | Raw scan of encrypted TEXT/VARCHAR columns for ASCII printable strings matching expected plaintext patterns (e.g., email regex, URL patterns) | If detected, flag row for manual investigation. Indicates a missing encrypt() call. |

---

## 7. Production Monitoring

### 7.1 Tracing / Observability

**Tooling:** Spring Boot Actuator + Micrometer (already in `build.gradle.kts`) with PostgreSQL exporter.

```
Existing (keep):
  - spring-boot-starter-actuator (all 3 services)
  - HikariCP connection pool metrics
  - JVM metrics (heap, GC, threads)

Add for migration monitoring:
  - Custom Micrometer Timer per JdbcTemplate operation:
    db.query.password.selectByUserId
    db.query.password.insert
    db.query.password.update
    db.query.otp.selectById
    db.query.otp.insert
    db.query.note.selectByUserId
    db.query.note.updateWithVersion
  - Micrometer Counter for:
    db.error.decryption_failure
    db.error.optimistic_lock_failure
    db.error.double_encryption_detected
    db.error.type_conversion_failure (e.g., NumberFormatException on decrypt)
```

**Dashboard:** Grafana dashboard with panels for:
1. P95 latency per operation type (line chart, 5-min rolling)
2. Error rate by type (stacked bar, 1-min buckets)
3. Connection pool utilization (gauge)
4. Transaction rollback rate (counter)
5. Optimistic lock collision rate (counter)

### 7.2 Key Metrics and Alert Thresholds

| Metric | Threshold | Severity | Runbook |
|--------|-----------|----------|---------|
| `db.error.decryption_failure` > 0 | Any non-zero count in 5-min window | **CRITICAL** | Data corruption. Stop deployment, investigate affected rows, check encryption key configuration |
| `db.error.optimistic_lock_failure` > 100/min | Spike 10x above baseline | **WARNING** | Possible race condition or retry storm. Check client behavior. |
| `db.error.type_conversion_failure` > 0 | Any non-zero count | **CRITICAL** | Mismatch between stored encrypted value and expected type. Likely missing/incorrect encrypt() call on write. |
| P95 latency > 2x baseline | >500ms for any query type | **WARNING** | Check for missing index, table scan, or connection pool exhaustion |
| `hikaricp_connections_active / hikaricp_connections_max` > 0.8 | >80% for >5 minutes | **CRITICAL** | Connection leak. Check for unclosed resources in JdbcTemplate code. Restart service. |
| Transaction rollback rate > 5% | >5% of all transactional operations | **WARNING** | Investigation needed — may indicate cascading failures or application logic bugs exposed by migration |

### 7.3 Sampling Strategy

**Production sampling (for offline flywheel):**
- Encrypt/decrypt health: 1% of all reads (random sample, seed based on request ID for reproducibility)
- Plaintext detection scan: 0.1% of rows, daily at 3am UTC (low-traffic window)
- Query comparison validation: 0.01% of writes (dual-write to a shadow table during initial migration phase only, removed after stabilization)

**Initial migration phase (first 7 days post-deployment):**
- 100% sampling of all JdbcTemplate operations for latency and error metrics
- 5% sampling for decryption health checks
- Daily full-scan of encrypted columns for plaintext leaks

**Steady state (after 7 days of stable operation):**
- Reduce to rates in table above
- Keep 100% error sampling always

---

## Appendix A: Service-Specific Migration Checklists

### A.1 Password Service — JPA → JdbcTemplate Mapping

| JPA Repository Method | JdbcTemplate SQL | Encryption Notes |
|----------------------|------------------|------------------|
| `findAll()` | `SELECT id, password, name, website, user_id FROM password` | Decrypt password, name, website after SELECT |
| `findById(Long id)` | `SELECT ... WHERE id = ?` | Same decrypt pattern |
| `findByUserId(Long userId)` | `SELECT ... WHERE user_id = ?` | Same decrypt pattern |
| `findByName(String name)` | `SELECT ... WHERE name = ?` | Parameter MUST be pre-encrypted |
| `findByUserIdAndNameAndWebsite(userId, name, website)` | `SELECT ... WHERE user_id = ? AND name = ? AND website = ?` | ALL 3 params MUST be pre-encrypted |
| `save(Password)` | `INSERT INTO password (password, name, website, user_id) VALUES (?, ?, ?, ?)` RETURNING id | Pre-encrypt password, name, website. Use `GeneratedKeyHolder` for id. |
| `deleteById(Long id)` | `DELETE FROM password WHERE id = ?` | No encryption needed |
| `existsById(Long id)` | `SELECT COUNT(*) FROM password WHERE id = ?` | No encryption needed |

### A.2 Note Service — JPA → JdbcTemplate Mapping

| JPA Repository Method | JdbcTemplate SQL | Encryption Notes | Optimistic Locking |
|----------------------|------------------|------------------|-------------------|
| `findByUserId(Long userId)` | `SELECT id, content, title, created_at, user_id, version FROM notes WHERE user_id = ?` | Decrypt content, title after SELECT | N/A for read |
| `findByTitleIgnoreCaseContainingAndUserId(title, userId)` | **ARCHITECTURAL CHANGE**: Cannot do ILIKE on encrypted field. Must `SELECT * FROM notes WHERE user_id = ?`, decrypt all titles in Java, then filter with `String::contains` case-insensitive | Decrypt in Java, filter | N/A for read |
| `findByTitleIgnoreCaseAndUserId(title, userId)` | Same as above — fetch all for user, filter in Java | Same | N/A |
| `save(Note)` (INSERT) | `INSERT INTO notes (content, title, created_at, user_id, version) VALUES (?, ?, ?, ?, 0)` RETURNING id | Pre-encrypt content, title | Set version=0 |
| `save(Note)` (UPDATE) | `UPDATE notes SET content = ?, title = ?, created_at = ?, user_id = ?, version = version + 1 WHERE id = ? AND version = ?` | Pre-encrypt content, title | **MUST include `WHERE version = ?`** and check rows affected == 1 |
| `deleteById(Long id)` | `DELETE FROM notes WHERE id = ?` | No encryption | No locking needed |

### A.3 OTP Service — JPA → JdbcTemplate Mapping

| JPA Repository Method | JdbcTemplate SQL | Encryption Notes |
|----------------------|------------------|------------------|
| `findByUserId(Long userId)` | `SELECT * FROM otp WHERE user_id = ?` | Decrypt 7 fields: email, secret, expiresAt, type, digits, algorithm, valid |
| `findById(Long id)` | `SELECT * FROM otp WHERE id = ?` | Same decrypt pattern |
| `save(otp)` (INSERT) | `INSERT INTO otp (user_id, email, secret, expires_at, type, issuer, digits, period, algorithm, valid) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)` RETURNING id | Pre-encrypt: email→StringCrypto, secret→StringCrypto, expiresAt→LongCrypto, type→StringCrypto, digits→IntegerCrypto, algorithm→StringCrypto, valid→BooleanCrypto |
| `save(otp)` (UPDATE) | `UPDATE otp SET user_id = ?, email = ?, secret = ?, expires_at = ?, type = ?, issuer = ?, digits = ?, period = ?, algorithm = ?, valid = ? WHERE id = ?` | Pre-encrypt same 7 fields |
| `deleteById(Long id)` | `DELETE FROM otp WHERE id = ?` | No encryption |
| Duplicate detection loop (OtpService line 44-54) | Fetch all for user (decrypted), loop in Java comparing `.trim().replace(" ", "").toUpperCase()` on secret | Decrypt all before comparison |

### A.4 OtpKey Service — JPA → JdbcTemplate Mapping

| JPA Repository Method | JdbcTemplate SQL | Notes |
|----------------------|------------------|-------|
| `findByUserId(String userId)` | `SELECT id, user_id, otp, created_at FROM otp_key WHERE user_id = ?` | No encryption (fields are plaintext) |
| `save(OtpKey)` INSERT | `INSERT INTO otp_key (user_id, otp, created_at) VALUES (?, ?, ?)` RETURNING id | No encryption |
| `delete(OtpKey)` | `DELETE FROM otp_key WHERE id = ?` | No encryption |

## Appendix B: Encryption Algorithm Inventory

| Service | Class | Algorithm | Mode | Padding | IV Size | Key Size | Key Derivation | Known Weakness |
|---------|-------|-----------|------|---------|---------|----------|----------------|----------------|
| password | `Encryption` (component) | AES | GCM | NoPadding | 12 bytes | 256-bit | First 32 bytes of `jwt.secret` | None (GCM is authenticated) |
| note | `EncryptionUtil` (static) | AES | CBC | PKCS5Padding | 16 bytes | 256-bit | SHA-256 of `jwt.secret` | Static singleton pattern fragile for testing |
| otp | `EncryptionUtil` (static) | AES | ECB | PKCS5Padding | None | 128-bit | SHA-1 of `app.encryption.key` | **ECB mode (no IV), SHA-1 (deprecated), 128-bit key (not 256)** |

**Migration recommendation:** The OTP service's ECB mode encryption should be upgraded to at least CBC with random IV as part of the migration, since the code path is being rewritten anyway. If maintaining backward compatibility with existing encrypted data is required, implement a dual-read path: try new CBC decryption, fall back to old ECB decryption for existing rows. New writes always use CBC.

## Appendix C: Test Directory Structure

```
server/
├── password/src/test/java/com/thisjowi/password/
│   ├── migration/
│   │   ├── PasswordJdbcTemplateIntegrationTest.java    # Testcontainers + JdbcTemplate
│   │   ├── PasswordMigrationComparisonTest.java        # Dual-write JPA vs JdbcTemplate
│   │   └── PasswordEncryptionIntegrityTest.java        # Round-trip encrypt/decrypt
│   ├── service/
│   │   ├── PasswordServiceJdbcTest.java
│   │   └── PasswordDeduplicationJdbcTest.java          # Concurrent dedup tests
│   └── benchmark/
│       └── PasswordBenchmark.java                      # JMH benchmarks
├── otp/src/test/java/com/thisjowi/otp/
│   ├── migration/
│   │   ├── OtpJdbcTemplateIntegrationTest.java
│   │   ├── OtpMigrationComparisonTest.java
│   │   └── OtpEncryptionIntegrityTest.java             # Test ALL 4 converters manually
│   └── benchmark/
│       └── OtpBenchmark.java
├── note/src/test/java/com/thisjowi/note/
│   ├── migration/
│   │   ├── NoteJdbcTemplateIntegrationTest.java
│   │   ├── NoteMigrationComparisonTest.java
│   │   ├── NoteEncryptionIntegrityTest.java
│   │   └── NoteOptimisticLockingTest.java              # Concurrent update tests
│   └── benchmark/
│       └── NoteBenchmark.java
└── shared-test/                                        # New module for shared test utilities
    └── src/test/java/com/thisjowi/shared/
        ├── DualWriteTestUtil.java                      # Helper for JPA vs JdbcTemplate comparison
        ├── EncryptionAssertions.java                   # Common encrypt/decrypt assertions
        └── TestcontainersConfig.java                   # Shared PostgreSQL container config
```
