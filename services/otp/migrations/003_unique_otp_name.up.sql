ALTER TABLE otp ADD CONSTRAINT uq_otp_user_name UNIQUE (user_id, email);
