CREATE TABLE IF NOT EXISTS password (
    id BIGSERIAL PRIMARY KEY,
    password TEXT,
    name TEXT,
    website TEXT,
    username TEXT,
    user_id TEXT
);

CREATE INDEX IF NOT EXISTS idx_password_user_id ON password(user_id);
CREATE INDEX IF NOT EXISTS idx_password_user_id_name_website ON password(user_id, name, website);
