CREATE TABLE IF NOT EXISTS socialapp.t_password_reset_tokens (
    token      VARCHAR(255) PRIMARY KEY,
    user_id    INT,
    expires_at TIMESTAMP WITH TIME ZONE
);
