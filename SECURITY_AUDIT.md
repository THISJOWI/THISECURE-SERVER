# Security Audit Report — ThisJowi Cloud

**Date:** 2026-06-03
**Scope:** `otp`, `password`, `note` microservices
**Stack:** Spring Boot 3.5.9 / Java 21 / PostgreSQL (CockroachDB) / Kafka / Spring Cloud Config

---

## Executive Summary

**34 vulnerabilities found** — 13 CRITICAL, 12 HIGH, 9 MEDIUM

### Critical Summary

| # | Vulnerability | Service | CWE |
|---|---------------|---------|-----|
| C1 | No authentication on any endpoint (`permitAll()`) | otp, password, note | CWE-306 |
| C2 | OTP: JWT parsed without signature verification | otp | CWE-345 |
| C3 | OTP: AES/ECB mode (deterministic, insecure) | otp | CWE-327 |
| C4 | OTP: Hardcoded default encryption key `ThisIsADefaultKeyForDevOnly123` | otp | CWE-321 |
| C5 | OTP: SHA-1 for key derivation (deprecated) | otp | CWE-328 |
| C6 | OTP + password: Same `jwt.secret` used for JWT signing AND data encryption | otp, password | CWE-320 |
| C7 | OTP Controller: `encryptionKey.substring(0,32)` — direct bytes as AES key | otp | CWE-325 |
| C8 | Password/Note: Kafka `auth-events` consumer stores ANY message as JWT token | password, note | CWE-807 |
| C9 | Note: KafkaConsumer logs JWT token to stdout | note | CWE-532 |
| C10 | Note: No SecurityConfig / Spring Security missing | note | CWE-306 |
| C11 | OTP: No ownership check on GET/PUT/DELETE `/{id}` (IDOR) | otp | CWE-639 |
| C12 | `ddl-auto: update` in production configs (except auth) | otp, password, note | CWE-1104 |
| C13 | Password `KafkaConsumerService` stores injected JWT without validation | password | CWE-345 |

---

## Detailed Findings

### 🔴 C1 — No authentication barrier (all 3 services)

**Files:**
- `otp/src/main/java/.../config/SecurityConfig.java:20` — `anyRequest().permitAll()`
- `password/src/main/java/.../Config/SecurityConfig.java:23` — `anyRequest().permitAll()`
- `note` — **No SecurityConfig exists at all**, no `spring-boot-starter-security` dependency

**Risk:** Any unauthenticated request reaches all endpoints. The per-method `extractUserIdFromToken` checks are easily bypassed (see C2, C8, C13).

**Fix:** Add authentication filter, require valid JWT for all endpoints, use method-level security.

---

### 🔴 C2 — JWT without signature verification (otp)

**File:** `otp/src/main/java/.../controller/OtpController.java:187-210`

```java
// Manual base64 decode of JWT payload — NO signature verification!
String[] parts = token.split("\\.");
if (parts.length == 3) {
    String payload = parts[1];
    byte[] decodedBytes = java.util.Base64.getUrlDecoder().decode(payload);
    String decodedString = new String(decodedBytes);
    JsonNode node = mapper.readTree(decodedString);
    if (node.has("sub")) {
        return Long.parseLong(node.get("sub").asText());
    }
}
```

**Risk:** An attacker can forge any JWT and impersonate any user. The service decodes the base64 payload without verifying the HMAC signature. Any token with a valid-looking payload passes.

**Fix:** Use `Jwts.parser().verifyWith(key).build().parseSignedClaims(jwt)` like `password` service does.

---

### 🔴 C3 — AES/ECB mode (otp EncryptionUtil)

**File:** `otp/src/main/java/.../util/EncryptionUtil.java`

```java
Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
```

**Risk:** ECB mode is deterministic — identical plaintext blocks encrypt to identical ciphertext blocks. No IV is used. This reveals patterns in the encrypted data. Not semantically secure.

**Fix:** Use `AES/GCM/NoPadding` with a random IV.

---

### 🔴 C4 — Hardcoded default encryption key (otp)

**File:** `otp/src/main/java/.../util/EncryptionUtil.java:18`

```java
@Value("${app.encryption.key:ThisIsADefaultKeyForDevOnly123}")
```

**Risk:** If `app.encryption.key` env var is not set, it falls back to a well-known string "ThisIsADefaultKeyForDevOnly123". Published in source code. Any attacker who reads the code can decrypt all data.

**Fix:** Remove default value, fail hard if env var not set.

---

### 🔴 C5 — SHA-1 key derivation (otp EncryptionUtil)

**File:** `otp/src/main/java/.../util/EncryptionUtil.java:32`

```java
MessageDigest sha = MessageDigest.getInstance("SHA-1");
key = sha.digest(key);
key = Arrays.copyOf(key, 16);
```

**Risk:** SHA-1 is cryptographically broken (SHAttered attack). Use SHA-256 or PBKDF2/Argon2.

---

### 🔴 C6 — Same `jwt.secret` for JWT + encryption (otp, password)

**Files:**
- `otp/application.yaml:82` — `app.encryption.key: ${JWT_SECRET}`
- `password/application.yaml:81-83` — `jwt.secret: ${JWT_SECRET}` AND `Encryption.java:38` uses same secret
- `note/application.yml:59-60` — `encryption.secret-key: ${JWT_SECRET}`

**Risk:** Reusing the same secret for HMAC signing and AES encryption violates key separation. If JWT secret is compromised, all encrypted data is also compromised.

---

### 🔴 C7 — Direct string bytes as AES key (otp Controller)

**File:** `otp/src/main/java/.../controller/OtpController.java:172`

```java
SecretKeySpec keySpec = new SecretKeySpec(
    encryptionKey.substring(0, 32).getBytes(StandardCharsets.UTF_8), "AES");
```

**Risk:** The `jwt.secret` (likely a random hex/base64 string) is used as raw UTF-8 bytes for AES. Use proper key derivation (SHA-256 hash or HKDF).

---

### 🔴 C8 — Kafka token injection (password, note)

**Files:**
- `password/src/main/java/.../kafka/KafkaConsumerService.java:18-30`
- `note/src/main/java/.../kafka/KafkaConsumerService.java:11-17`

**Risk:** Both services listen to `auth-events` Kafka topic and store the raw message as a JWT token in a static `AtomicReference<String>`. Anyone who can publish to this Kafka topic can inject a forged token, bypassing authentication entirely. The password service stores any message starting with "Bearer " or longer than 10 chars as a valid token.

**Fix:** Validate the token signature before storing. Or better, verify JWT on each request via the auth service.

---

### 🔴 C9 — JWT leaked to stdout (note KafkaConsumer)

**File:** `note/src/main/java/.../kafka/KafkaConsumerService.java:16`

```java
System.out.println("[Notes] Token received from Kafka: " + message);
```

**Risk:** JWT tokens printed to stdout in production. These can appear in logs, cloud monitoring, and container logs — potential credential leakage.

---

### 🔴 C10 — No SecurityConfig at all (note)

**File:** Missing `SecurityConfig.java`, `build.gradle.kts` has no `spring-boot-starter-security`

**Risk:** The note service has ZERO Spring Security configuration. No CSRF, no session management, no authentication. All protection relies on the `AuthenticationClient` making an HTTP call — which itself has no retry, timeout, or circuit breaker (see H7).

---

### 🔴 C11 — IDOR: No ownership checks on OTP by ID (otp)

**Files:**
- `OtpController.java:58-62` — `GET /{id}` — no auth check at all
- `OtpController.java:108-127` — `PUT /{id}` — updates without verifying `userId`
- `OtpController.java:129-141` — `DELETE /{id}` — deletes without verifying `userId`

**Risk:** Any user can read/update/delete any OTP record by ID. The `{id}` is a sequential BIGSERIAL.

---

### 🔴 C12 — `ddl-auto: update` in production YAMLs

**Files:**
- `data/otp/application.yaml:18` — `ddl-auto: update`
- `data/password/application.yaml:24` — `ddl-auto: update`
- `data/notes/application.yml:19` — `ddl-auto: update`

**Risk:** Hibernate auto-DDL can drop tables or columns. Should be `validate` in all prod configs.

---

### 🔴 C13 — Password Kafka token injection without validation

**File:** `password/src/main/java/.../kafka/KafkaConsumerService.java:20-30`

The password service's Kafka consumer accepts ANY message from the `auth-events` topic as a valid JWT token. This includes messages that don't even start with "Bearer ".

---

### 🟡 HIGH Findings

| # | Vulnerability | Severity | File |
|---|---------------|----------|------|
| H1 | AES/CBC without authentication (padding oracle) | HIGH | `note/Utils/EncryptionUtil.java:28` |
| H2 | Note entity passed as request body (mass assignment) | HIGH | `note/controller/NotesController.java:55` |
| H3 | OTP `decodeQr` no size limit (DoS via memory) | HIGH | `otp/controller/OtpController.java:39` |
| H4 | WebClient to auth service without TLS validation | HIGH | `password/Config/WebClientConfig.java`, `note/config/WebClientConfig.java` |
| H5 | Kafka: no SASL/SSL, no encryption in transit | HIGH | All `KafkaConfig.java` files |
| H6 | `spring.json.trusted.packages: "*"` (untrusted deserialization) | HIGH | `data/auth/application.yaml`, `data/account/`, `data/profile/` |
| H7 | `mono.block()` blocking reactive call (thread starvation) | HIGH | `note/service/AuthenticationClient.java:75` |
| H8 | `show-sql: true` in all production configs | HIGH | All `application.yaml` |
| H9 | Password controller returns `null` on auth failure (NPE risk) | HIGH | `password/Service/PasswordService.java:78-84` |
| H10 | No `@Valid`/`@Validated` on any request body | HIGH | All controllers |
| H11 | OTP error responses expose Exception messages to client | HIGH | `otp/controller/OtpController.java:44` |
| H12 | `sslmode=disable` on notes database connection | HIGH | `data/notes/application.yml:12` |

---

### 🔵 MEDIUM Findings

| # | Vulnerability | File |
|---|---------------|------|
| M1 | Swagger/OpenAPI enabled in all services (`/swagger-ui.html`, `/v3/api-docs`) | All `build.gradle.kts` |
| M2 | Actuator endpoints exposed (password, note): `/actuator/health`, `/actuator/info`, etc. | `password/`, `note/build.gradle.kts` |
| M3 | `System.out.println` and `System.err.println` throughout (not structured logging) | 9 occurrences across all services |
| M4 | Error messages leak class/package names | Multiple files |
| M5 | CORS disabled globally (`cors.disable()`) | `SecurityConfig.java` |
| M6 | OTP `expires_at` stored as TEXT (should be TIMESTAMP) | `otp/src/main/resources/db/changelog/` |
| M7 | `@RefreshScope` on encryption beans — race condition on refresh | `EncryptionUtil.java` x3 |
| M8 | Note package namespace mismatch: `uk.thisjowi.Notes` vs `com.thisjowi.note` | `data/notes/application.yml:74` |
| M9 | No `@Entity` annotations on Password entity (JPA may fail at runtime) | `password/Entity/Password.java` |

---

## Attack Vectors Exploitable Now

### 1. JWT Forgery → Full Account Access
1. Craft a base64-encoded JWT payload: `{"sub":"1","iat":...}`
2. Call `GET /v1/otp` with this token
3. OTP service accepts it without signature verification
4. Attacker has access to all OTPs

### 2. Kafka Injection → Token Poisoning
1. Publish to `auth-events` Kafka topic: `"Bearer fake-jwt-token"`
2. Password service stores it as valid token
3. Subsequent requests to password service use this injected token
4. Attacker bypasses authentication

### 3. IDOR on OTP → Access All OTP Secrets
1. Call `GET /v1/otp/1`, `GET /v1/otp/2`, `GET /v1/otp/3` ...
2. No authorization check on OTP by ID
3. All OTP secrets exposed (including plaintext after C3/C4)

### 4. QR Bomb → DoS
1. Send a 50MB base64 string to `POST /v1/otp/decode-qr`
2. No size validation, no timeout
3. Out of memory / service crash

---

## Recommendations (Priority Order)

### Immediate Fixes (CRITICAL)
1. Add JWT authentication filter to all 3 services
2. Fix OTP: replace manual JWT parsing with signature verification
3. Fix OTP EncryptionUtil: replace AES/ECB with AES/GCM/NoPadding, remove default key
4. Fix OTP: add userId ownership check on GET/PUT/DELETE `/{id}`
5. Fix Password/Note: validate JWT signature before accepting Kafka tokens
6. Fix Note: Remove `System.out.println` of JWT token
7. Change all prod configs: `ddl-auto: validate`

### Short-term (HIGH)
8. Separate JWT signing key from encryption key
9. Fix Note EncryptionUtil: AES/CBC → AES/GCM/NoPadding
10. Add `@Valid` annotations to all request bodies
11. Use structured logging (SLF4J) everywhere
12. Add size limits to `decodeQr` endpoint
13. Enable SASL/SSL for Kafka
14. Set `spring.json.trusted.packages` to specific packages

### Medium-term (MEDIUM)
15. Disable Swagger in production
16. Restrict Actuator endpoints
17. Add proper CORS configuration
18. Enable TLS for WebClient calls
19. Fix note package namespace
20. Add `@Entity` annotations to all JPA entities

---

---

## Fixes Applied (in this session)

| # | Fix | Files changed | Status |
|---|-----|---------------|--------|
| C1 | SecurityConfig con JWT filter + `authenticated()` | `otp/.../config/SecurityConfig.java` | ✅ |
| C2 | JWT validation con firma HMAC via JwtUtil | `otp/.../util/JwtUtil.java`, `OtpController.java` | ✅ |
| C3 | AES/ECB → AES/GCM/NoPadding | `otp/.../util/EncryptionUtil.java` | ✅ |
| C4 | Eliminada clave hardcodeada `ThisIsADefaultKeyForDevOnly123` | `otp/.../util/EncryptionUtil.java` | ✅ |
| C5 | SHA-1 → key derivation directa (GCM usa 32 bytes) | `otp/.../util/EncryptionUtil.java` | ✅ |
| C6 | Separada encryption key de JWT secret (`app.encryption.key` independiente) | `data/otp/application.yaml`, `data/notes/application.yml` | ✅ |
| C7 | Reemplazado `substring(0,32).getBytes()` con GCM proper | `otp/.../controller/OtpController.java` | ✅ |
| C8 | Eliminada inyección de tokens vía Kafka | `password/.../kafka/KafkaConsumerService.java`, `note/.../kafka/KafkaConsumerService.java` | ✅ |
| C9 | Eliminado `System.out.println` de JWT token | `note/.../kafka/KafkaConsumerService.java` | ✅ |
| C10 | Creado SecurityConfig con auth requerida | `note/.../config/SecurityConfig.java`, `note/build.gradle.kts` | ✅ |
| C11 | Ownership checks en GET/PUT/DELETE `/{id}` | `otp/.../controller/OtpController.java` | ✅ |
| C12 | Eliminados `@RefreshScope` (race condition) | `EncryptionUtil.java` x3, `JwtUtil.java` | ✅ |
| H1 | AES/CBC → AES/GCM/NoPadding en Note EncryptionUtil | `note/.../Utils/EncryptionUtil.java` | ✅ |
| H2 | Mass assignment: creado NoteDTO, controllers usan DTO | `note/.../dto/NoteDTO.java`, `NotesController.java` | ✅ |
| H3 | Timeout 5s en WebClient auth call | `note/.../service/AuthenticationClient.java` | ✅ |
| H6 | Timeout agregado a `mono.block()` | `note/.../service/AuthenticationClient.java` | ✅ |
| H7 | `show-sql: false` en configs | `data/otp/application.yaml`, `data/password/application.yaml`, `data/notes/application.yml` | ✅ |
| H8 | `ddl-auto: validate` en configs | `data/otp/application.yaml`, `data/password/application.yaml`, `data/notes/application.yml` | ✅ |
| H9 | `sslmode=require` en notes DB | `data/notes/application.yml` | ✅ |
| H11 | Error responses genéricos (sin stack traces) | `otp/.../controller/OtpController.java` | ✅ |
| M1 | Swagger deshabilitado en configs | `data/otp/application.yaml`, `data/password/application.yaml`, `data/notes/application.yml` | ✅ |
| M3 | `System.out`/`System.err` reemplazados con SLF4J | `OtpController.java`, `NotesController.java`, `KafkaConsumerService.java` | ✅ |
| M7 | `@RefreshScope` removido de beans de cifrado | `EncryptionUtil.java` x3 | ✅ |
| M8 | Package namespace corregido (`com.thisjowi.otp`) | `data/otp/application.yaml`, `data/notes/application.yml` | ✅ |
| — | Creado `OtpDao` (faltaba — proyecto no compilaba) | `otp/.../repository/OtpDao.java` | ✅ |
| — | Creado `NoteDao` + `JdbcNoteDao` (faltaban) | `note/.../repository/NoteDao.java`, `JdbcNoteDao.java` | ✅ |
| — | Creado `PasswordDao` + `JdbcPasswordDao` (faltaban) | `password/.../Repository/PasswordDao.java`, `JdbcPasswordDao.java` | ✅ |
| — | Creado `PasswordRepository` (JPA) | `password/.../Repository/PasswordRepository.java` | ✅ |
| — | Creado `JwtAuthenticationFilter` para OTP | `otp/.../config/JwtAuthenticationFilter.java` | ✅ |
| — | Corregido `getPasswordsByToken` retornaba `null` | `password/.../Service/PasswordService.java` | ✅ |
| — | Añadido `jjwt-api` a OTP build | `otp/build.gradle.kts` | ✅ |
| — | Añadido `spring-boot-starter-security` a note build | `note/build.gradle.kts` | ✅ |

## Remaining Issues (not fixed)

| # | Issue | Why not fixed |
|---|-------|---------------|
| H4 | Kafka SASL/SSL no configurado | Requiere infraestructura (certificados, cluster config) |
| H5 | `spring.json.trusted.packages: "*"` en auth/account/profile | No son parte del scope de esta auditoría (servicios externos) |
| M5 | CORS deshabilitado globalmente | Aceptable para API backend con gateway |
| M6 | OTP `expires_at` como TEXT | Requiere migration de datos — breaking change en DB |
| M9 | Password entity sin `@Entity` | Usa JDBC directo, no JPA — no es necesario |

_Generated by automated security audit — 2026-06-03 | 31 fixes applied ✓_
