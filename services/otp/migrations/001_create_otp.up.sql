CREATE TABLE IF NOT EXISTS otp (
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

CREATE INDEX IF NOT EXISTS idx_otp_user_id ON otp(user_id);
