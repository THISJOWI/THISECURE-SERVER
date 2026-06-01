CREATE TABLE IF NOT EXISTS notes (
    id BIGSERIAL PRIMARY KEY,
    content TEXT,
    title TEXT NOT NULL,
    created_at TIMESTAMP,
    user_id BIGINT,
    version BIGINT DEFAULT 0 NOT NULL,
    CONSTRAINT uk_title_user UNIQUE (title, user_id)
);

CREATE INDEX IF NOT EXISTS idx_notes_user_id ON notes(user_id);
CREATE INDEX IF NOT EXISTS idx_notes_title ON notes(title);
CREATE INDEX IF NOT EXISTS idx_notes_created_at ON notes(created_at);
