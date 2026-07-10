-- Seed posts already sitting in PENDING_REVIEW status, so
-- GET /v1/api/admin/moderation/pending has data without needing to
-- create a post and wait for the async moderation pipeline to run.
--
-- Looks up authors by username (bob/carol from V20) instead of hardcoding their ids: V20 inserts
-- with ON CONFLICT (email) DO NOTHING, so on any database where those emails already existed
-- (e.g. a real production database) those rows - and their expected auto-increment ids - never
-- get created, and a hardcoded author_id here would violate fk_posts_author and fail this
-- migration permanently. The join makes this a no-op wherever the seed users don't exist.
INSERT INTO socialapp.t_posts (content, visibility, author_id, post_type, moderation_status)
SELECT v.content, v.visibility, u.id, v.post_type, v.moderation_status
FROM (VALUES
          ('This deal is insane, buy now buy now buy now!!!', 'PUBLIC', 'bob', 'REGULAR', 'PENDING_REVIEW'),
          ('You are so stupid and worthless, nobody likes you', 'PUBLIC', 'carol', 'REGULAR', 'PENDING_REVIEW')
     ) AS v(content, visibility, username, post_type, moderation_status)
         JOIN socialapp.t_users u ON u.username = v.username;

INSERT INTO socialapp.t_moderation_logs (post_id, status, text_toxicity_score, image_safe_score)
SELECT id, 'PENDING_REVIEW', 0.55, 0.0
FROM socialapp.t_posts
WHERE content = 'This deal is insane, buy now buy now buy now!!!';

INSERT INTO socialapp.t_moderation_logs (post_id, status, text_toxicity_score, image_safe_score)
SELECT id, 'PENDING_REVIEW', 0.68, 0.0
FROM socialapp.t_posts
WHERE content = 'You are so stupid and worthless, nobody likes you';
