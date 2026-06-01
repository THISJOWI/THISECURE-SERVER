---
phase: XX-jdbc-template-migration-review
reviewed: 2026-05-22T12:00:00Z
depth: deep
files_reviewed: 24
files_reviewed_list:
  - password/src/main/java/com/thisjowi/password/Repository/PasswordRepository.java
  - password/src/main/java/com/thisjowi/password/Service/PasswordService.java
  - password/src/main/java/com/thisjowi/password/Service/PasswordDeduplicationService.java
  - password/src/main/java/com/thisjowi/password/Controller/PasswordController.java
  - password/src/main/java/com/thisjowi/password/Entity/Password.java
  - password/src/main/java/com/thisjowi/password/Entity/PasswordDTO.java
  - password/src/main/java/com/thisjowi/password/Utils/Encryption.java
  - password/src/main/java/com/thisjowi/password/Utils/JwtUtil.java
  - password/src/main/java/com/thisjowi/password/kafka/KafkaConsumerService.java
  - otp/src/main/java/com/thisjowi/otp/repository/OtpRepository.java
  - otp/src/main/java/com/thisjowi/otp/repository/OtpKeyRepository.java
  - otp/src/main/java/com/thisjowi/otp/service/OtpService.java
  - otp/src/main/java/com/thisjowi/otp/controller/OtpController.java
  - otp/src/main/java/com/thisjowi/otp/entity/otp.java
  - otp/src/main/java/com/thisjowi/otp/model/OtpKey.java
  - otp/src/main/java/com/thisjowi/otp/converter/StringCryptoConverter.java
  - otp/src/main/java/com/thisjowi/otp/converter/LongCryptoConverter.java
  - otp/src/main/java/com/thisjowi/otp/converter/IntegerCryptoConverter.java
  - otp/src/main/java/com/thisjowi/otp/converter/BooleanCryptoConverter.java
  - otp/src/main/java/com/thisjowi/otp/util/EncryptionUtil.java
  - otp/src/main/java/com/thisjowi/otp/config/EncryptionConfig.java
  - note/src/main/java/com/thisjowi/note/repository/NoteRepository.java
  - note/src/main/java/com/thisjowi/note/service/NoteService.java
  - note/src/main/java/com/thisjowi/note/controller/NotesController.java
  - note/src/main/java/com/thisjowi/note/entity/Note.java
  - note/src/main/java/com/thisjowi/note/Utils/EncryptionUtil.java
  - note/src/main/java/com/thisjowi/note/service/AuthenticationClient.java
  - note/src/main/java/com/thisjowi/note/kafka/KafkaConsumerService.java
findings:
  critical: 4
  warning: 15
  info: 7
  total: 26
status: issues_found
---

# Phase XX: Code Review Report — ORM Layer for JdbcTemplate Migration

**Reviewed:** 2026-05-22T12:00:00Z
**Depth:** deep
**Files Reviewed:** 24
**Status:** issues_found — 26 findings (4 critical, 15 warning, 7 info)

## Summary

This review analyzed 24 Java source files across three Spring Boot microservices (password, otp, note) with a focus on the JPA/Hibernate → JdbcTemplate migration. Each service uses Spring Data JPA with inconsistent patterns: the password service encrypts at the service layer (migration-friendly), the note service encrypts at the service layer (migration-friendly), but the **otp service relies entirely on JPA `AttributeConverter` for encryption** — this is the single biggest migration blocker.

**Key concerns:**
- **AES/ECB encryption in the OTP service** with a hardcoded fallback key (`"ThisIsADefaultKeyForDevOnly123"`) — not just weak, but actively dangerous.
- **JWT signature verification is completely absent** in the OTP controller — token payload is parsed without cryptographic verification, enabling trivial authentication bypass.
- **No Flyway or SQL migration files exist** anywhere in the repository — the entire database schema is managed via `ddl-auto` from JPA entities, which disappears with the migration.
- **Three different, mutually incompatible encryption algorithms** (AES-256-GCM, AES-256-CBC, AES-128-ECB) across the three services, with no shared module.
- **Dirty checking risk** in PasswordService where decrypted values are written back to managed entities after save.

The note service is the most "migration-ready" — it has service-layer encryption, proper response-copy patterns to avoid dirty checking, and `@Transactional` annotations. The OTP service requires the most rework.

---

## Critical Issues

### CR-01: OTP Controller — JWT Parsing Without Signature Verification (Authentication Bypass)

**File:** `otp/src/main/java/com/thisjowi/otp/controller/OtpController.java:153-177`
**Issue:** The `extractUserIdFromToken` method manually splits the JWT on `.`, Base64-decodes the payload, and extracts the `sub` claim **without ever verifying the JWT signature**. Any attacker can craft a JWT with an arbitrary `sub` value and gain access to any user's OTP data. This is a complete authentication bypass.

```java
// Lines 157-170 — the payload is decoded but never verified:
String[] parts = token.split("\\.");
if (parts.length == 3) {
    String payload = parts[1];
    // ... Base64 decode ...
    if (node.has("sub")) {
        return Long.parseLong(node.get("sub").asText()); // ← No signature check!
    }
}
```

**Why it matters for the migration:** A JdbcTemplate migration should include proper security hardening. The JWT parsing must use a verified JWT library (jjwt is already in the project via the password service's `JwtUtil`, which uses `io.jsonwebtoken` and properly calls `.verifyWith(key)`).

**Fix:**
```java
// Delete the entire extractUserIdFromToken method and replace with:
private final JwtUtil jwtUtil;  // Inject a JwtUtil (like password service has)

private Long extractUserIdFromToken(String token) {
    if (token == null || !token.startsWith("Bearer ")) return null;
    return jwtUtil.extractUserId(token.substring(7));
}
```

---

### CR-02: OTP EncryptionUtil — AES/ECB Mode + Hardcoded Default Key

**File:** `otp/src/main/java/com/thisjowi/otp/util/EncryptionUtil.java:16,46-47,51,56-57,65`
**Issue:** The OTP service's encryption uses **AES/ECB mode** (Electronic Codebook) — the weakest block cipher mode. Identical plaintext blocks produce identical ciphertext blocks, leaking data patterns. Moreover, there is a **hardcoded default key** `"ThisIsADefaultKeyForDevOnly123"` on line 16, and the implementation uses **SHA-1** (deprecated in 2017) for key derivation (line 29-32). The decrypt path silently returns the input as plaintext on failure (line 66), masking errors.

**Why it matters for the migration:** JPA converters that use this broken encryption are the ONLY encryption mechanism in the OTP service. When converters are removed during migration, every field currently encrypted by converters becomes plaintext. The replacement encryption must be migrated to AES-256-GCM (like the password service) or AES-256-CBC with authentication, and applied at the service layer.

**Fix:**
- Replace the entire `EncryptionUtil` with the password service's GCM-based `Encryption` class (or extract a shared `encryption-common` library).
- Remove the default key fallback. Throw on missing configuration.
- Use SHA-256 for key derivation instead of SHA-1.
- Never silently return plaintext on decryption failure — throw a checked exception.

---

### CR-03: OTP Double-Encryption Conflict — JPA Converters vs. Controller Inline Decrypt

**File:** `otp/src/main/java/com/thisjowi/otp/controller/OtpController.java:72,115-151` + `otp/src/main/java/com/thisjowi/otp/entity/otp.java:26-59`
**Issue:** The data flows through **three conflicting encryption layers**:

1. Client sends encrypted secret → 2. `OtpController` decrypts it (line 72) → 3. Sets plaintext on entity → 4. JPA `StringCryptoConverter` encrypts it AGAIN on `save()` (line 12 in StringCryptoConverter) → 5. Returns encrypted entity to client (controller does NOT decrypt the response).

The result is broken: the client receives encrypted data it cannot decrypt because the response encryption is different from what it sent. Additionally, if the converter fails, **cleartext secrets are stored in the database**. The controller's inline `decrypt()` method (lines 115-151) duplicates encryption logic and uses `encryptionKey.substring(0, 32)` — fragile and incompatible with key derivation used by `EncryptionUtil`.

**Why it matters for the migration:** The JdbcTemplate migration removes converters. The encryption must be unified: **service-layer encryption only** (like the password and note services). The controller should never contain encryption logic.

**Fix:**
1. Remove all `@Convert` annotations from `otp.java` entity.
2. Remove the inline `decrypt()` method from `OtpController`.
3. Move encryption/decryption to `OtpService` at the boundaries: encrypt before `save()`, decrypt after `find()`.
4. The controller handles only HTTP concerns — pass encrypted data to/from the service.

---

### CR-04: PasswordService — Dirty Checking Risk (Decrypting Managed Entity After Save)

**File:** `password/src/main/java/com/thisjowi/password/Service/PasswordService.java:24-41,268-285`
**Issue:** Both `savePassword()` and `updatePassword()` follow this dangerous pattern:

```java
Password saved = passwordRepository.save(password);  // Managed entity, encrypted
decryptPasswordFields(saved);  // Modifies the MANAGED entity in-place!
return saved;
```

The `saved` entity is managed by Hibernate's persistence context. `decryptPasswordFields` modifies it in-place by calling `setPassword()`, `setName()`, `setWebsite()` with decrypted values. If there is a wrapping transaction (or if Spring Data JPA flushes on query execution), these decrypted values will be **written back to the database**. The risk is real because `PasswordDeduplicationService.savePasswordForTokenWithDeduplication` calls `savePassword()` and may execute within a shared transaction context.

Note that `savePasswordForTokenWithDeduplication` (line 48) calls `savePassword()` and `updatePassword()` — both of which decrypt in-place — and the service lacks `@Transactional`.

**Why it matters for the migration:** With JdbcTemplate, there is no dirty checking, so this pattern becomes safe. However, during the migration itself (or if the JPA version remains in production), this is a data-corruption risk.

**Fix:** Follow the note service's pattern — create a response copy:
```java
public Password savePassword(Password password) {
    encryptPasswordFields(password);
    Password saved = passwordRepository.save(password);
    return createDecryptedCopy(saved);  // Return a DTO or detached copy
}
```

---

## Warnings

### WR-01: No Flyway/SQL Migration Files — Entire Schema Relies on JPA ddl-auto

**File:** `(absent — no SQL files anywhere in repository)`
**Issue:** Not a single `.sql` file exists in the entire repository. All three services rely on Spring Boot's `ddl-auto` (likely `update` or `create-drop`) to auto-generate schema from `@Entity` annotations. With JdbcTemplate, `ddl-auto` does not apply — schema management must be explicit.

**Why it matters for the migration:** This is a hard blocker for the JdbcTemplate migration. Before migrating any service, Flyway (or Liquibase) must be configured and initial baseline migrations (V1__create_*.sql) must be generated from the current entity definitions. Without this, the database schema cannot be reliably created, versioned, or migrated.

**Fix:** Add Flyway dependency to each service. Generate baseline SQL migrations from the current JPA entity definitions. Example for a second migration:
```sql
-- V2__add_indexes.sql
CREATE INDEX idx_password_user_id ON password(user_id);
CREATE INDEX idx_otp_user_id ON otp(user_id);
CREATE INDEX idx_notes_user_id ON notes(user_id);
```

---

### WR-02: OTP JPA Converters — Encryption at Wrong Layer for JdbcTemplate Migration

**Files:**
- `otp/src/main/java/com/thisjowi/otp/converter/StringCryptoConverter.java`
- `otp/src/main/java/com/thisjowi/otp/converter/LongCryptoConverter.java`
- `otp/src/main/java/com/thisjowi/otp/converter/IntegerCryptoConverter.java`
- `otp/src/main/java/com/thisjowi/otp/converter/BooleanCryptoConverter.java`

**Issue:** All four converters hook into the JPA `AttributeConverter` lifecycle. When switching to JdbcTemplate, these converters are **dead code** — JdbcTemplate calls `RowMapper`/`PreparedStatement.setX()` directly with no interception point. Every field annotated with `@Convert` in `otp.java` (email, secret, expiresAt, type, digits, algorithm, valid) will become **unencrypted after migration** unless service-layer encryption is added.

**Why it matters for the migration:** This is the core architectural incompatibility. The OTP service must be refactored to move encryption from converters into the service layer before any JdbcTemplate code can replace the repositories.

**Fix:** Before migration:
1. Create encryption/decryption wrapper methods in `OtpService`.
2. Remove `@Convert` annotations from all fields in `otp.java`.
3. Add `encryptOtpFields()` / `decryptOtpFields()` to `OtpService` (mirroring `PasswordService`).
4. After migration: delete all converter classes and the `EncryptionConfig.java` circular-dependency workaround.

---

### WR-03: PasswordService Lacks @Transactional on All Modifying Methods

**File:** `password/src/main/java/com/thisjowi/password/Service/PasswordService.java:24-285`
**Issue:** None of the 6 service methods declare `@Transactional`. Spring Data JPA repositories apply implicit transactions per-call, but the service methods that perform read-modify-write (`savePasswordForTokenWithDeduplication`, `deletePasswordByToken`) have no atomic transaction boundary. If `findById` succeeds but `deleteById` fails (or vice versa), the system is left in an inconsistent state.

**Why it matters for the migration:** JdbcTemplate has NO implicit transactions. Every multi-statement operation MUST be wrapped in an explicit `@Transactional` or `TransactionTemplate`. This must be addressed before or during migration.

**Fix:** Add `@Transactional` to all modifying methods:
```java
@Transactional
public Password savePassword(Password password) { ... }

@Transactional
public void deletePasswordByToken(String authHeader, Long id) { ... }
```

---

### WR-04: NoteRepository — findByTitle Queries Are Impossible with Encrypted Titles

**File:** `note/src/main/java/com/thisjowi/note/repository/NoteRepository.java:11-15`
**Issue:** The repository defines `findByTitleIgnoreCase()`, `findByTitleIgnoreCaseContaining()`, `findByCreatedAt()`, and `findByTitleIgnoreCaseAndUserId()` as JPA-derived query methods. But `title` is stored **encrypted** — the ciphertext stored in the database cannot be matched by a plaintext search parameter. These queries will **always return empty results**.

The `findByTitleIgnoreCase` method (line 14) is particularly dangerous: it searches by title WITHOUT userId filtering. If titles were ever stored unencrypted, a user could fetch another user's note by guessing its title.

**Why it matters for the migration:** JdbcTemplate does not auto-generate queries from method names. These must be replaced with explicit SQL. But more importantly, the current logic is silently broken — it should either be removed or replaced with application-side filtering after decryption.

**Fix:** For the migration:
1. Remove `findByTitleIgnoreCaseContaining`, `findByTitleIgnoreCase`, `findByTitleIgnoreCaseContainingAndUserId`, and `findByTitleIgnoreCaseAndUserId` from the repository.
2. Fetch all notes by userId, decrypt in service layer, then filter by title in-memory using Java streams.

---

### WR-05: Three Incompatible Encryption Algorithms Across Services — No Shared Module

**Files:**
- `password/src/main/java/com/thisjowi/password/Utils/Encryption.java` — AES-256-GCM (modern, authenticated)
- `note/src/main/java/com/thisjowi/note/Utils/EncryptionUtil.java` — AES-256-CBC with random IV (good, but no authentication tag)
- `otp/src/main/java/com/thisjowi/otp/util/EncryptionUtil.java` — AES-128-ECB with SHA-1 (dangerously weak)

**Issue:** Data encrypted by one service cannot be decrypted by another. If there is ever cross-service data access (e.g., the authentication service needs to read OTP data), it will fail. More critically for the migration: the OTP service must be completely rewritten to match the GCM approach used by the password service.

**Why it matters for the migration:** The migration is an opportunity to extract a shared `encryption-common` module. Without it, ciphertext migrated from old schema to new schema may not be readable.

**Fix:** Extract a shared `encryption-common` library providing AES-256-GCM encryption. All three services should depend on it with the same configuration (`${jwt.secret}`).

---

### WR-06: Static Token in KafkaConsumerService — Cross-User Race Condition

**Files:**
- `password/src/main/java/com/thisjowi/password/kafka/KafkaConsumerService.java:18`
- `note/src/main/java/com/thisjowi/note/kafka/KafkaConsumerService.java:11`

**Issue:** Both services store the latest JWT token in a `public static final AtomicReference<String>`. This is a **single global variable shared across all threads and all requests**. If User A's token arrives via Kafka and User B makes an API request simultaneously, User B could operate with User A's credentials. The password service uses Kafka as a fallback token source (per the `extractUserIdFromToken` comments), but the `PasswordService.getPasswordsByToken` method explicitly rejects Kafka-sourced tokens (line 92 comment) — which is the right call. The note service's NotesController does NOT have this protection.

**Why it matters for the migration:** This architectural flaw predates the migration. With JdbcTemplate, the same risk exists. The static token approach should be replaced with proper token-per-request handling during the migration cleanup phase.

**Fix:** Remove the static AtomicReference entirely. Use only the Authorization header from the HTTP request. The Kafka listener should store tokens **per-user** in a time-limited cache (e.g., Redis, Caffeine) keyed by userId — or be removed if not used.

---

### WR-07: Missing Database Indexes on userId Columns (All Services)

**Files:**
- `password/src/main/java/com/thisjowi/password/Entity/Password.java:34`
- `otp/src/main/java/com/thisjowi/otp/entity/otp.java:23`
- `note/src/main/java/com/thisjowi/note/entity/Note.java:36`

**Issue:** Every query in all three services filters by `userId` (`findByUserId`), yet no `@Index` annotation or database index exists on the `userId` column. With `ddl-auto`, index creation depends on whether Hibernate auto-creates indexes (it does not by default for non-primary-key columns). As the dataset grows, these queries will perform full table scans.

**Why it matters for the migration:** JdbcTemplate migrations require explicit DDL. The Flyway migration scripts should include index creation. Without indexes, a JdbcTemplate query like `SELECT * FROM password WHERE user_id = ?` will scan the entire table.

**Fix:** Add to Flyway migration:
```sql
CREATE INDEX idx_password_user_id ON password(user_id);
CREATE INDEX idx_otp_user_id ON otp(user_id);
CREATE INDEX idx_notes_user_id ON notes(user_id);
```

---

### WR-08: OtpService.updateOtp Blindly Overwrites All Fields with Nulls

**File:** `otp/src/main/java/com/thisjowi/otp/service/OtpService.java:119-122`
**Issue:**
```java
public otp updateOtp(Long id, otp updatedOtp) {
    updatedOtp.setId(id);
    return otpRepository.save(updatedOtp);
}
```
This replaces the entire row with whatever the caller provides. If the caller omits `digits`, `period`, `algorithm`, or `valid`, those columns become `null` in the database. Worse, the `@Column(nullable = false)` constraints on `email`, `secret`, `expiresAt`, `type`, and `valid` will cause a `DataIntegrityViolationException` if any of those fields are null.

**Why it matters for the migration:** With JdbcTemplate, an `UPDATE` statement must list specific columns. The migration should adopt a **selective update** pattern — fetch the existing row, merge fields, then save.

**Fix:**
```java
@Transactional
public otp updateOtp(Long id, otp updatedOtp) {
    otp existing = otpRepository.findById(id)
        .orElseThrow(() -> new EntityNotFoundException("OTP not found: " + id));
    if (updatedOtp.getSecret() != null) existing.setSecret(updatedOtp.getSecret());
    if (updatedOtp.getIssuer() != null) existing.setIssuer(updatedOtp.getIssuer());
    // ... selectively merge other fields
    return otpRepository.save(existing);
}
```

---

### WR-09: OtpService.createOtp — O(N) Duplicate Check Loads All User OTPs

**File:** `otp/src/main/java/com/thisjowi/otp/service/OtpService.java:42-55`
**Issue:** To check for duplicates, `createOtp` loads ALL OTPs for the user into memory and iterates through them comparing normalized secrets. This is an O(N) operation on the application side.

**Why it matters for the migration:** JdbcTemplate won't have `findByUserId` auto-generated — you'll write the SQL manually. This is an opportunity to add a `COUNT(*) ... WHERE user_id = ? AND secret = ?` query instead of loading all rows.

**Fix:** After migration, replace the in-memory loop with:
```sql
SELECT COUNT(*) FROM otp WHERE user_id = ? AND secret = ?
```

---

### WR-10: PasswordDeduplicationService.removeDuplicates — No Transaction for Multi-Delete

**File:** `password/src/main/java/com/thisjowi/password/Service/PasswordDeduplicationService.java:81-125`
**Issue:** The `removeDuplicates` method deletes multiple records in a loop (lines 109-115) without `@Transactional`. If the process fails midway, some duplicates are deleted and others remain — with no way to roll back. The method also lacks locking (`@Lock`, `SELECT ... FOR UPDATE`), so concurrent duplicate detection and deletion could interfere.

**Why it matters for the migration:** JdbcTemplate requires explicit `@Transactional` for batch operations. A DELETE loop in JdbcTemplate without a transaction is the same problem.

**Fix:** Add `@Transactional` to `removeDuplicates()` and consider using `deleteAllByIdInBatch()` for efficiency.

---

### WR-11: OtpService.validateOtp — Broken TOTP Validation Logic

**File:** `otp/src/main/java/com/thisjowi/otp/service/OtpService.java:128-135`
**Issue:**
```java
return o.get().getSecret().equals(code);  // Line 132
```
This compares the **stored secret** (which is the TOTP base32 secret key, e.g., `JBSWY3DPEHPK3PXP`) to the **user-provided 6-digit code** (e.g., `123456`). These will never match. The correct implementation would use `totpGenerator.generate(decodedSecret)` and compare the generated code with the user's input. Furthermore, since the secret is encrypted by the JPA converter, the entity would have the plaintext after read — but this logic is still semantically wrong for TOTP validation.

**Why it matters for the migration:** The migration should include proper TOTP validation using a library like `aerogear-otp-java` or manual HMAC-SHA1 computation.

**Fix:** Implement proper TOTP validation:
```java
public boolean validateOtp(Long id, String code) {
    Optional<otp> o = otpRepository.findById(id);
    if (o.isEmpty() || !o.get().getValid() || o.get().getExpiresAt() <= System.currentTimeMillis()) {
        return false;
    }
    String secret = o.get().getSecret();  // Already decrypted by service-layer encryption
    // Use TOTP library to generate expected code and compare
    return TotpValidator.validate(secret, code);
}
```

---

### WR-12: Note Entity @Version — Incompatible with JdbcTemplate Without Manual Handling

**File:** `note/src/main/java/com/thisjowi/note/entity/Note.java:38-39`
**Issue:** The `@Version` field enables optimistic locking managed by JPA. JdbcTemplate does not understand `@Version`. Every `UPDATE` statement must manually include `WHERE version = ?` and increment the version. If this is missed, the optimistic locking guarantee is lost.

**Why it matters for the migration:** The migration must either:
1. Manually handle version checks in all UPDATE SQL: `UPDATE notes SET content = ?, version = version + 1 WHERE id = ? AND version = ?`, and throw `OptimisticLockingFailureException` if `updatedRows == 0`.
2. Remove optimistic locking entirely (not recommended if there are concurrent writes).

**Fix:** Include version in all UPDATE WHERE clauses and check the rows-affected count.

---

### WR-13: System.out/err.println in Production Code (5 instances)

**Files:**
- `otp/src/main/java/com/thisjowi/otp/controller/OtpController.java:77,79,147`
- `note/src/main/java/com/thisjowi/note/controller/NotesController.java:54`
- `note/src/main/java/com/thisjowi/note/kafka/KafkaConsumerService.java:16`

**Issue:** Direct console output bypasses structured logging (SLF4J/Logback). No log levels, no contextual metadata, and unredacted secrets can appear in logs (line 147 prints decryption errors to stderr with the original encrypted text). Line 77 prints masked secrets to stdout — but even a masked partial secret is a log-leak and a PCI/DSS concern.

**Why it matters for the migration:** The migration cleanup phase should include logging hygiene. Replace all `System.out/err` with proper `log.debug/info/warn/error` calls.

**Fix:** Replace with SLF4J:
```java
log.info("Received createOtp request for user {} with secret: {}", userId, masked);
log.error("Error decrypting secret: {}", e.getMessage(), e);
```

---

### WR-14: NotesController Injects NoteRepository Directly (Layer Violation)

**File:** `note/src/main/java/com/thisjowi/note/controller/NotesController.java:26,141`
**Issue:** The controller has both `NoteService` and `NoteRepository` as dependencies. In `deleteNote` (lines 141-150), the controller calls `noteRepository.findById(id)` directly to check ownership — bypassing the service layer. This means the authorization check happens in the controller, and the service's `deleteNoteById` has no ownership validation. If another endpoint or internal call uses `deleteNoteById` directly, ownership is not enforced.

**Why it matters for the migration:** The migration should consolidate all data access through the service layer. With JdbcTemplate, the controller should never have a `JdbcTemplate` or `NoteRepository` bean.

**Fix:** Move ownership check into `NoteService.deleteNoteById`:
```java
@Transactional
public boolean deleteNoteById(Long id, Long userId) {
    Note note = noteRepository.findById(id).orElse(null);
    if (note == null || !note.getUserId().equals(userId)) return false;
    noteRepository.deleteById(id);
    return true;
}
```
Remove `NoteRepository` from controller's constructor.

---

### WR-15: OtpService.createOtp Kafka Event Sent Outside Transaction

**File:** `otp/src/main/java/com/thisjowi/otp/service/OtpService.java:69-84`
**Issue:** The OTP is saved to the database first, then a Kafka event is sent on lines 72-81. There is no transaction wrapping both operations. If the Kafka send fails, the OTP is already persisted (orphaned data). If the DB transaction rolls back after Kafka succeeds, a phantom OTP event is published. The `createOtpForUser` method (lines 100-112) wraps the Kafka send in a try/catch that eats errors — so a failure is silently ignored.

**Why it matters for the migration:** This is a standard distributed-transaction problem. The migration is an opportunity to implement the **transactional outbox pattern**: write the OTP AND an outbox event in the same DB transaction, then a separate process polls the outbox and sends to Kafka.

**Fix:** Implement transactional outbox pattern or use a CDC connector (Debezium). At minimum, add `@Transactional` and structured error handling.

---

## Info

### IN-01: Entity Class `otp` Violates Java Naming Conventions

**File:** `otp/src/main/java/com/thisjowi/otp/entity/otp.java:16`
**Issue:** The class is named `otp` (lowercase) — Java convention requires `PascalCase` for class names: `Otp`. This propagates to method names like `findByUserId` returning `List<otp>` which looks incorrect.

**Fix:** Rename to `Otp`. Update all imports and references.

---

### IN-02: OtpKey Uses Manual Getters/Setters Instead of Lombok

**File:** `otp/src/main/java/com/thisjowi/otp/model/OtpKey.java:24-32`
**Issue:** The `OtpKey` entity uses 24 lines of manual getters/setters while every other entity in the project uses Lombok (`@Getter @Setter`). This is inconsistent and adds boilerplate.

**Fix:** Replace with `@Getter @Setter @NoArgsConstructor` and remove manual methods.

---

### IN-03: OtpKey.userId is String While otp.userId is Long — Type Inconsistency

**Files:**
- `otp/src/main/java/com/thisjowi/otp/model/OtpKey.java:12` — `String userId`
- `otp/src/main/java/com/thisjowi/otp/entity/otp.java:23` — `Long userId`

**Issue:** Two entities in the same service use different types for the same conceptual field (`userId`). This suggests the `OtpKey` entity may be from an earlier design iteration and was never aligned.

**Fix:** Unify to `Long userId` across all entities in the OTP service.

---

### IN-04: Commented/Untranslated Spanish in Encryption.java

**File:** `password/src/main/java/com/thisjowi/password/Utils/Encryption.java:107-109`
**Issue:** The `decrypt` method has Spanish-language Javadoc comments:
```java
/**
 * Desencripta una cadena encriptada con encrypt().
 * Extrae el IV del comienzo del ciphertext.
 */
```
While the `encrypt` method has English Javadoc. Inconsistency reduces maintainability.

**Fix:** Standardize all comments to English.

---

### IN-05: `getNoteByCretedAt` Typo in Method Name

**File:** `note/src/main/java/com/thisjowi/note/service/NoteService.java:143`
**Issue:** Method `getNoteByCretedAt` is missing the 'a' — should be `getNoteByCreatedAt`. Furthermore, this method bypasses encryption — it calls `noteRepository.findByCreatedAt()` and returns the encrypted entity directly (no `decryptNote` call), unlike every other method in the service.

**Fix:** Rename to `getNoteByCreatedAt` and add decryption:
```java
public Optional<Note> getNoteByCreatedAt(LocalDateTime createdAt) {
    return noteRepository.findByCreatedAt(createdAt).map(this::decryptNote);
}
```

---

### IN-06: PasswordRepository.findByName Returns List Instead of Optional

**File:** `password/src/main/java/com/thisjowi/password/Repository/PasswordRepository.java:13`
**Issue:** `findByName` returns `List<Password>`, but `name` appears to be a user-scoped title field (based on the `@JsonProperty("title")` mapping). Combined with the duplicate-prevention logic in `PasswordDeduplicationService`, this suggests name should be unique per user. A `List` return type implies many results are expected, but the code never uses this method.

**Fix:** If this method is unused, remove it. If used, change to `Optional<Password> findByUserIdAndName(Long userId, String name)` for correctness.

---

### IN-07: Password Entity Serialized Directly — Lacks Response DTO

**File:** `password/src/main/java/com/thisjowi/password/Controller/PasswordController.java:53,98,135`
**Issue:** The controller returns `Password` entities directly in HTTP responses (`ResponseEntity.ok(list)`, `ResponseEntity.ok(updated)`, etc.). If any field contains uncleansed data or internal metadata, it leaks to the client. In contrast, the request path uses `PasswordDTO`. The response path should also use a DTO.

**Fix:** Create `PasswordResponseDTO` with only the fields the client needs (`id`, `title`, `website`, `password`, `userId`) and map entities to DTOs before returning.

---

_Reviewed: 2026-05-22T12:00:00Z_
_Reviewer: the agent (gsd-code-reviewer)_
_Depth: deep_
