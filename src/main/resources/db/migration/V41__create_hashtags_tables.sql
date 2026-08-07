CREATE TABLE IF NOT EXISTS socialapp.t_hashtags (
    id SERIAL PRIMARY KEY,
    name VARCHAR(50) NOT NULL UNIQUE,
    usage_count INT DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS socialapp.t_post_hashtags (
    post_id INT NOT NULL,
    hashtag_id INT NOT NULL,
    PRIMARY KEY (post_id, hashtag_id),
    CONSTRAINT fk_post_hashtags_post FOREIGN KEY (post_id) REFERENCES socialapp.t_posts(id) ON DELETE CASCADE,
    CONSTRAINT fk_post_hashtags_tag FOREIGN KEY (hashtag_id) REFERENCES socialapp.t_hashtags(id) ON DELETE CASCADE
);

INSERT INTO socialapp.t_hashtags (name, usage_count) VALUES 
('java', 0), ('springboot', 0), ('react', 0), ('nodejs', 0), ('systemdesign', 0), ('career', 0)
ON CONFLICT (name) DO NOTHING;
