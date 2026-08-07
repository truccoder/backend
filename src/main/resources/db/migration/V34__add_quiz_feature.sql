ALTER TABLE t_posts ADD COLUMN quiz_details JSONB;

CREATE TABLE t_quiz_answers (
    id SERIAL PRIMARY KEY,
    post_id INTEGER NOT NULL REFERENCES t_posts(id) ON DELETE CASCADE,
    user_id INTEGER NOT NULL REFERENCES t_users(id) ON DELETE CASCADE,
    answers JSONB NOT NULL,
    score INTEGER NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_quiz_answers_post_id ON t_quiz_answers(post_id);
CREATE INDEX idx_quiz_answers_user_id ON t_quiz_answers(user_id);
