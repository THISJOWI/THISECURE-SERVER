-- liquibase formatted sql
-- changeset otp:1 runOnChange:false
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

-- changeset otp:2 runOnChange:false
CREATE TABLE IF NOT EXISTS otp_key (
    id BIGSERIAL PRIMARY KEY,
    user_id VARCHAR(255),
    otp VARCHAR(255),
    created_at TIMESTAMP
);

-- changeset otp:3 runOnChange:false
CREATE INDEX IF NOT EXISTS idx_otp_user_id ON otp(user_id);

-- changeset otp:4 runOnChange:false
CREATE INDEX IF NOT EXISTS idx_otp_key_user_id ON otp_key(user_id);

-- changeset otp:5 runOnChange:false
ALTER TABLE otp ALTER COLUMN user_id TYPE VARCHAR(255);
ALTER TABLE otp ALTER COLUMN user_id DROP NOT NULL;
ALTER TABLE otp ALTER COLUMN user_id SET DEFAULT NULL;
