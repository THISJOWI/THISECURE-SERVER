---
phase: code-review
reviewed_at: 2026-06-15T12:00:00Z
scope: full
severity_counts:
  critical: 7
  warning: 10
  info: 5
total_findings: 22
status: issues_found
---

# Code Review Report — ThisSecure Go Backend

**Reviewed at:** 2026-06-15
**Scope:** `otp`, `password`, `note` Go microservices + shared `pkg/` library
**Stack:** Go 1.25 / Gin / pgx v5 / Kafka / Postgres (CockroachDB)

---

## Executive Summary

**22 findings** — 7 CRITICAL, 10 WARNING, 5 INFO

The Go rewrite fixes many issues from the original Java/Spring Boot version (proper JWT signature verification, AES-GCM instead of ECB, no hardcoded defaults). However, several new Go-specific issues exist and some old patterns recur.

---

## CRITICAL Findings

### CR-01: OTP Validate() has Insecure Direct Object Reference (IDOR)

**File:** `services/otp/internal/service/otp_service.go:125-150`
**CWE:** CWE-639 (Authorization Bypass Through User-Controlled Key)
**Severity:** CRITICAL

**Issue:**
The `Validate()` method fetches an OTP by ID only, with NO ownership check. The method signature `Validate(ctx context.Context, id int64, code string) (bool, error)` does not even accept a `userID` parameter. Any authenticated user can validate any OTP record by ID.

The handler at `otp_handler.go:129-146` also fails to pass the user ID to Validate.

**Root Cause:**
Unlike `GetByID()` (line 42-52), `Update()` (line 83-108), and `Delete()` (line 110-123) which all check `existing.UserID != userID`, the `Validate()` method:
1. Does not accept a `userID` parameter
2. Never checks `o.UserID` against the authenticated user

**Attack Vector:**
1. Authenticate as User A
2. Iterate through OTP IDs (sequential BIGSERIAL): `POST /v1/otp/1/validate?code=123456`, `POST /v1/otp/2/validate?code=123456`, ...
3. Validate OTPs belonging to any user (including brute-forcing codes for accounts you don't own)

**Fix:**

**1. `otp_service.go` — Add userID parameter and ownership check to Validate():**

```go
func (s *OtpService) Validate(ctx context.Context, id int64, code, userID string) (bool, error) {
    o, err := s.repo.FindByID(ctx, id)
    if err != nil {
        return false, err
    }
    if o == nil {
        return false, fmt.Errorf("otp not found")
    }
    // ADD: ownership check
    if o.UserID != userID {
        return false, fmt.Errorf("otp not found")
    }
    // ... rest of method unchanged
}
```

**2. `otp_handler.go` — Pass userID to Validate():**

```go
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
    userID := middleware.GetUserID(c)
    valid, err := h.svc.Validate(c.Request.Context(), id, code, userID)
    // ... rest unchanged
}
```

**3. Optionally, add a `FindByIDAndUser` repository method** for a single-query approach:

```go
func (r *OtpRepo) FindByIDAndUser(ctx context.Context, id int64, userID string) (*model.Otp, error) {
    rows, err := r.pool.Query(ctx, 
        `SELECT id, user_id, email, secret, expires_at, type, issuer, digits, period, algorithm, valid 
         FROM otp WHERE id = $1 AND user_id = $2`, id, userID)
    // ... rest
}
```

**Test Cases:**
- Validate with correct user ID → success
- Validate with different user's OTP ID → returns "otp not found" (not distinguishing from genuinely missing)
- Validate with non-existent ID → "otp not found"

**Migration:** Breaking change to `OtpService.Validate()` signature — all callers must be updated. Handler is the only caller in this codebase.

---

### CR-02: Encryption Failures Silently Ignored — Plaintext Data Leakage

**Files:**
- `services/otp/internal/service/otp_service.go:152-170`
- `services/note/internal/service/note_service.go:169-191`
- `services/password/internal/service/password_service.go:134-152`

**Severity:** CRITICAL

**Issue:**
All three services have encrypt/decrypt helper methods that silently ignore crypto errors. When encryption fails (invalid key length, corrupted data), the plaintext data continues to flow unencrypted with zero indication.

**Pattern (present in all 3 services):**

```go
func (s *NoteService) encryptNote(n *model.Note) {
    if s.encKey == nil {
        return
    }
    if n.Content != "" {
        enc, err := crypto.Encrypt([]byte(n.Content), s.encKey)
        if err == nil {
            n.Content = enc
        }
        // BUG: if err != nil, n.Content stays unencrypted. No log, no warning.
    }
}
```

**Root Cause:**
The encrypt/decrypt methods are designed as fire-and-forget helpers with no error propagation. The callers (`Create`, `Update`, `GetAll`, `GetByID`, etc.) never check if encryption/decryption succeeded.

**Fix:**

**1. Return errors from encrypt/decrypt and propagate to callers:**

```go
func (s *NoteService) encryptNote(n *model.Note) error {
    if len(s.encKey) == 0 {
        return nil
    }
    if n.Content == "" {
        return nil
    }
    enc, err := crypto.Encrypt([]byte(n.Content), s.encKey)
    if err != nil {
        return fmt.Errorf("encrypt note: %w", err)
    }
    n.Content = enc
    return nil
}
```

**2. Update callers to check errors (example for Create):**

```go
func (s *NoteService) Create(ctx context.Context, req model.NoteRequest, userID string) (*model.Note, error) {
    // ... setup ...
    if err := s.encryptNote(note); err != nil {
        return nil, fmt.Errorf("encryption failed: %w", err)
    }
    // ... proceed ...
}
```

**3. At minimum, log errors if full propagation is too invasive:**

```go
func (s *NoteService) encryptNote(n *model.Note) {
    if len(s.encKey) == 0 {
        return
    }
    if n.Content != "" {
        enc, err := crypto.Encrypt([]byte(n.Content), s.encKey)
        if err != nil {
            log.Printf("ERROR: encryptNote failed: %v", err)
            return
        }
        n.Content = enc
    }
}
```

**Warning:** The minimum fix (logging) is better than silence but still allows plaintext data to persist in the database on encryption failure. The full fix (error propagation) is strongly recommended.

**Test Cases:**
- Encrypt with valid key → content is encrypted
- Encrypt with wrong-size key → error returned, content unchanged
- Decrypt with valid key → content decrypted
- Decrypt with corrupted ciphertext → error returned

---

### CR-03: Empty Encryption Key Not Detected — nil vs Empty Slice

**Files:**
- `services/note/internal/service/note_service.go:170`
- `services/password/internal/service/password_service.go:135`
- `services/otp/internal/service/otp_service.go:153`

**Severity:** CRITICAL

**Issue:**
The encrypt/decrypt guard checks use `s.encKey == nil` to skip encryption when no key is configured. However, when `ENCRYPTION_KEY` env var is empty (or not set), `config.Load()` produces `cfg.EncryptionKey = ""` and `main.go` does `encKey := []byte(cfg.EncryptionKey)`. The result is `[]byte{}` — a **non-nil empty slice**, not `nil`.

This means:
- `s.encKey == nil` is **false** (it's `[]byte{}`, not `nil`)
- The encryption guard is bypassed
- `crypto.Encrypt([]byte(content), []byte{})` is called with an empty key
- AES-GCM fails because the key is 0 bytes, but the error is silently ignored
- Data is stored unencrypted

**Root Cause:**
Go's distinction between `nil` slices and empty slices. `[]byte("")` produces `[]byte{}` (non-nil, zero-length). The nil check treats empty slice as "key is set".

**Fix:**

Replace all `s.encKey == nil` checks with `len(s.encKey) == 0`:

```go
// In all 3 services, change:
if s.encKey == nil || o.Secret == "" {

// To:
if len(s.encKey) == 0 || o.Secret == "" {
```

Affected locations:
- `otp_service.go:153` (encryptSecret)
- `otp_service.go:163` (decryptSecret)
- `note_service.go:170` (encryptNote)
- `note_service.go:182` (decryptNote)
- `password_service.go:135` (encrypt)
- `password_service.go:145` (decrypt)

**Test Cases:**
- Service created with `encKey = []byte{}` → encrypt/decrypt are no-ops, key validation fails at startup
- Service created with `encKey = []byte("valid-32-byte-key-here!!!!")` → encrypt/decrypt work normally
- Service created with `encKey = nil` → encrypt/decrypt skip (defensive, though shouldn't happen)

---

### CR-04: Missing Startup Config Validation — Services Start with Empty Secrets

**Files:**
- `services/otp/internal/config/config.go:1-41`
- `services/note/internal/config/config.go:1-41`
- `services/password/internal/config/config.go:1-41`
- `services/otp/cmd/server/main.go:23-85`
- `services/note/cmd/server/main.go:22-66`
- `services/password/cmd/server/main.go:22-67`

**Severity:** CRITICAL

**Issue:**
All three config files default `JWT_SECRET` and `ENCRYPTION_KEY` to `""` (empty string). All three `main.go` files proceed without validating these values. Combined with CR-03 (empty key bypasses encryption), services can start in a completely insecure state:

- JWT validation: `jwt.ValidateToken` with `[]byte("")` as secret — any HMAC-signed token with empty key passes (the empty string is a valid HMAC key)
- Encryption: empty key silently bypasses encryption
- Kafka HMAC: empty key produces deterministic HMACs that are trivially forgeable

**Root Cause:**
No startup validation. `crypto.ValidateKey()` exists but is never called. No JWT secret validation exists.

**Fix:**

**1. Add a `Validate()` method to each Config:**

```go
func (c Config) Validate() error {
    if c.JWTSecret == "" {
        return fmt.Errorf("JWT_SECRET is required")
    }
    if len(c.JWTSecret) < 32 {
        return fmt.Errorf("JWT_SECRET must be at least 32 characters")
    }
    if c.EncryptionKey == "" {
        return fmt.Errorf("ENCRYPTION_KEY is required")
    }
    if err := crypto.ValidateKey([]byte(c.EncryptionKey)); err != nil {
        return fmt.Errorf("ENCRYPTION_KEY: %w", err)
    }
    return nil
}
```

**2. Call validation in main.go immediately after Load():**

```go
cfg := config.Load()
if err := cfg.Validate(); err != nil {
    log.Fatalf("invalid config: %v", err)
}
```

**3. Apply to all three services.**

**Test Cases:**
- Start with valid JWT_SECRET and ENCRYPTION_KEY → success
- Start with empty JWT_SECRET → fatal error "JWT_SECRET is required"
- Start with short ENCRYPTION_KEY → fatal error "key must be 16, 24, or 32 bytes"

---

### CR-05: JWT Secret Reused as Kafka HMAC Key — Key Separation Violation

**Files:**
- `services/otp/cmd/server/main.go:36`
- `services/note/cmd/server/main.go:35`
- `services/password/cmd/server/main.go:35`

**Severity:** CRITICAL

**Issue:**
All three main.go files use the JWT secret directly as the Kafka HMAC signing key:

```go
signer := kafka.NewSigner(jwtSecret) // jwtSecret is cfg.JWTSecret as []byte
```

This means:
- The same key signs JWT tokens and Kafka message HMACs
- If the JWT secret is compromised, Kafka message integrity is also compromised
- If the Kafka HMAC key is broken, JWT tokens can be forged

**Root Cause:**
No separate `KAFKA_HMAC_KEY` configuration exists. The JWT secret is convenient but violates the cryptographic principle of key separation.

**Fix:**

**1. Add `KafkaHMACKey` to Config structs:**

```go
type Config struct {
    Port          string
    DatabaseURL   string
    JWTSecret     string
    EncryptionKey string
    KafkaHMACKey  string   // NEW
    KafkaBrokers  []string
}
```

**2. Load from env var with fallback:**

```go
cfg.KafkaHMACKey = getEnv("KAFKA_HMAC_KEY", getEnv("JWT_SECRET", ""))
```

**3. Use separate key in main.go:**

```go
hmacKey := []byte(cfg.KafkaHMACKey)
signer := kafka.NewSigner(hmacKey)
```

**Note:** The fallback to JWT_SECRET preserves backward compatibility. A deprecation warning should be logged when JWT_SECRET is used as fallback.

**Test Cases:**
- Set KAFKA_HMAC_KEY → uses KAFKA_HMAC_KEY
- Don't set KAFKA_HMAC_KEY → falls back to JWT_SECRET with warning
- Neither set → fatal config error (see CR-04)

---

### CR-06: In-Memory Rate Limiter Unbounded Growth — Memory Leak

**File:** `pkg/middleware/ratelimit.go:16-22`
**Severity:** CRITICAL

**Issue:**
The `RateLimiter` struct maintains a `map[string]*visitor` that is never cleaned up. Every unique IP address that hits the service creates a `visitor` entry that persists for the lifetime of the process. Under normal traffic patterns with dynamic IPs, this map grows without bound.

```go
type RateLimiter struct {
    mu       sync.Mutex
    visitors map[string]*visitor  // NEVER CLEANED
    rate     int
    burst    int
    interval time.Duration
}
```

**Root Cause:**
No periodic cleanup goroutine. No TTL on entries. The map grows linearly with the number of unique IPs seen.

**Fix:**

**1. Add a background cleanup goroutine:**

```go
func NewRateLimiter(rate, burst int, interval time.Duration) *RateLimiter {
    rl := &RateLimiter{
        visitors: make(map[string]*visitor),
        rate:     rate,
        burst:    burst,
        interval: interval,
    }
    go rl.cleanupLoop()
    return rl
}

func (rl *RateLimiter) cleanupLoop() {
    ticker := time.NewTicker(5 * time.Minute)
    defer ticker.Stop()
    for range ticker.C {
        rl.mu.Lock()
        cutoff := time.Now().Add(-rl.interval * 2)
        for ip, v := range rl.visitors {
            if v.lastCheck.Before(cutoff) {
                delete(rl.visitors, ip)
            }
        }
        rl.mu.Unlock()
    }
}
```

**2. Or alternatively, use a wrapper that provides lifecycle management:**

```go
func (rl *RateLimiter) Stop() {
    // signal cleanup goroutine to stop
}
```

**Test Cases:**
- Add entries, wait for cleanup interval, verify old entries removed
- Active entries (recently accessed) not removed
- Cleanup doesn't race with Allow() calls

---

### CR-07: note_repo.go FindByTitle() and FindByCreatedAt() Leak Cross-User Data

**File:** `services/note/internal/repository/note_repo.go:107-130`
**Severity:** CRITICAL

**Issue:**
Two public repository methods query without a `user_id` filter:

```go
// Line 107-121: NO user_id filter
func (r *NoteRepo) FindByTitle(ctx context.Context, title string) (*model.Note, error) {
    rows, err := r.pool.Query(ctx, 
        `SELECT id, content, title, created_at, user_id, version FROM notes WHERE title = $1`, title)
    // ...
}

// Line 123-130: NO user_id filter
func (r *NoteRepo) FindByCreatedAt(ctx context.Context, t time.Time) ([]model.Note, error) {
    rows, err := r.pool.Query(ctx,
        `SELECT id, content, title, created_at, user_id, version FROM notes WHERE created_at = $1`, t)
    // ...
}
```

**Root Cause:**
These methods exist alongside the properly-filtered `FindByTitleAndUser()`. While they are not currently called from the service layer, they are exported public methods that could be used accidentally or by future code.

**Fix:**

**Option A: Remove the unsafe methods entirely** (preferred, since they're unused). The service layer uses `FindByTitleAndUser` which properly filters by `user_id`.

**Option B: Add user_id parameter to make them safe:**

```go
func (r *NoteRepo) FindByTitle(ctx context.Context, title, userID string) (*model.Note, error) {
    rows, err := r.pool.Query(ctx, 
        `SELECT ... FROM notes WHERE title = $1 AND user_id = $2`, title, userID)
    // ...
}
```

**Test Cases:**
- Verify FindByTitleAndUser returns notes only for the specified user
- Verify the unsafe methods are removed or fixed
- Cross-user test: User A's notes not visible to User B

---

## WARNING Findings

### WR-01: Route Ambiguity — `/v1/notes/:title` Conflicts with `/v1/notes/id/:id`

**File:** `services/note/internal/handler/note_handler.go:21-30`
**Severity:** WARNING

**Issue:**
Route registration order creates ambiguity when a note has the title "id":

```go
func (h *NoteHandler) Register(r *gin.RouterGroup) {
    // ...
    r.GET("/:title", h.GetByTitle)     // Line 26: matches /v1/notes/ANYTHING
    r.GET("/id/:id", h.GetByID)        // Line 27: matches /v1/notes/id/123
    // ...
}
```

Since Gin evaluates routes in registration order, `GET /v1/notes/id` will match `/:title` (with `title = "id"`) before `GET /v1/notes/id/123` has a chance. The title "id" can never be retrieved by title lookup.

**Root Cause:**
Wildcard route `/:title` greedily matches any single path segment, including the literal "id".

**Fix:**

**Option A: Register specific routes before wildcard routes (swap order):**

```go
r.GET("/id/:id", h.GetByID)           // MUST come first
r.GET("/:title", h.GetByTitle)        // catch-all after specific routes
```

**Option B: Use a different URL structure that avoids ambiguity:**

```go
r.GET("/by-title/:title", h.GetByTitle)  // explicit path
r.GET("/:id", h.GetByID)                 // numeric IDs only, with middleware check
```

**Test Cases:**
- `GET /v1/notes/my-title` → GetByTitle handler
- `GET /v1/notes/id/123` → GetByID handler
- `GET /v1/notes/id` (title="id") → GetByTitle handler

**Migration:** Option A is backward-compatible. Option B changes API URLs.

---

### WR-02: Error Responses Leak Internal Details to Clients

**Files:**
- `services/otp/internal/handler/otp_handler.go`
- `services/note/internal/handler/note_handler.go`
- `services/password/internal/handler/password_handler.go`

**Severity:** WARNING

**Issue:**
All error responses use `c.JSON(status, gin.H{"error": err.Error()})`, which exposes internal error messages including database errors, stack traces, and implementation details. Examples of strings that could leak:

- `"query: connection refused"` — exposes infrastructure details
- `"collect: no rows in result set"` — exposes ORM internals
- `"insert: duplicate key value violates unique constraint"` — exposes schema details
- `"note not found or not owned"` — distinguishes "not found" from "not owned" (information disclosure)

**Root Cause:**
No distinction between user-facing error messages and internal errors. All `fmt.Errorf` messages from the service/repository layer are passed directly to the HTTP response.

**Fix:**

**1. Define typed errors in each service package:**

```go
// In service or model package
var (
    ErrNotFound      = errors.New("resource not found")
    ErrNotOwned      = errors.New("resource not owned")
    ErrInvalidInput  = errors.New("invalid input")
    ErrInternal      = errors.New("internal error")
)
```

**2. Service layer wraps errors with types:**

```go
if existing == nil || existing.UserID != userID {
    return nil, fmt.Errorf("%w: otp not found or not owned", ErrNotFound)
}
```

**3. Handler maps typed errors to status codes and safe messages:**

```go
func errorResponse(c *gin.Context, err error) {
    switch {
    case errors.Is(err, model.ErrNotFound):
        c.JSON(http.StatusNotFound, gin.H{"error": "resource not found"})
    case errors.Is(err, model.ErrNotOwned):
        c.JSON(http.StatusForbidden, gin.H{"error": "access denied"})
    case errors.Is(err, model.ErrInvalidInput):
        c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()}) // validation errors are safe
    default:
        log.Printf("ERROR: %v", err) // log the real error server-side
        c.JSON(http.StatusInternalServerError, gin.H{"error": "internal server error"})
    }
}
```

**Test Cases:**
- Not found → 404 with generic message
- Not owned → 403 (not 500) with generic message
- DB failure → 500 with generic message, real error in server logs
- Validation error → 400 with specific message (safe to expose)

---

### WR-03: Service Layer Returns Generic Error Messages Sent as HTTP 500

**Files:**
- `services/otp/internal/service/otp_service.go:88-89, 115-116`
- `services/note/internal/service/note_service.go:123-124, 142-143`
- `services/password/internal/service/password_service.go:86-87, 107-108`

**Severity:** WARNING

**Issue:**
When ownership checks fail, service methods return errors like `fmt.Errorf("otp not found or not owned")`. Handlers treat ALL errors as HTTP 500 Internal Server Error. An ownership failure should be 403 Forbidden or 404 Not Found (to avoid user enumeration), not 500.

```
Handler: c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
```

This conflates server errors with authorization errors, making monitoring harder and leaking information.

**Fix:**
See WR-02 for the typed errors approach. Additionally, status codes should be:

| Condition | HTTP Status |
|-----------|-------------|
| Row not found (ID doesn't exist) | 404 Not Found |
| Row exists but belongs to different user | 404 Not Found (don't reveal existence) |
| Database error | 500 Internal Server Error |
| Invalid input | 400 Bad Request |

---

### WR-04: `sslmode=disable` Hardcoded in All Database URLs

**Files:**
- `services/otp/internal/config/config.go:27`
- `services/note/internal/config/config.go:27`
- `services/password/internal/config/config.go:27`

**Severity:** WARNING

**Issue:**
All three config.go files hardcode `sslmode=disable` in the database connection string:

```go
cfg.DatabaseURL = fmt.Sprintf("postgres://%s:%s@%s:%s/otp?sslmode=disable", ...)
```

There is no way to enable SSL/TLS for the database connection without modifying source code.

**Root Cause:**
No `DB_SSLMODE` environment variable. The sslmode parameter is hardcoded.

**Fix:**

**1. Add DB_SSLMODE to config and connection string:**

```go
type Config struct {
    // ...
    DBSSLMode string
}

func Load() Config {
    cfg := Config{
        // ...
        DBSSLMode: getEnv("DB_SSLMODE", "disable"), // default disable for dev
    }
    // ...
    cfg.DatabaseURL = fmt.Sprintf("postgres://%s:%s@%s:%s/%s?sslmode=%s",
        dbUser, dbPass, dbHost, dbPort, dbName, cfg.DBSSLMode)
}
```

**2. Validate in Config.Validate() (see CR-04):**

```go
validSSLModes := map[string]bool{"disable": true, "require": true, "verify-ca": true, "verify-full": true}
if !validSSLModes[c.DBSSLMode] {
    return fmt.Errorf("invalid DB_SSLMODE: %s", c.DBSSLMode)
}
```

**3. Enforce `require` or stricter in production via env var.**

**Note:** `docker-compose.yaml` uses local Postgres containers where `disable` is acceptable. Production should use `require` or `verify-full`.

---

### WR-05: Kafka Producers Use `context.Background()` Instead of Request Context

**Files:**
- `services/otp/internal/service/otp_service.go:186, 202`
- `services/note/internal/service/note_service.go:209`
- `services/password/internal/service/password_service.go:171`

**Severity:** WARNING

**Issue:**
All `publishEvent`/`publishEvents` methods use `context.Background()` for Kafka writes instead of the parent request context:

```go
if err := s.syncProd.Publish(context.Background(), o.UserID, event); err != nil {
    log.Printf("WARN: failed to publish sync event: %v", err)
}
```

This means:
- Request cancellation does not propagate to Kafka writes
- Kafka write timeouts are unbounded (Background context never cancels)
- If the HTTP request times out, Kafka writes continue independently

**Root Cause:**
The publish helpers don't accept a context parameter. They create a detached context.

**Fix:**

Change publishEvent signature to accept context, and pass it through:

```go
func (s *NoteService) publishEvent(ctx context.Context, note *model.Note, action string) {
    if s.syncEvents == nil {
        return
    }
    // ...
    if err := s.syncEvents.Publish(ctx, note.UserID, event); err != nil {
        log.Printf("WARN: failed to publish sync event: %v", err)
    }
}
```

Then callers pass `ctx`:

```go
s.publishEvent(ctx, note, "created")
```

**Test Cases:**
- Request cancellation propagates to Kafka
- Kafka timeout respects context deadline

---

### WR-06: Rate Limiter Uses Client IP Without Trusted Proxy Handling

**File:** `pkg/middleware/ratelimit.go:59`
**Severity:** WARNING

**Issue:**
The rate limiter uses `c.ClientIP()` which trusts `X-Forwarded-For` and `X-Real-Ip` headers by default. An attacker can spoof these headers to:
- Distribute requests across fake IPs to bypass rate limits
- Impersonate other IPs to cause them to be rate-limited

**Root Cause:**
Gin's `ClientIP()` trusts proxy headers by default. In production behind a reverse proxy, this is desirable. But without proper trusted proxy configuration, it's a vulnerability.

**Fix:**

**1. Set trusted proxies in Gin:**

```go
// In main.go
r := gin.Default()
r.SetTrustedProxies([]string{"10.0.0.0/8", "172.16.0.0/12"}) // your trusted CIDRs
```

**2. Or validate the source of X-Forwarded-For:**

```go
r.ForwardedByClientIP = true
r.SetTrustedProxies(trustedProxies)
```

**Test Cases:**
- Request with spoofed X-Forwarded-For from untrusted IP → uses actual IP
- Request from trusted proxy with X-Forwarded-For → uses forwarded IP

---

### WR-07: Kafka Consumer Loop Has No Context Cancellation Check

**File:** `pkg/kafka/consumer.go:34-52`
**Severity:** WARNING

**Issue:**
The consumer `Run()` method runs an infinite `for` loop without checking for context cancellation:

```go
func (c *Consumer) Run(ctx context.Context) error {
    for {
        msg, err := c.reader.FetchMessage(ctx)
        if err != nil {
            return fmt.Errorf("fetch: %w", err)
        }
        // ... process message
    }
}
```

While `FetchMessage` does accept a context and will return an error on cancellation, the error handling returns immediately without distinguishing cancellation from real errors. If the context is cancelled, the goroutine exits with an error log but the error is not distinguished.

**Fix:**

Check for context cancellation explicitly:

```go
func (c *Consumer) Run(ctx context.Context) error {
    for {
        select {
        case <-ctx.Done():
            return ctx.Err()
        default:
        }
        
        msg, err := c.reader.FetchMessage(ctx)
        if err != nil {
            if errors.Is(err, context.Canceled) || errors.Is(err, context.DeadlineExceeded) {
                return nil // clean shutdown
            }
            return fmt.Errorf("fetch: %w", err)
        }
        // ... process message
    }
}
```

---

### WR-08: No Rate Limiting on OTP Validate Endpoint — Brute Force Risk

**File:** `services/otp/internal/handler/otp_handler.go:129-146`
**Severity:** WARNING

**Issue:**
The OTP validate endpoint `POST /v1/otp/:id/validate?code=XXXXXX` has no per-ID rate limiting. While there is a global rate limiter (10 rps, burst 20), an attacker can:
1. Make 10 validate requests per second across different OTP IDs
2. No lockout after repeated failed attempts on the same OTP
3. No progressive delay or CAPTCHA

**Root Cause:**
The rate limiter is global (per-IP), not per-resource. Failed attempts are not tracked.

**Fix:**

**1. Add failed attempt tracking in the service or a dedicated middleware:**

```go
// In OtpService
type OtpService struct {
    repo         *repository.OtpRepo
    encKey       []byte
    eventProd    *kafka.Producer
    syncProd     *kafka.Producer
    failedAttempts map[int64]*attemptTracker  // NEW
    mu            sync.Mutex                   // NEW
}

func (s *OtpService) Validate(ctx context.Context, id int64, code, userID string) (bool, error) {
    // Check failed attempts
    s.mu.Lock()
    tracker := s.failedAttempts[id]
    if tracker != nil && tracker.count >= 5 && time.Since(tracker.lastAttempt) < 15*time.Minute {
        s.mu.Unlock()
        return false, fmt.Errorf("too many attempts, try again later")
    }
    s.mu.Unlock()
    
    // ... existing validation logic ...
    
    if !valid {
        s.mu.Lock()
        if s.failedAttempts[id] == nil {
            s.failedAttempts[id] = &attemptTracker{}
        }
        s.failedAttempts[id].count++
        s.failedAttempts[id].lastAttempt = time.Now()
        s.mu.Unlock()
    }
}
```

**Test Cases:**
- 5 failed attempts → 6th attempt blocked for 15 minutes
- Successful validation → resets failed count
- Different OTP ID → independent counter

---

### WR-09: Note Service Create() Error Handling on Unique Constraint Race

**File:** `services/note/internal/service/note_service.go:96-110`
**Severity:** WARNING

**Issue:**
In the `Create()` method, the unique constraint violation handler has nested error handling issues:

```go
if pgErr, ok := err.(*pgconn.PgError); ok && pgErr.Code == "23505" {
    existing2, _ := s.repo.FindByTitleAndUser(ctx, req.Title, userID)  // error ignored
    if existing2 != nil {
        note.ID = existing2.ID
        note.Version = existing2.Version
        s.repo.Update(ctx, note)  // update error ignored
        s.publishEvent(note, "created")
        return s.GetByID(ctx, note.ID, userID)
    }
}
```

Problems:
1. `FindByTitleAndUser` error is silently ignored (`_`)
2. `s.repo.Update(ctx, note)` error is silently ignored
3. `s.GetByID` error is silently ignored (returned directly)
4. The unique constraint handler is nested inside the `Insert` error handler, but at the end of the `else` block — the code flow is confusing

**Fix:**

```go
if pgErr, ok := err.(*pgconn.PgError); ok && pgErr.Code == "23505" {
    existing2, findErr := s.repo.FindByTitleAndUser(ctx, req.Title, userID)
    if findErr != nil {
        return nil, fmt.Errorf("find after race: %w", findErr)
    }
    if existing2 != nil {
        note.ID = existing2.ID
        note.Version = existing2.Version
        if updateErr := s.repo.Update(ctx, note); updateErr != nil {
            return nil, fmt.Errorf("update after race: %w", updateErr)
        }
        s.decryptNote(note)
        s.publishEvent(ctx, note, "created")
        return note, nil
    }
}
```

---

### WR-10: Dedup Service Lacks Encryption Key — Cannot Decrypt

**File:** `services/password/internal/service/dedup_service.go:20-56`
**Severity:** WARNING

**Issue:**
The `DedupService` receives a `*repository.PasswordRepo` but NOT an encryption key. It calls `FindByUserID` which returns encrypted passwords. However, it only groups by `Name`, `Website`, and `Username` — which are NOT encrypted — so this works correctly for the dedup use case.

**But:** If future changes add password comparison (e.g., "find duplicate passwords with same value"), the service cannot decrypt. The architecture should either:
1. Pass the encryption key to DedupService for future use
2. Document clearly that DedupService only compares unencrypted fields

**Fix:**

Option 1: Add encryption key to DedupService:

```go
type DedupService struct {
    repo   *repository.PasswordRepo
    encKey []byte
}

func NewDedupService(repo *repository.PasswordRepo, encKey []byte) *DedupService {
    return &DedupService{repo: repo, encKey: encKey}
}
```

Option 2: Document the limitation with a comment:

```go
// DedupService identifies duplicates by comparing unencrypted fields
// (Name, Website, Username) only. It does NOT compare encrypted password values.
type DedupService struct {
    repo *repository.PasswordRepo
}
```

---

## INFO Findings

### IN-01: Rate Limiter Token Bucket Integer Division Inaccuracy

**File:** `pkg/middleware/ratelimit.go:44`
**Severity:** INFO

**Issue:**
Token replenishment uses integer division:

```go
v.tokens += int(elapsed/rl.interval) * rl.rate
```

Since `elapsed` and `rl.interval` are both `time.Duration` (nanoseconds), short elapsed times (less than one interval) result in zero tokens replenished. This is generally fine for token bucket, but edge cases around sub-interval requests can cause slight inaccuracy.

**Recommendation:** Use floating point for more precise token replenishment, or accept the conservative behavior (fewer tokens granted = more restrictive, not a security issue).

---

### IN-02: JWT Signing Method Validation Only Allows HMAC

**File:** `pkg/jwt/jwt.go:11`
**Severity:** INFO

**Issue:**
The JWT validation only accepts HMAC signing methods:

```go
if _, ok := t.Method.(*jwt.SigningMethodHMAC); !ok {
    return nil, fmt.Errorf("unexpected signing method: %v", t.Header["alg"])
}
```

This is correct for the current architecture (shared secret between auth service and microservices), but prevents adoption of RS256/ES256 for asymmetric JWT signing in the future.

**Recommendation:** Add a configurable list of allowed signing methods, or document that only HMAC is supported.

---

### IN-03: OTP Model Uses String for ExpiresAt Instead of time.Time

**File:** `services/otp/internal/model/otp.go:8`
**Severity:** INFO

**Issue:**
The `Otp.ExpiresAt` field is `string` (stored as TEXT in the database) rather than `time.Time`:

```go
ExpiresAt string `json:"expiresAt" db:"expires_at"`
```

This requires manual parsing with `strconv.ParseInt` at every validation check, and loses database-level temporal query capabilities (e.g., `SELECT ... WHERE expires_at > NOW()`).

**Recommendation:** Change to `time.Time` with a database migration to convert the column. This is a breaking schema change.

---

### IN-04: No Structured Logging — Inconsistent Log Levels

**Files:** All service and handler files
**Severity:** INFO

**Issue:**
All logging uses the standard `log` package with inconsistent formats:

- `log.Printf("WARN: ...")` — custom prefix
- `log.Printf("ERROR: ...")` — custom prefix
- `log.Fatalf("database: %v", err)` — no prefix
- `log.Println("shutting down...")` — no prefix

No structured logging (JSON), no log levels, no correlation IDs.

**Recommendation:** Adopt a structured logger like `slog` (Go 1.21+ standard library) or `zerolog`/`zap`. Include request IDs and trace IDs.

---

### IN-05: No Health Check Verifies Database or Kafka Connectivity

**Files:** All three `main.go` health endpoints
**Severity:** INFO

**Issue:**
The health endpoint only returns a static response:

```go
r.GET("/health", func(c *gin.Context) { c.JSON(200, gin.H{"status": "ok"}) })
```

This does not verify database connectivity or Kafka broker availability. Kubernetes liveness/readiness probes would report healthy even when the database is unreachable.

**Recommendation:** Add a deep health check that pings the database and checks Kafka connectivity. Keep the simple endpoint for liveness, add `/health/ready` for readiness.

---

## Summary of Required Fixes by Priority

### Must Fix (CRITICAL)
| ID | Issue | Effort |
|----|-------|--------|
| CR-01 | OTP Validate IDOR | Small |
| CR-02 | Silently ignored encryption errors | Medium |
| CR-03 | Empty encryption key not detected | Small |
| CR-04 | Missing config validation | Small |
| CR-05 | JWT secret reused as HMAC key | Small |
| CR-06 | Rate limiter memory leak | Small |
| CR-07 | Unsafe repository methods | Small |

### Should Fix (WARNING)
| ID | Issue | Effort |
|----|-------|--------|
| WR-01 | Route ambiguity | Small |
| WR-02 | Error details leaked to clients | Medium |
| WR-03 | Wrong HTTP status codes | Small |
| WR-04 | Hardcoded sslmode=disable | Small |
| WR-05 | context.Background() for Kafka | Small |
| WR-06 | Trusted proxy not configured | Small |
| WR-07 | Kafka consumer context check | Small |
| WR-08 | OTP brute force no lockout | Medium |
| WR-09 | Create() error handling gaps | Small |
| WR-10 | DedupService missing encKey | Small |

### Consider (INFO)
| ID | Issue | Effort |
|----|-------|--------|
| IN-01 | Token bucket precision | Trivial |
| IN-02 | JWT signing method flexibility | Small |
| IN-03 | ExpiresAt as string instead of time.Time | Large (DB migration) |
| IN-04 | Structured logging | Medium |
| IN-05 | Deep health checks | Small |

---

_Reviewed: 2026-06-15_
_Reviewer: the agent (code-reviewer)_
