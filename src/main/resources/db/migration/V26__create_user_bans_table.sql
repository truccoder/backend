CREATE TABLE socialapp.t_user_bans (
    id           BIGSERIAL PRIMARY KEY,
    user_id      INT                      NOT NULL REFERENCES socialapp.t_users (id) ON DELETE CASCADE,
    post_id      INT                      REFERENCES socialapp.t_posts (id) ON DELETE SET NULL,
    banned_until TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at   TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_user_bans_user_id ON socialapp.t_user_bans (user_id);
