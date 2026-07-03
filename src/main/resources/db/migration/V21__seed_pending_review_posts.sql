-- Seed posts already sitting in PENDING_REVIEW status, so
-- GET /v1/api/admin/moderation/pending has data without needing to
-- create a post and wait for the async moderation pipeline to run.
INSERT INTO socialapp.t_posts (content, visibility, author_id, post_type, moderation_status) VALUES
    ('This deal is insane, buy now buy now buy now!!!', 'PUBLIC', 2, 'REGULAR', 'PENDING_REVIEW'),
    ('You are so stupid and worthless, nobody likes you', 'PUBLIC', 3, 'REGULAR', 'PENDING_REVIEW');

INSERT INTO socialapp.t_moderation_logs (post_id, status, text_toxicity_score, image_safe_score)
SELECT id, 'PENDING_REVIEW', 0.55, 0.0
FROM socialapp.t_posts
WHERE content = 'This deal is insane, buy now buy now buy now!!!';

INSERT INTO socialapp.t_moderation_logs (post_id, status, text_toxicity_score, image_safe_score)
SELECT id, 'PENDING_REVIEW', 0.68, 0.0
FROM socialapp.t_posts
WHERE content = 'You are so stupid and worthless, nobody likes you';
