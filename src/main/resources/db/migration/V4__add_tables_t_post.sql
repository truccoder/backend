CREATE SEQUENCE IF NOT EXISTS socialapp.q_posts_id START WITH 1 INCREMENT BY 10;

CREATE TABLE IF NOT EXISTS socialapp.t_posts (
    id int DEFAULT nextval('q_posts_id') PRIMARY KEY,
    content VARCHAR,
    google_place_id VARCHAR,
    location_type VARCHAR,
    location_details JSONB,
    author_id int REFERENCES t_users(id),
    created_at TIMESTAMP with time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP with time zone
);