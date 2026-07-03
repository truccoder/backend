ALTER TABLE socialapp.t_users ADD COLUMN email_verified BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE IF NOT EXISTS socialapp.t_email_verification_tokens (
    token      VARCHAR(255) PRIMARY KEY,
    user_id    INT,
    expires_at TIMESTAMP WITH TIME ZONE
);

CREATE TABLE IF NOT EXISTS socialapp.t_magic_link_tokens (
    token      VARCHAR(255) PRIMARY KEY,
    user_id    INT,
    expires_at TIMESTAMP WITH TIME ZONE
);
