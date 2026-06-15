ALTER TABLE password ADD CONSTRAINT uq_password_user_name_website UNIQUE (user_id, name, website);
