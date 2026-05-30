CREATE SEQUENCE IF NOT EXISTS q_posts_id INCREMENT BY 1 MINVALUE 1 NO MAXVALUE START WITH 1 CACHE 10 NO CYCLE;

CREATE TABLE IF NOT EXISTS socialapp.t_posts (
    id int DEFAULT nextval('q_posts_id') Primary key,
    content VARCHAR,
    google_place_id VARCHAR,
    location_type VARCHAR,
    location_details JSONB,
    author_id int REFERENCES t_users(id),
    created_at TIMESTAMP with time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP with time zone
);