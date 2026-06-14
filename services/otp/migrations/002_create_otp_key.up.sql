CREATE TABLE IF NOT EXISTS otp_key (
    id BIGSERIAL PRIMARY KEY,
    user_id VARCHAR(255),
    otp VARCHAR(255),
    created_at TIMESTAMP DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_otp_key_user_id ON otp_key(user_id);
