CREATE TABLE IF NOT EXISTS otp (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT,
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

CREATE TABLE IF NOT EXISTS otp_key (
    id BIGSERIAL PRIMARY KEY,
    user_id VARCHAR(255),
    otp VARCHAR(255),
    created_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_otp_user_id ON otp(user_id);
CREATE INDEX IF NOT EXISTS idx_otp_key_user_id ON otp_key(user_id);
