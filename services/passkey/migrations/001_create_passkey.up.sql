CREATE TABLE IF NOT EXISTS passkey (
    id BIGSERIAL PRIMARY KEY,
    credential_id TEXT NOT NULL,
    public_key TEXT,
    rp_id TEXT,
    rp_name TEXT,
    user_handle TEXT,
    user_display_name TEXT,
    sign_count BIGINT DEFAULT 0,
    name TEXT,
    transports TEXT[],
    credential_type TEXT DEFAULT 'public-key',
    backup_eligible BOOLEAN DEFAULT FALSE,
    backup_state BOOLEAN DEFAULT FALSE,
    user_id TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_passkey_user_id ON passkey(user_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_passkey_user_credential ON passkey(user_id, credential_id);
