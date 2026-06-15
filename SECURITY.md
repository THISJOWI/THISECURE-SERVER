# Security Audit Report — ThisJowi Cloud (Go Rewrite)

**Date:** 2026-06-15  
**Scope:** `otp`, `password`, `note` Go microservices + shared `pkg/` library  
**Stack:** Go 1.25 / Gin / pgx / Kafka (segmentio/kafka-go) / PostgreSQL (CockroachDB)  
**ASVS Level:** Target 2 (verified against Level 2 criteria)  
**Methodology:** Full code review of all 22 `.go` source files, config, Dockerfiles, migrations, CI pipeline, and docker-compose.  

---

## Executive Summary

**22 vulnerabilities found** — 5 CRITICAL, 9 HIGH, 6 MEDIUM, 2 LOW

The Go rewrite shows **substantial improvement** over the Spring Boot codebase (34 → 22 findings). Critical architectural fixes include proper JWT signature verification, AES-256-GCM authenticated encryption, and parameterized SQL queries (zero SQL injection). However, an authorization bypass remains in the OTP validate endpoint, and key-management flaws persist.

### What was fixed from Spring Boot:
| Old Issue | Status |
|-----------|--------|
| C1: No authentication (permitAll) | ✅ Fixed — JWTAuth middleware on all v1 routes |
| C2: JWT without signature verification | ✅ Fixed — golang-jwt with HMAC verification |
| C3: AES/ECB mode | ✅ Fixed — AES-256-GCM with random nonce |
| C4: Hardcoded default encryption key | ❌ Regressed — empty string default (new finding) |
| C5: SHA-1 key derivation | ✅ Fixed — GCM uses key directly |
| C6: Same secret for JWT + encryption | ❌ Still present — JWT_SECRET used for Kafka HMAC |
| C7: Direct string bytes as AES key | ⚠️ Partially — still `[]byte(cfgKey)` (no derivation) |
| C8: Kafka token injection | ✅ Fixed — HMAC signature verification on messages |
| C9: JWT leaked to stdout | ✅ Fixed — uses structured `log.Printf` |
| C11: IDOR on OTP GET/PUT/DELETE | ✅ Fixed — ownership checks in service layer |
| C12: ddl-auto:update | ✅ N/A — explicit SQL migrations |
| H1: AES/CBC | ✅ Fixed — AES-256-GCM |
| H2: Mass assignment | ✅ Fixed — request DTOs |
| H3: QR bomb (no size limit) | ❌ Still present (new finding) |
| H5: Kafka plaintext | ❌ Still present |
| H12: sslmode=disable | ❌ Still present (hardcoded) |

### What regressed:
| Area | Detail |
|------|--------|
| Encryption key default | Was `ThisIsADefaultKeyForDevOnly123` (hardcoded but functional). Now empty string `""` — encryption silently fails. |
| Docker USER directive | Backup Dockerfiles had `USER appuser`. Current Dockerfiles run as root. |
| Kafka HMAC key | Still reuses JWT_SECRET — just in a different way (used for Kafka signing instead of encryption) |

---

## Critical Findings

### 🔴 CR-01 — OTP Validate: Missing Ownership Check (IDOR)

| Field | Value |
|-------|-------|
| **Severity** | **CRITICAL** |
| **CWE** | CWE-639: Authorization Bypass Through User-Controlled Key |
| **CVSS 3.1** | **8.1** (AV:N/AC:L/PR:L/UI:N/S:U/C:H/I:H/A:N) |
| **Files** | `services/otp/internal/service/otp_service.go:125-150`, `services/otp/internal/handler/otp_handler.go:129-146` |

**Description:**  
The `Validate` method fetches an OTP by database ID only and compares the provided code — it never verifies that the authenticated user owns the OTP. The handler at `otp_handler.go:140` calls `h.svc.Validate(c.Request.Context(), id, code)` but does **not** pass `userID` from the JWT. The service method `Validate(ctx, id, code)` at line 125 has no `userID` parameter at all.

Compare with `GetByID` (line 48): `if o == nil || o.UserID != userID` — correct ownership check.  
Compare with `GetAll` (line 32): queries `WHERE user_id = $1` — correct scoping.

**Proof of Concept:**
1. Authenticate as User-A (obtain valid JWT)
2. Enumerate `/v1/otp/1/validate?code=123456`, `/v1/otp/2/validate?code=123456`, …
3. IDs are sequential BIGSERIAL — trivially enumerable
4. On each attempt, the service fetches the OTP by ID, decrypts the secret, and compares the code
5. An attacker can brute-force OTP codes against any user's OTP by ID (the code space is only 10^6 for TOTP 6-digit)

The code comparison at line 146 uses `subtle.ConstantTimeCompare` (correct timing-safe comparison) but this only helps after the data is already fetched — the ownership bypass already occurred.

**Recommended Fix:**
```go
// otp_handler.go — Validate handler
func (h *OtpHandler) Validate(c *gin.Context) {
    id, err := strconv.ParseInt(c.Param("id"), 10, 64)
    if err != nil {
        c.JSON(http.StatusBadRequest, gin.H{"error": "invalid id"})
        return
    }
    code := c.Query("code")
    if code == "" {
        c.JSON(http.StatusBadRequest, gin.H{"error": "code is required"})
        return
    }
    userID := middleware.GetUserID(c)   // ← ADD THIS
    valid, err := h.svc.Validate(c.Request.Context(), id, code, userID)  // ← PASS userID
    ...
}

// otp_service.go — Validate method
func (s *OtpService) Validate(ctx context.Context, id int64, code string, userID string) (bool, error) {
    o, err := s.repo.FindByID(ctx, id)
    if err != nil {
        return false, err
    }
    if o == nil || o.UserID != userID {  // ← ADD OWNERSHIP CHECK
        return false, fmt.Errorf("otp not found")
    }
    // ... rest of validation
}
```

---

### 🔴 CR-02 — JWT_SECRET Reused as Kafka HMAC Key (Key Separation Violation)

| Field | Value |
|-------|-------|
| **Severity** | **CRITICAL** |
| **CWE** | CWE-1051: Initialization with Hard-Coded Network Resource Configuration Data |
| **CVSS 3.1** | **7.4** (AV:N/AC:H/PR:N/UI:N/S:U/C:H/I:H/A:N) |
| **Files** | `services/otp/cmd/server/main.go:34-36`, `services/password/cmd/server/main.go:33-35`, `services/note/cmd/server/main.go:33-35` |

**Description:**  
The same `JWT_SECRET` environment variable is used for two cryptographically distinct purposes:
1. **JWT token validation** — `mid.JWTAuth(jwtSecret)` at line 63/48/47
2. **Kafka message HMAC signing** — `kafka.NewSigner(jwtSecret)` at line 36/35/35

The HMAC signer uses `crypto/hmac.New(sha256.New, key)` — same HMAC-SHA256 as JWT HS256. If an attacker compromises one key usage, both systems are compromised simultaneously. Specifically:
- If JWT_SECRET is leaked (log files, memory dump, env var exposure), all Kafka messages can be forged
- If a Kafka HMAC oracle exists (timing side-channel in consumer verification), the JWT secret can be recovered

**Recommended Fix:**
```go
// Add separate env var for Kafka HMAC signing
// In config.go:
KafkaHMACKey string

func Load() Config {
    return Config{
        JWTSecret:     requireEnv("JWT_SECRET"),
        EncryptionKey: requireEnv("ENCRYPTION_KEY"),
        KafkaHMACKey:  requireEnv("KAFKA_HMAC_KEY"),  // ← separate key
        ...
    }
}

// In main.go:
jwtSecret := []byte(cfg.JWTSecret)
hmacKey := []byte(cfg.KafkaHMACKey)

signer := kafka.NewSigner(hmacKey)          // ← uses dedicated key
v1 := r.Group("/v1/otp", mid.JWTAuth(jwtSecret))  // ← uses JWT key
```

**Fallback strategy (if immediate env var change not possible):**
Derive separate keys using HKDF:
```go
jwtSecret := []byte(cfg.JWTSecret)
hmacKey := crypto.HKDF(sha256.New, jwtSecret, []byte("kafka-hmac-key"), nil) // 32 bytes
```

---

### 🔴 CR-03 — JWT_SECRET Defaults to Empty String (Token Forgery)

| Field | Value |
|-------|-------|
| **Severity** | **CRITICAL** |
| **CWE** | CWE-1188: Initialization of a Resource with an Insecure Default |
| **CVSS 3.1** | **9.8** (AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H) |
| **Files** | `services/otp/internal/config/config.go:19`, `services/password/internal/config/config.go:19`, `services/note/internal/config/config.go:19` |

**Description:**  
All three services default `JWT_SECRET` to empty string `""`:
```go
JWTSecret: getEnv("JWT_SECRET", ""),
```

When `JWT_SECRET` is not set:
- `jwtSecret` becomes `[]byte("")` (zero-length, non-nil slice)
- `golang-jwt/jwt/v5` uses this empty byte slice as the HMAC signing key
- `crypto/hmac.New(sha256.New, []byte{})` is valid in Go — it creates an HMAC with a zero-length key
- An attacker can forge tokens using HS256 with an empty key and the service will accept them

The JWTAuth middleware has an algorithm check: `t.Method.(*jwt.SigningMethodHMAC)` — this blocks `alg: none` attacks, but an HS256 token signed with an empty key passes because HS256 IS an HMAC method. The `jwt.Valid` flag will be `true` when Parse successfully verifies the signature.

**Proof of Concept:**
```go
// Forge a token with empty secret key
token := jwt.NewWithClaims(jwt.SigningMethodHS256, jwt.MapClaims{
    "sub": "victim-user-id",
    "exp": time.Now().Add(time.Hour).Unix(),
})
forgedToken, _ := token.SignedString([]byte(""))
// This forgedToken passes validation in any service with JWT_SECRET="" 
```

**Recommended Fix:**
```go
func Load() Config {
    jwtSecret := os.Getenv("JWT_SECRET")
    if jwtSecret == "" {
        log.Fatal("JWT_SECRET environment variable is required but not set")
    }
    if len(jwtSecret) < 32 {
        log.Fatal("JWT_SECRET must be at least 32 characters")
    }
    return Config{
        JWTSecret: jwtSecret,
        ...
    }
}
```

---

### 🔴 CR-04 — ENCRYPTION_KEY Defaults to Empty String (Silent Encryption Failure)

| Field | Value |
|-------|-------|
| **Severity** | **CRITICAL** |
| **CWE** | CWE-311: Missing Encryption of Sensitive Data |
| **CVSS 3.1** | **7.5** (AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:N/A:N) |
| **Files** | All 3 `config.go`, `otp_service.go:152-160`, `password_service.go:134-142`, `note_service.go:169-179`, `crypto.go:14-29` |

**Description:**  
All three services default `ENCRYPTION_KEY` to empty string:
```go
EncryptionKey: getEnv("ENCRYPTION_KEY", ""),
```

When `ENCRYPTION_KEY` is not set:
- `encKey` becomes `[]byte("")` — **not nil**, but a zero-length slice
- The nil check `s.encKey == nil` at the top of `encryptSecret`/`encryptNote`/`encrypt()` evaluates to **FALSE** — the encryption path is entered
- `crypto.Encrypt()` → `aes.NewCipher([]byte{})` → ERROR: `crypto/aes: invalid key size 0`
- The error is **silently swallowed**: `if err == nil { o.Secret = enc }` — no `else` clause
- Result: data is stored in **plaintext** in the database with no warning

This is worse than the Spring Boot version which had a hardcoded (but functional) default key. The Go version **silently disables encryption**, meaning:
- OTP secrets stored in plaintext
- Password entries stored in plaintext  
- Note content stored in plaintext

A database compromise reveals all secrets in clear text.

**Recommended Fix:**
```go
func Load() Config {
    encKey := os.Getenv("ENCRYPTION_KEY")
    if encKey == "" {
        log.Fatal("ENCRYPTION_KEY environment variable is required but not set")
    }
    key := []byte(encKey)
    if err := crypto.ValidateKey(key); err != nil {
        log.Fatalf("ENCRYPTION_KEY invalid: %v", err)
    }
    return Config{
        EncryptionKey: encKey,
        ...
    }
}
```

Additionally, in the encrypt/decrypt service methods — **never silently swallow crypto errors**:
```go
func (s *OtpService) encryptSecret(o *model.Otp) {
    if o.Secret == "" {
        return
    }
    enc, err := crypto.Encrypt([]byte(o.Secret), s.encKey)
    if err != nil {
        log.Printf("CRITICAL: encryption failed for OTP %d: %v", o.ID, err)
        return  // or: panic in development, return error in production
    }
    o.Secret = enc
}
```

---

### 🔴 CR-05 — Crypto Errors Silently Ignored, Data Returned in Plaintext

| Field | Value |
|-------|-------|
| **Severity** | **CRITICAL** |
| **CWE** | CWE-391: Unchecked Error Condition |
| **CVSS 3.1** | **6.5** (AV:N/AC:L/PR:L/UI:N/S:U/C:H/I:N/A:N) |
| **Files** | `otp_service.go:152-170`, `password_service.go:134-152`, `note_service.go:169-191` |

**Description:**  
Every encrypt/decrypt method in all three services silently ignores crypto errors:

```go
// pattern in otp_service.go
func (s *OtpService) encryptSecret(o *model.Otp) {
    if s.encKey == nil || o.Secret == "" { return }
    enc, err := crypto.Encrypt([]byte(o.Secret), s.encKey)
    if err == nil {        // ← no else: error is swallowed
        o.Secret = enc
    }
}
```

This means ANY encryption failure (wrong key length, GCM failure, encoding error) results in:
1. **On encrypt**: plaintext secret stored in database
2. **On decrypt**: previously encrypted data returned as garbage or old ciphertext returned to client (since `o.Secret` is not updated when decryption fails)

This is not just about the empty key default (CR-04). Even with a valid key, transient errors like corrupted ciphertext, encoding failures, or GCM authentication failures will leave data exposed.

**Proof of Concept:**
An attacker with database access who corrupts the encrypted `secret` column can cause future `decryptSecret` calls to fail, after which:
- The corrupted (encrypted) value is returned as-is to the API consumer
- Or in the case of encrypt failure on create/update, the plaintext is stored directly

**Recommended Fix:**  
All encrypt/decrypt helpers must **log at ERROR level** and either return an error up the call stack or, at minimum, not silently preserve the uncleaned value. See CR-04 fix for code example.

---

## High Findings

### 🟡 HI-01 — No Request Body Size Limits (DoS via DecodeQR / Import)

| Field | Value |
|-------|-------|
| **Severity** | **HIGH** |
| **CWE** | CWE-770: Allocation of Resources Without Limits or Throttling |
| **CVSS 3.1** | **7.5** (AV:N/AC:L/PR:N/UI:N/S:U/C:N/I:N/A:H) |
| **Files** | `otp_handler.go:32-46`, `qr_service.go:20-41`, `note_handler.go:47-60`, `password_handler.go:114-127`, all `main.go` `http.Server` configs |

**Description:**  
Go's `http.Server` has no default request body size limit. Gin's `ShouldBindJSON` reads the entire body into memory. Attack vectors:

1. **QR Bomb (OTP service):** POST to `/v1/otp/decode-qr` with a 500MB base64 string. The handler decodes it with `base64.StdEncoding.DecodeString` into memory (line 21), then passes to `image.Decode` which allocates more buffers. The QR library has no size guard.

2. **Import Bomb (Note/Password):** POST to `/v1/notes/import` or `/v1/passwords/import` with a JSON array of 1 million entries. Each entry triggers a database operation in a loop with no batch limit.

3. **General JSON Bomb:** `ShouldBindJSON` on any POST/PUT reads the full body. No Content-Length validation.

Only the Kafka consumer has `MaxBytes: 10e6` (10MB) configured.

**Recommended Fix:**
```go
// In main.go, configure the HTTP server with limits:
srv := &http.Server{
    Addr:           ":" + cfg.Port,
    Handler:        r,
    MaxHeaderBytes: 1 << 20,  // 1 MB
    ReadTimeout:    30 * time.Second,
    WriteTimeout:   30 * time.Second,
    IdleTimeout:    120 * time.Second,
}

// Add body size limit middleware:
func BodyLimit(maxBytes int64) gin.HandlerFunc {
    return func(c *gin.Context) {
        c.Request.Body = http.MaxBytesReader(c.Writer, c.Request.Body, maxBytes)
        c.Next()
    }
}

// Apply selectively:
r.Use(BodyLimit(1 << 20)) // 1MB global limit
```

Also add per-endpoint limits:
```go
// In OTP handler:
func (h *OtpHandler) DecodeQR(c *gin.Context) {
    c.Request.Body = http.MaxBytesReader(c.Writer, c.Request.Body, 100<<20) // 100MB for QR images
    // ... rest
}
```

---

### 🟡 HI-02 — Database sslmode=disable Hardcoded in All Configs

| Field | Value |
|-------|-------|
| **Severity** | **HIGH** |
| **CWE** | CWE-319: Cleartext Transmission of Sensitive Information |
| **CVSS 3.1** | **6.5** (AV:A/AC:L/PR:N/UI:N/S:U/C:H/I:N/A:N) |
| **Files** | `otp/config/config.go:27`, `password/config/config.go:27`, `note/config/config.go:27` |

**Description:**  
All database connection strings hardcode `sslmode=disable`:
```go
cfg.DatabaseURL = fmt.Sprintf("postgres://%s:%s@%s:%s/otp?sslmode=disable", ...)
```

There is no environment variable to control SSL mode. This means:
- Database credentials sent in plaintext over the network
- Query results (including decrypted secrets) sent in plaintext
- Man-in-the-middle on the database network can intercept all data

**Recommended Fix:**
```go
dbSSLMode := getEnv("DB_SSLMODE", "require") // or "verify-full"
cfg.DatabaseURL = fmt.Sprintf("postgres://%s:%s@%s:%s/otp?sslmode=%s", 
    dbUser, dbPass, dbHost, dbPort, dbSSLMode)
```

Also add `DB_SSLCERT`, `DB_SSLKEY`, `DB_SSLROOTCERT` env vars for mTLS scenarios.

---

### 🟡 HI-03 — Rate Limiter In-Memory Only (Bypassable Behind Load Balancer)

| Field | Value |
|-------|-------|
| **Severity** | **HIGH** |
| **CWE** | CWE-799: Improper Control of Interaction Frequency |
| **CVSS 3.1** | **5.9** (AV:N/AC:H/PR:N/UI:N/S:U/C:N/I:N/A:H) |
| **Files** | `pkg/middleware/ratelimit.go:11-66`, all `main.go` files |

**Description:**  
The rate limiter stores visitor state in a `map[string]*visitor` protected by a `sync.Mutex` — purely in-process memory. In a multi-instance deployment behind a load balancer (as the K8s deployment configs suggest), each instance has its own independent rate limit counter. An attacker can:
- Distribute requests across instances (round-robin LB)
- Effectively multiply the rate limit by the number of instances
- With 3 instances and rate=10/burst=20: effective burst = 60

The map also grows unbounded — never evicts expired entries.

**Recommended Fix:**
Short-term: Use Redis-based rate limiting (shared state across instances):
```go
// redis_ratelimit.go
func NewRedisRateLimiter(rdb *redis.Client, rate, burst int, window time.Duration) gin.HandlerFunc {
    return func(c *gin.Context) {
        ip := c.ClientIP()
        key := fmt.Sprintf("ratelimit:%s", ip)
        // Use Redis Sorted Set or INCR with TTL
        ...
    }
}
```

Long-term: Deploy an API gateway (Nginx, Kong, Traefik) with rate limiting at the edge.

---

### 🟡 HI-04 — Kafka Plaintext Only (No SASL/TLS)

| Field | Value |
|-------|-------|
| **Severity** | **HIGH** |
| **CWE** | CWE-319: Cleartext Transmission of Sensitive Information |
| **CVSS 3.1** | **6.5** (AV:A/AC:L/PR:N/UI:N/S:U/C:H/I:N/A:N) |
| **Files** | `pkg/kafka/producer.go:17-27`, `pkg/kafka/consumer.go:20-32`, `docker-compose.yaml:66` |

**Description:**  
Kafka producers and consumers use `kafka.TCP(brokers...)` — no TLS configuration. The `docker-compose.yaml` confirms `PLAINTEXT://localhost:9092`. Kafka messages traverse the network unencrypted.

While Kafka message payloads are HMAC-signed for integrity, they are NOT encrypted. The `SyncEvent` payloads contain metadata (`userId`, `issuer`, `title`, `website`, `username`) that could be sensitive. More critically, the `OtpCreatedEvent` contains `email` addresses.

**Recommended Fix:**
```go
// producer.go
func NewProducer(brokers []string, topic string, signer *Signer, tlsConfig *tls.Config, saslMechanism sasl.Mechanism) *Producer {
    dialer := &kafka.Dialer{
        TLS:           tlsConfig,
        SASLMechanism: saslMechanism,
        Timeout:       10 * time.Second,
    }
    return &Producer{
        writer: &kafka.Writer{
            Addr:     kafka.TCP(brokers...),
            Topic:    topic,
            Balancer: &kafka.Hash{},
            Transport: &kafka.Transport{
                Dial: dialer.DialContext,
            },
        },
        signer: signer,
    }
}
```

---

### 🟡 HI-05 — Dockerfiles Run as Root (No USER Directive)

| Field | Value |
|-------|-------|
| **Severity** | **HIGH** |
| **CWE** | CWE-250: Execution with Unnecessary Privileges |
| **CVSS 3.1** | **7.8** (AV:L/AC:L/PR:N/UI:R/S:C/C:H/I:H/A:H) |
| **Files** | `services/otp/Dockerfile`, `services/password/Dockerfile`, `services/note/Dockerfile` |

**Description:**  
All three Dockerfiles use `FROM alpine:3.19` without a `USER` directive — the container runs as root. If a vulnerability allows code execution (e.g., RCE via a dependency bug), the attacker gets root access inside the container. The backup Dockerfiles at `backup/otp/Dockerfile`, `backup/password/Dockerfile`, `backup/notes/Dockerfile` all use `USER appuser` — this is a **regression** in the Go rewrite.

**Recommended Fix:**
```dockerfile
FROM golang:1.25-alpine AS builder
# ... build steps ...

FROM alpine:3.19
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
COPY --from=builder /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/
COPY --from=builder /server /server
USER appuser
EXPOSE 8085
HEALTHCHECK --interval=30s --timeout=3s CMD wget --no-verbose --tries=1 --spider http://localhost:8085/health || exit 1
CMD ["/server"]
```

---

### 🟡 HI-06 — Ownership Violations Return 500 Instead of 403

| Field | Value |
|-------|-------|
| **Severity** | **HIGH** |
| **CWE** | CWE-209: Generation of Error Message Containing Sensitive Information |
| **CVSS 3.1** | **5.3** (AV:N/AC:L/PR:N/UI:N/S:U/C:L/I:N/A:N) |
| **Files** | `otp_service.go:89`, `otp_service.go:116`, `password_service.go:87`, `password_service.go:108`, `note_service.go:124`, `note_service.go:143` |

**Description:**  
When ownership checks fail during Update/Delete, the services return `fmt.Errorf("otp not found or not owned")` which the handlers bubble up as HTTP 500 (Internal Server Error). This creates distinguishable error responses:
- GET /:id → 404 (not found) — confirms the ID doesn't exist
- PUT/DELETE /:id → 500 (owned by another user) — confirms the ID exists but belongs to someone else
- GET /:id → 200 (success) — confirms the ID belongs to you

An attacker can enumerate database IDs and determine which exist and who they belong to.

**Recommended Fix:**
In service layer, treat "not yours" identically to "not found":
```go
// otp_service.go Update
func (s *OtpService) Update(ctx context.Context, id int64, req model.CreateOtpRequest, userID string) (*model.Otp, error) {
    existing, err := s.repo.FindByID(ctx, id)
    if err != nil {
        return nil, err
    }
    if existing == nil || existing.UserID != userID {
        return nil, fmt.Errorf("otp not found")  // same error as not existing
    }
    ...
}
```

And in handler, return 404 for "not found" (whether it doesn't exist or isn't yours):
```go
// otp_handler.go Update handler
if err != nil {
    if err.Error() == "otp not found" {
        c.JSON(http.StatusNotFound, gin.H{"error": "otp not found"})
        return
    }
    c.JSON(http.StatusInternalServerError, gin.H{"error": "internal error"})
    return
}
```

Better approach — define sentinel errors:
```go
var ErrNotFound = errors.New("resource not found")

// In service:
if existing == nil || existing.UserID != userID {
    return nil, ErrNotFound
}
```

---

### 🟡 HI-07 — Kafka Consumer: Failed Messages Not Dead-Lettered

| Field | Value |
|-------|-------|
| **Severity** | **HIGH** (downgraded from CRITICAL; the fix for C8 prevents token injection, but DLQ gap remains) |
| **CWE** | CWE-754: Improper Check for Unusual or Exceptional Conditions |
| **CVSS 3.1** | **5.9** (AV:N/AC:H/PR:N/UI:N/S:U/C:N/I:N/A:H) |
| **Files** | `pkg/kafka/consumer.go:34-52` |

**Description:**  
The consumer's `Run()` loop at lines 40-51 handles failures with `continue`:
```go
if err := c.verifySignature(msg); err != nil {
    log.Printf("WARN: signature verification failed: %v", err)
    continue  // message offset not committed, but consumer moves past it
}
if err := c.handler(ctx, string(msg.Key), msg.Value); err != nil {
    log.Printf("ERROR: handler failed: %v", err)
    continue  // same issue
}
```

Messages that fail verification or handling are NOT committed (`CommitMessages` is only called after success). However, `FetchMessage` on the next iteration advances to the next message offset. This means:
- Failed messages are effectively skipped in the current session
- On consumer restart, uncommitted messages are re-delivered, potentially causing repeated processing failures
- No dead-letter topic exists to quarantine problematic messages for later inspection

**Recommended Fix:**
```go
func (c *Consumer) Run(ctx context.Context) error {
    for {
        msg, err := c.reader.FetchMessage(ctx)
        if err != nil {
            return fmt.Errorf("fetch: %w", err)
        }
        
        if err := c.verifySignature(msg); err != nil {
            log.Printf("WARN: signature verification failed for offset %d: %v", msg.Offset, err)
            // Option: publish to dead-letter topic
            if err2 := c.publishToDLQ(ctx, msg, "signature-verification-failed"); err2 != nil {
                log.Printf("ERROR: failed to publish to DLQ: %v", err2)
            }
            // Commit to avoid infinite reprocessing on restart
            if err := c.reader.CommitMessages(ctx, msg); err != nil {
                return fmt.Errorf("commit failed message: %w", err)
            }
            continue
        }
        
        if err := c.handler(ctx, string(msg.Key), msg.Value); err != nil {
            log.Printf("ERROR: handler failed for offset %d: %v", msg.Offset, err)
            // Retry with backoff, then DLQ on exhaustion
            if err2 := c.publishToDLQ(ctx, msg, "handler-failed"); err2 != nil {
                log.Printf("ERROR: failed to publish to DLQ: %v", err2)
            }
            if err := c.reader.CommitMessages(ctx, msg); err != nil {
                return fmt.Errorf("commit failed message: %w", err)
            }
            continue
        }
        
        if err := c.reader.CommitMessages(ctx, msg); err != nil {
            return fmt.Errorf("commit: %w", err)
        }
    }
}
```

---

### 🟡 HI-08 — OTP Service DecodeQR: No Input Limits (Pixie Dust / Image Bomb)

| Field | Value |
|-------|-------|
| **Severity** | **HIGH** |
| **CWE** | CWE-400: Uncontrolled Resource Consumption |
| **CVSS 3.1** | **7.5** (AV:N/AC:L/PR:N/UI:N/S:U/C:N/I:N/A:H) |
| **Files** | `otp_handler.go:32-46`, `qr_service.go:20-41` |

**Description:**  
The `DecodeQR` handler accepts a base64-encoded image in a JSON body and passes the entire decoded byte slice to `image.Decode`. The Go standard library `image.Decode` attempts to determine the format by reading the header — an attacker can send a "pixie dust" (decompression bomb) image that decompresses to gigabytes from a relatively small compressed payload.

Additionally, `base64.StdEncoding.DecodeString` allocates ~75% of the input size (4 base64 chars → 3 bytes). A 500MB base64 string becomes ~375MB in memory before even reaching `image.Decode`.

**Recommended Fix:**
```go
func (s *QrService) DecodeQR(base64Image string) (string, error) {
    // Limit input size BEFORE decoding (base64 size)
    if len(base64Image) > 10*1024*1024 { // 10MB base64, ~7.5MB decoded
        return "", fmt.Errorf("image too large")
    }
    
    data, err := base64.StdEncoding.DecodeString(base64Image)
    if err != nil {
        return "", err
    }
    
    // Limit decoded size
    if len(data) > 7*1024*1024 { // double-check decoded size
        return "", fmt.Errorf("image too large after decode")
    }
    
    img, _, err := image.Decode(bytes.NewReader(data))
    // ...
}
```

---

### 🟡 HI-09 — Error Messages Leak Internal Details (Info Disclosure)

| Field | Value |
|-------|-------|
| **Severity** | **HIGH** |
| **CWE** | CWE-209: Generation of Error Message Containing Sensitive Information |
| **CVSS 3.1** | **4.3** (AV:N/AC:L/PR:L/UI:N/S:U/C:L/I:N/A:N) |
| **Files** | All handler files, `pkg/middleware/auth.go:27` |

**Description:**  
Error responses throughout all handlers bubble raw Go error messages to HTTP responses:

```go
c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
```

Specific leaks:
- **JWT errors** (`auth.go:27`): `"invalid token: token is expired by 5m"` — reveals token expiry timing
- **DB errors**: `"query: ERROR: relation 'otp' does not exist"` — reveals table names
- **DB errors**: `"insert: ERROR: duplicate key value violates unique constraint"` — reveals schema
- **Crypto errors**: `"invalid ciphertext"` — reveals encryption scheme details
- **Binding errors**: `"Key: 'CreateOtpRequest.Type' Error:Field validation for 'Type' failed on the 'required' tag"` — reveals struct field names

**Recommended Fix:**
```go
// Add error wrapping in service/handler:
func (h *OtpHandler) GetAll(c *gin.Context) {
    userID := middleware.GetUserID(c)
    otps, err := h.svc.GetAll(c.Request.Context(), userID)
    if err != nil {
        log.Printf("ERROR: GetAll failed for user %s: %v", userID, err)
        c.JSON(http.StatusInternalServerError, gin.H{"error": "internal server error"})
        return
    }
    // ...
}
```

Never return `err.Error()` to clients in production. Log the full error server-side; return generic messages.

---

## Medium Findings

### 🔵 ME-01 — Sequential BIGSERIAL IDs Enable Enumeration

| Field | Value |
|-------|-------|
| **Severity** | **MEDIUM** |
| **CWE** | CWE-1295: Debug Messages Revealing Unnecessary Information |
| **CVSS 3.1** | **5.3** (AV:N/AC:L/PR:N/UI:N/S:U/C:L/I:N/A:N) |
| **Files** | All migration files: `001_create_otp.up.sql:2`, `001_create_passwords.up.sql:2`, `001_create_notes.up.sql:2` |

**Description:**  
All tables use `BIGSERIAL` for primary keys — auto-incrementing IDs. This enables:
- Trivial enumeration of all records (try IDs 1, 2, 3, …)
- Inference of record creation rate (ID delta over time)
- Inference of total record count (max ID)

This compounds the IDOR vulnerability (CR-01) by making the ID space trivially enumerable.

**Recommended Fix:**
```sql
-- Use UUIDv7 for primary keys (time-ordered, index-friendly)
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

CREATE TABLE IF NOT EXISTS otp (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v7(),
    ...
);
```

Or use a segmented key approach with a random component:
```sql
id TEXT PRIMARY KEY DEFAULT encode(gen_random_bytes(8), 'hex')
```

---

### 🔵 ME-02 — expires_at Stored as TEXT Instead of TIMESTAMP

| Field | Value |
|-------|-------|
| **Severity** | **MEDIUM** |
| **CWE** | CWE-1104: Use of Unmaintained Third-Party Components |
| **CVSS 3.1** | **4.0** (AV:N/AC:H/PR:N/UI:N/S:U/C:N/I:L/A:L) |
| **Files** | `otp/migrations/001_create_otp.up.sql:6`, `otp/model/otp.go:8`, `otp_service.go:55` |

**Description:**  
The `expires_at` column is `TEXT NOT NULL` and stores Unix milliseconds as a string (`strconv.FormatInt(time.Now().UnixMilli()+..., 10)`). This means:
- No timezone awareness
- No database-level date comparisons (must parse strings in application code)
- Error-prone: if a non-numeric string enters the column, `strconv.ParseInt` will fail at runtime
- Cannot use PostgreSQL date functions for queries or indexing

**Recommended Fix:**
```sql
-- Migration
ALTER TABLE otp ALTER COLUMN expires_at TYPE TIMESTAMPTZ 
    USING to_timestamp(expires_at::bigint / 1000.0);
```

```go
// Model
ExpiresAt time.Time `json:"expiresAt" db:"expires_at"`
```

---

### 🔵 ME-03 — No CORS Configuration

| Field | Value |
|-------|-------|
| **Severity** | **MEDIUM** |
| **CWE** | CWE-942: Permissive Cross-domain Policy with Untrusted Domains |
| **CVSS 3.1** | **5.4** (AV:N/AC:L/PR:N/UI:R/S:U/C:L/I:L/A:N) |
| **Files** | All `main.go` files (absence of CORS middleware) |

**Description:**  
None of the services configure CORS headers. Gin's default behavior: no `Access-Control-Allow-*` headers are set. This means:
- Browser-based clients cannot make cross-origin requests (functional issue)
- If a web frontend needs to call these APIs, CORS must be configured

If this is a pure API backend consumed by native apps, this is acceptable. If a web frontend is planned, this becomes a MEDIUM finding because CORS must be configured restrictively.

**Recommended Fix (if web frontend):**
```go
import "github.com/gin-contrib/cors"

r.Use(cors.New(cors.Config{
    AllowOrigins:     []string{"https://app.thisjowi.com"},
    AllowMethods:     []string{"GET", "POST", "PUT", "DELETE", "OPTIONS"},
    AllowHeaders:     []string{"Origin", "Content-Type", "Authorization"},
    AllowCredentials: true,
    MaxAge:           12 * time.Hour,
}))
```

---

### 🔵 ME-04 — Unused FindByTitle() Lacks User Filtering (Dead Code Risk)

| Field | Value |
|-------|-------|
| **Severity** | **MEDIUM** |
| **CWE** | CWE-639: Authorization Bypass Through User-Controlled Key |
| **CVSS 3.1** | **6.5** (AV:N/AC:L/PR:L/UI:N/S:U/C:H/I:N/A:N) |
| **Files** | `note_repo.go:107-121` |

**Description:**  
`FindByTitle(ctx, title)` at line 107 queries `SELECT ... FROM notes WHERE title = $1` — **no user_id filter**. This method is currently **not called** from the service or handler layer (the service uses `FindByTitleAndUser` instead). However, it is exported and exists in the repository — any future developer calling it would bypass ownership checks entirely, allowing any authenticated user to read any note by title.

**Recommended Fix:**
Either remove the method or add the user_id parameter:
```go
func (r *NoteRepo) FindByTitle(ctx context.Context, title, userID string) (*model.Note, error) {
    rows, err := r.pool.Query(ctx, 
        `SELECT id, content, title, created_at, user_id, version FROM notes WHERE title = $1 AND user_id = $2`, 
        title, userID)
    // ...
}
```

---

### 🔵 ME-05 — Docker HEALTHCHECK Spiders Localhost (May Fail in Some Networks)

| Field | Value |
|-------|-------|
| **Severity** | **MEDIUM** |
| **CWE** | CWE-1071: Uncontrolled Recursion |
| **CVSS 3.1** | **2.3** (AV:L/AC:H/PR:H/UI:N/S:U/C:N/I:N/A:L) |
| **Files** | All three `Dockerfile` files |

**Description:**  
```
HEALTHCHECK --interval=30s --timeout=3s CMD wget --no-verbose --tries=1 --spider http://localhost:8085/health || exit 1
```

The healthcheck uses `wget` which is available in the base `alpine:3.19` image, but:
- `wget` adds unnecessary attack surface to the runtime image
- If the application binds only to a specific interface (not localhost), the healthcheck fails

**Recommended Fix:**
Use a Go-based healthcheck or install a minimal tool:
```dockerfile
FROM golang:1.25-alpine AS builder
# ... build healthcheck binary ...

# Or use a static Go binary:
RUN CGO_ENABLED=0 go build -o /healthcheck ./cmd/healthcheck/

FROM scratch
COPY --from=builder /server /server
COPY --from=builder /healthcheck /healthcheck
HEALTHCHECK --interval=30s --timeout=3s CMD ["/healthcheck"]
```

---

### 🔵 ME-06 — Note FindByCreatedAt Lacks User Filtering (Dead Code Risk)

| Field | Value |
|-------|-------|
| **Severity** | **MEDIUM** |
| **CWE** | CWE-639: Authorization Bypass Through User-Controlled Key |
| **CVSS 3.1** | **4.3** (AV:N/AC:L/PR:L/UI:N/S:U/C:L/I:N/A:N) |
| **Files** | `note_repo.go:123-130` |

**Description:**  
`FindByCreatedAt(ctx, t)` at line 123 queries `WHERE created_at = $1` — no user_id filter. Currently uncalled from any service or handler, but exported. Same risk as ME-04.

---

## Low Findings

### 🔵 LO-01 — Database Credentials in Default Values

| Field | Value |
|-------|-------|
| **Severity** | **LOW** |
| **CWE** | CWE-798: Use of Hard-coded Credentials |
| **Files** | All `config.go` files |

**Description:**  
Default database credentials are hardcoded in every config:
```go
dbUser := getEnv("DB_USERNAME", "postgres")
dbPass := getEnv("DB_PASSWORD", "postgres")
```

While these are default PostgreSQL credentials, if an environment misconfiguration occurs, the services connect with well-known credentials.

**Recommended Fix:**
Remove defaults for credentials — fail hard:
```go
dbUser := os.Getenv("DB_USERNAME")
if dbUser == "" {
    log.Fatal("DB_USERNAME is required")
}
```

---

### 🔵 LO-02 — Missing Security Headers (Content-Security-Policy, X-Content-Type-Options, etc.)

| Field | Value |
|-------|-------|
| **Severity** | **LOW** |
| **CWE** | CWE-693: Protection Mechanism Failure |
| **Files** | All `main.go` (absence of security header middleware) |

**Description:**  
No security headers are set on HTTP responses:
- `X-Content-Type-Options: nosniff`
- `X-Frame-Options: DENY`
- `Content-Security-Policy`
- `Strict-Transport-Security` (should be set by reverse proxy anyway)

For an API backend, these headers are less critical than for a web UI, but still recommended.

**Recommended Fix:**
```go
r.Use(func(c *gin.Context) {
    c.Header("X-Content-Type-Options", "nosniff")
    c.Header("X-Frame-Options", "DENY")
    c.Next()
})
```

---

## Verified Safe Patterns

### ✅ JWT Signature Verification (Fixed from C2)
`pkg/jwt/jwt.go:9-30` — Uses `golang-jwt/jwt/v5` with proper `jwt.Parse` using HMAC signature verification. Algorithm confusion prevented via type assertion `t.Method.(*jwt.SigningMethodHMAC)`.

### ✅ AES-256-GCM Authenticated Encryption (Fixed from C3)
`pkg/crypto/crypto.go:14-50` — AES-GCM with random nonce from `crypto/rand`, base64 encoding. Correctly handles nonce prepended to ciphertext. Replaces the insecure AES/ECB from Spring Boot.

### ✅ Kafka Message HMAC Integrity (Fixed from C8)
`pkg/kafka/hmac.go` — HMAC-SHA256 signing of all Kafka messages with signature in `X-Signature` header. Consumer verifies before processing. Uses `hmac.Equal` for constant-time comparison. Replaces the dangerous token injection pattern.

### ✅ Parameterized SQL Queries (No SQL Injection)
Every query across all repositories uses `$1, $2, ...` placeholders with pgx. No string concatenation in SQL. The `ILIKE '%' || $1 || '%'` pattern in `note_repo.go:63` correctly uses a parameter for the search term.

### ✅ Ownership Checks on CRUD Operations (Fixed from C11 except Validate)
- OTP: `GetByID` (line 42-51), `Update` (line 83-90), `Delete` (line 110-122) all check `existing.UserID != userID`
- Password: `GetByID` (line 39-48), `Update` (line 81-99), `Delete` (line 102-115) all check ownership
- Note: `GetByID` (line 40-49), `Update` (line 118-135), `Delete` (line 137-149) all check ownership
- **Exception:** OTP Validate (CR-01) — the one endpoint that was missed

### ✅ Timing-Safe Code Comparison
`otp_service.go:146` — Uses `subtle.ConstantTimeCompare` for OTP code validation, preventing timing attacks on code comparison.

### ✅ Graceful Shutdown
All services implement proper SIGINT/SIGTERM handling with 10-second shutdown timeout, closing DB pool, Kafka connections, and HTTP server.

### ✅ CI Pipeline Security Scanning
`.github/workflows/main.yaml` — Comprehensive scanning: Hadolint (Docker lints), Trivy (FS + IaC), Gitleaks (secrets), CodeQL (SAST). Non-blocking but generates GitHub issues on failure.

---

## Docker-Compose Security Issues

| Issue | Severity | Detail |
|-------|----------|--------|
| Plaintext Kafka | HIGH | `PLAINTEXT://localhost:9092` — no TLS |
| Hardcoded DB credentials | MEDIUM | `POSTGRES_PASSWORD: postgres` |
| No resource limits | MEDIUM | No `mem_limit`, `cpus` on containers |
| Exposed ports | LOW | DB ports exposed to host (5433-5435) |
| No network isolation | LOW | All services on default network |

---

## Attack Scenario: Chained Exploitation

### Scenario: Full OTP Secret Extraction

**Preconditions:** Attacker has a valid account (can authenticate).

**Step 1 — Token Theft (if JWT_SECRET empty):**
Craft a forged JWT with any `sub` value using empty secret key → gain access as any user → use CR-03.

**Step 2 — ID Enumeration:**
Enumerate `/v1/otp/1`, `/v1/otp/2`, … using ME-01 (sequential IDs) → determine valid OTP IDs.

**Step 3 — OTP Validation Bypass:**
For each valid OTP ID, call `/v1/otp/{id}/validate?code=000000` through `999999` → CR-01 (no ownership check). Each call to `Validate` at `otp_service.go:125` fetches the OTP, decrypts its secret, and compares the code using constant-time comparison. An attacker can detect correct codes through response timing (the constant-time compare is only for the final comparison — the `FindByID` + `decryptSecret` + expiry check have variable timing).

**Step 4 — Secret Extraction via Timing:**
The `decryptSecret` at line 145 incurs a measurable timing difference between successful and failed decryption (GCM authentication tag verification). An attacker can use this to:
1. Validate a code → GCM auth tag failure on `o.Secret` if the stored ciphertext is corrupted/tampered
2. Detect correct codes even without the timing-safe comparison being the bottleneck

**Impact:** Complete compromise of all OTP secrets across all users.

---

## CVSS Score Summary

| ID | Finding | CVSS | Vector |
|----|---------|------|--------|
| CR-01 | OTP Validate IDOR | 8.1 | AV:N/AC:L/PR:L/UI:N/S:U/C:H/I:H/A:N |
| CR-02 | JWT/Kafka key reuse | 7.4 | AV:N/AC:H/PR:N/UI:N/S:U/C:H/I:H/A:N |
| CR-03 | Empty JWT_SECRET default | 9.8 | AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H |
| CR-04 | Empty ENCRYPTION_KEY default | 7.5 | AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:N/A:N |
| CR-05 | Crypto errors silently ignored | 6.5 | AV:N/AC:L/PR:L/UI:N/S:U/C:H/I:N/A:N |
| HI-01 | No body size limits | 7.5 | AV:N/AC:L/PR:N/UI:N/S:U/C:N/I:N/A:H |
| HI-02 | sslmode=disable | 6.5 | AV:A/AC:L/PR:N/UI:N/S:U/C:H/I:N/A:N |
| HI-03 | In-memory rate limiter | 5.9 | AV:N/AC:H/PR:N/UI:N/S:U/C:N/I:N/A:H |
| HI-04 | Kafka plaintext | 6.5 | AV:A/AC:L/PR:N/UI:N/S:U/C:H/I:N/A:N |
| HI-05 | Docker root | 7.8 | AV:L/AC:L/PR:N/UI:R/S:C/C:H/I:H/A:H |
| HI-06 | 500 vs 403 info leak | 5.3 | AV:N/AC:L/PR:N/UI:N/S:U/C:L/I:N/A:N |
| HI-07 | No Kafka DLQ | 5.9 | AV:N/AC:H/PR:N/UI:N/S:U/C:N/I:N/A:H |
| HI-08 | QR DoS (no size limits) | 7.5 | AV:N/AC:L/PR:N/UI:N/S:U/C:N/I:N/A:H |
| HI-09 | Error info disclosure | 4.3 | AV:N/AC:L/PR:L/UI:N/S:U/C:L/I:N/A:N |
| ME-01 | BIGSERIAL enumeration | 5.3 | AV:N/AC:L/PR:N/UI:N/S:U/C:L/I:N/A:N |
| ME-02 | expires_at as TEXT | 4.0 | AV:N/AC:H/PR:N/UI:N/S:U/C:N/I:L/A:L |
| ME-03 | No CORS config | 5.4 | AV:N/AC:L/PR:N/UI:R/S:U/C:L/I:L/A:N |
| ME-04 | FindByTitle no user filter | 6.5 | AV:N/AC:L/PR:L/UI:N/S:U/C:H/I:N/A:N |
| ME-05 | Healthcheck uses wget | 2.3 | AV:L/AC:H/PR:H/UI:N/S:U/C:N/I:N/A:L |
| ME-06 | FindByCreatedAt no user filter | 4.3 | AV:N/AC:L/PR:L/UI:N/S:U/C:L/I:N/A:N |
| LO-01 | DB creds in defaults | 4.0 | AV:N/AC:H/PR:N/UI:N/S:U/C:L/I:N/A:N |
| LO-02 | Missing security headers | 3.0 | AV:N/AC:H/PR:N/UI:R/S:U/C:N/I:L/A:N |

---

## Remediation Priority Matrix

### Immediate (before production deployment)

| # | Finding | Effort | Files Changed |
|---|---------|--------|---------------|
| 1 | CR-01: OTP Validate IDOR | 30 min | `otp_handler.go`, `otp_service.go` |
| 2 | CR-03: Enforce JWT_SECRET | 15 min | 3× `config.go` |
| 3 | CR-04: Enforce ENCRYPTION_KEY | 15 min | 3× `config.go` |
| 4 | CR-05: Log crypto errors | 30 min | 3× service files |
| 5 | HI-06: Normalize ownership errors | 1 hr | All service + handler files |
| 6 | HI-05: Add USER to Dockerfiles | 15 min | 3× `Dockerfile` |

### Short-term (1-2 sprints)

| # | Finding | Effort | Files Changed |
|---|---------|--------|---------------|
| 7 | CR-02: Separate Kafka HMAC key | 1 hr | 3× `config.go`, 3× `main.go` |
| 8 | HI-01: Body size limits | 2 hrs | `main.go`, all handlers |
| 9 | HI-02: Configurable sslmode | 30 min | 3× `config.go` |
| 10 | HI-08: QR input limits | 30 min | `qr_service.go` |
| 11 | HI-09: Generic error responses | 2 hrs | All handler files |
| 12 | ME-04/06: Fix dead code methods | 15 min | `note_repo.go` |

### Medium-term (next quarter)

| # | Finding | Effort | Dependencies |
|---|---------|--------|--------------|
| 13 | HI-03: Redis rate limiter | 1 day | Redis deployment |
| 14 | HI-04: Kafka TLS/SASL | 1 day | Kafka cluster config |
| 15 | HI-07: Kafka DLQ | 4 hrs | Kafka topic config |
| 16 | ME-01: UUID primary keys | 3 days | DB migration, API changes |
| 17 | ME-02: TIMESTAMP for expires_at | 1 day | DB migration |

---

## Comparison: Spring Boot vs Go Rewrite

| Category | Spring Boot (Old) | Go (New) | Delta |
|----------|-------------------|----------|-------|
| Total vulnerabilities | 34 | 22 | -35% |
| Critical | 13 | 5 | -62% |
| High | 12 | 9 | -25% |
| Medium | 9 | 6 | -33% |
| Low | 0 | 2 | +2 |
| JWT verification | Broken (base64 decode only) | Correct (HMAC verify) | Fixed |
| Encryption mode | ECB (deterministic) | GCM (authenticated) | Fixed |
| SQL injection risk | JDBC (moderate) | Parameterized (none) | Fixed |
| Kafka token injection | Messages stored as JWT | HMAC-verified | Fixed |
| Key management | Same secret for all uses | Same secret for JWT+Kafka | Partial |
| Encryption key default | Hardcoded but functional | Empty → silent failure | Regressed |
| Container security | USER appuser | Runs as root | Regressed |

---

## Appendices

### A. Attack Surface Map

```
                    ┌─────────────┐
                    │  API Client │
                    └──────┬──────┘
                           │ HTTPS (should be, not enforced)
                    ┌──────▼──────┐
                    │  Gin Router │
                    │  RateLimit  │ ← In-memory, per-instance
                    │  JWTAuth    │ ← JWT_SECRET, correct HMAC verify
                    └──┬──┬──┬───┘
                       │  │  │
              ┌────────▼──▼──▼─────────┐
              │    Service Layer       │
              │  - Ownership checks    │ ← MISSING on Validate
              │  - Encryption (GCM)    │ ← Silent errors
              │  - Kafka events        │
              └──┬──────────┬──────────┘
                 │          │
         ┌───────▼───┐ ┌───▼──────────┐
         │  PostgreSQL│ │ Kafka        │
         │  sslmode=  │ │ PLAINTEXT    │
         │  disable   │ │ key=JWT_SEC  │
         └───────────┘ └──────────────┘
```

### B. Environment Variables Checklist

| Variable | Required? | Min Length | Notes |
|----------|-----------|------------|-------|
| `JWT_SECRET` | **YES** | 32 chars | Must not be shared with `KAFKA_HMAC_KEY` |
| `ENCRYPTION_KEY` | **YES** | 16, 24, or 32 bytes | Must be valid AES key length |
| `KAFKA_HMAC_KEY` | **YES** | 32 chars | Separate from JWT_SECRET |
| `DB_HOST` | YES | — | |
| `DB_PORT` | YES | — | |
| `DB_USERNAME` | YES, no default | — | |
| `DB_PASSWORD` | YES, no default | — | |
| `DB_SSLMODE` | Recommended | — | `require` or `verify-full` |
| `KAFKA_HOST` | YES | — | |
| `KAFKA_PORT` | YES | — | |
| `PORT` | No | — | Default per service |

---

*Audit conducted 2026-06-15 | Go 1.25 codebase | 22 findings | 5 CRITICAL requiring immediate action*  
*No automated tooling used — full manual code review of all 22 source files*  
*Findings verified by source code analysis, not by runtime testing*
