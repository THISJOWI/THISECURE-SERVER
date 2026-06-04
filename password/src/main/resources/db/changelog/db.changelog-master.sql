-- liquibase formatted sql
-- changeset password:1 runOnChange:false
CREATE TABLE IF NOT EXISTS password (
    id BIGSERIAL PRIMARY KEY,
    password TEXT,
    name TEXT,
    website TEXT,
    user_id BIGINT NOT NULL
);

-- changeset password:2 runOnChange:false
CREATE INDEX IF NOT EXISTS idx_password_user_id ON password(user_id);

-- changeset password:3 runOnChange:false
CREATE INDEX IF NOT EXISTS idx_password_user_id_name_website ON password(user_id, name, website);

-- changeset password:4 runOnChange:false
ALTER TABLE password ALTER COLUMN user_id TYPE TEXT;
