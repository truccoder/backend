ALTER TABLE socialapp.t_posts ADD COLUMN moderation_status VARCHAR;

CREATE SEQUENCE IF NOT EXISTS q_moderation_logs_id INCREMENT BY 1 MINVALUE 1 NO MAXVALUE START WITH 1 CACHE 10 NO CYCLE;

CREATE TABLE socialapp.t_moderation_logs (
     id int DEFAULT nextval('q_moderation_logs_id') PRIMARY KEY,
     post_id INT REFERENCES socialapp.t_posts(id) ON DELETE CASCADE,
     status VARCHAR,
     violation_type VARCHAR,
     text_toxicity_score DECIMAL(4,3),
     image_safe_score DECIMAL(4,3),
     rule_violations JSONB,
     reviewed_at TIMESTAMP WITH TIME ZONE,
     created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_moderation_logs_post_id ON socialapp.t_moderation_logs(post_id);
CREATE INDEX idx_posts_moderation_status ON socialapp.t_posts(moderation_status);

CREATE TABLE socialapp.t_user_violations (
    id BIGSERIAL PRIMARY KEY,
    user_id INT NOT NULL REFERENCES socialapp.t_users(id) ON DELETE CASCADE,
    post_id INT REFERENCES socialapp.t_posts(id) ON DELETE SET NULL,
    violation_type VARCHAR(50) NOT NULL,
    severity VARCHAR(20) NOT NULL,
    description VARCHAR(500),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

ALTER TABLE socialapp.t_users ADD COLUMN banned_until TIMESTAMP WITH TIME ZONE;

CREATE INDEX idx_user_violations_user_id ON socialapp.t_user_violations(user_id);
CREATE INDEX idx_user_violations_created_at ON socialapp.t_user_violations(user_id, created_at);
