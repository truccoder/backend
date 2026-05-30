CREATE SEQUENCE IF NOT EXISTS q_user_interactions_id INCREMENT BY 1 MINVALUE 1 NO MAXVALUE START WITH 1 CACHE 10 NO CYCLE;

CREATE TABLE socialapp.t_user_interactions (
    id int DEFAULT nextval('q_user_interactions_id') PRIMARY KEY,
    user_id INT,
    post_id INT,
    author_id INT,
    type VARCHAR,
    created_at TIMESTAMP with time zone DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_user_interactions_user_author ON socialapp.t_user_interactions(user_id, author_id);
CREATE INDEX idx_user_interactions_user_created ON socialapp.t_user_interactions(user_id, created_at);
