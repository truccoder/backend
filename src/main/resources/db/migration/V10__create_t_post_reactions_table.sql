CREATE TABLE IF NOT EXISTS socialapp.t_post_reactions (
    user_id       INT NOT NULL REFERENCES socialapp.t_users(id) ON DELETE CASCADE,
    post_id       INT NOT NULL REFERENCES socialapp.t_posts(id) ON DELETE CASCADE,
    reaction_type VARCHAR(20) NOT NULL,
    created_at    TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, post_id)
);

CREATE INDEX idx_post_reactions_post_id ON socialapp.t_post_reactions(post_id);
CREATE INDEX idx_post_reactions_post_type ON socialapp.t_post_reactions(post_id, reaction_type);