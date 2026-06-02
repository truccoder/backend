-- User professional profile for AI personalization
CREATE TABLE socialapp.t_user_professional_profiles (
    user_id INT PRIMARY KEY REFERENCES socialapp.t_users(id) ON DELETE CASCADE,
    job_title VARCHAR(100),
    seniority_level VARCHAR(20) NOT NULL DEFAULT 'JUNIOR',
    years_of_experience INT DEFAULT 0,
    known_tech_stack JSONB DEFAULT '[]'::jsonb,
    work_history JSONB DEFAULT '[]'::jsonb,
    interested_domains JSONB DEFAULT '[]'::jsonb,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- Cached AI explanations per post per user profile level
CREATE SEQUENCE IF NOT EXISTS q_explanations_id INCREMENT BY 1 MINVALUE 1 NO MAXVALUE START WITH 1 CACHE 10 NO CYCLE;

CREATE TABLE socialapp.t_explanations (
    id INT DEFAULT nextval('q_explanations_id') PRIMARY KEY,
    post_id INT NOT NULL REFERENCES socialapp.t_posts(id) ON DELETE CASCADE,
    user_id INT NOT NULL REFERENCES socialapp.t_users(id) ON DELETE CASCADE,
    original_content TEXT NOT NULL,
    explanation_content TEXT NOT NULL,
    concepts JSONB DEFAULT '[]'::jsonb,
    prerequisites JSONB DEFAULT '[]'::jsonb,
    complexity_score INT DEFAULT 1,
    feedback_note TEXT,
    version INT DEFAULT 1,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(post_id, user_id, version)
);

CREATE INDEX idx_explanations_user_id ON socialapp.t_explanations(user_id);
CREATE INDEX idx_explanations_post_id ON socialapp.t_explanations(post_id);

-- Personal access tokens for Obsidian sync
CREATE SEQUENCE IF NOT EXISTS q_access_tokens_id INCREMENT BY 1 MINVALUE 1 NO MAXVALUE START WITH 1 CACHE 10 NO CYCLE;

CREATE TABLE socialapp.t_personal_access_tokens (
    id INT DEFAULT nextval('q_access_tokens_id') PRIMARY KEY,
    user_id INT NOT NULL REFERENCES socialapp.t_users(id) ON DELETE CASCADE,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    name VARCHAR(100) NOT NULL,
    last_used_at TIMESTAMP WITH TIME ZONE,
    expires_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_access_tokens_user_id ON socialapp.t_personal_access_tokens(user_id);
CREATE INDEX idx_access_tokens_token_hash ON socialapp.t_personal_access_tokens(token_hash);
