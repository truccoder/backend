CREATE SEQUENCE IF NOT EXISTS q_comments_id INCREMENT BY 1 MINVALUE 1 NO MAXVALUE START WITH 1 CACHE 10 NO CYCLE;

CREATE TABLE IF NOT EXISTS socialapp.t_comments (
                                                    id          INT DEFAULT nextval('q_comments_id') PRIMARY KEY,
    post_id     INT NOT NULL REFERENCES socialapp.t_posts(id) ON DELETE CASCADE,
    author_id   INT NOT NULL REFERENCES socialapp.t_users(id),
    content     VARCHAR NOT NULL,
    parent_id   INT REFERENCES socialapp.t_comments(id) ON DELETE CASCADE,
    created_at  TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP WITH TIME ZONE
                                                                  );

CREATE INDEX IF NOT EXISTS idx_comments_post_id   ON socialapp.t_comments(post_id);
CREATE INDEX IF NOT EXISTS idx_comments_parent_id ON socialapp.t_comments(parent_id);
CREATE INDEX IF NOT EXISTS idx_comments_author_id ON socialapp.t_comments(author_id);