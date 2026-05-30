DROP TABLE socialapp.t_posts;

CREATE TABLE IF NOT EXISTS socialapp.t_posts (
    id int DEFAULT nextval('q_posts_id') PRIMARY KEY,
    content VARCHAR,
    visibility VARCHAR,
    images JSONB,
    google_place_id VARCHAR,
    location_type VARCHAR,
    location_details JSONB,
    author_id INT,
    created_at TIMESTAMP with time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP with time zone,

    CONSTRAINT fk_posts_author FOREIGN KEY (author_id)
    REFERENCES socialapp.t_users(id) ON DELETE CASCADE
    );

CREATE TABLE socialapp.t_post_tags (
   post_id INT,
   tagged_user_id INT,
   PRIMARY KEY (post_id, tagged_user_id),

   CONSTRAINT fk_post_tags_post FOREIGN KEY (post_id)
   REFERENCES socialapp.t_posts(id) ON DELETE CASCADE,

   CONSTRAINT fk_post_tags_user FOREIGN KEY (tagged_user_id)
   REFERENCES socialapp.t_users(id) ON DELETE CASCADE
);