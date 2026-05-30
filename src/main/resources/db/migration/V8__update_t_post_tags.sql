DROP TABLE socialapp.t_post_tags;

CREATE TABLE IF NOT EXISTS socialapp.t_post_tags (
    post_id INT NOT NULL,
    position INT NOT NULL,
    tagged_user_id INT NOT NULL,
    PRIMARY KEY (post_id, position),

    CONSTRAINT fk_post_tags_post
    FOREIGN KEY (post_id)
    REFERENCES socialapp.t_posts(id)
    ON DELETE CASCADE,

    CONSTRAINT fk_post_tags_user
    FOREIGN KEY (tagged_user_id)
    REFERENCES socialapp.t_users(id)
    ON DELETE CASCADE
);