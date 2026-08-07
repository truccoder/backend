CREATE SEQUENCE IF NOT EXISTS q_user_github_stats_id INCREMENT BY 1 MINVALUE 1 NO MAXVALUE START WITH 1 CACHE 10 NO CYCLE;

CREATE TABLE IF NOT EXISTS t_user_github_stats (
    id bigint DEFAULT nextval('q_user_github_stats_id') PRIMARY KEY,
    user_id bigint NOT NULL UNIQUE,
    github_username VARCHAR(255) NOT NULL,
    access_token VARCHAR(255),
    public_repos_count INTEGER DEFAULT 0,
    followers_count INTEGER DEFAULT 0,
    pinned_repos_json JSONB,
    contribution_graph_json JSONB,
    last_synced_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT fk_github_stats_user FOREIGN KEY (user_id) REFERENCES t_users(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_github_stats_last_synced ON t_user_github_stats(last_synced_at);
