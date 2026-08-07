CREATE TABLE t_reputation_events (
    id BIGSERIAL PRIMARY KEY,
    user_id INTEGER NOT NULL REFERENCES t_users(id) ON DELETE CASCADE,
    source_type VARCHAR(50) NOT NULL,
    source_id VARCHAR(100) NOT NULL,
    points INTEGER NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_reputation_event UNIQUE (user_id, source_type, source_id)
);

CREATE INDEX idx_reputation_events_user ON t_reputation_events(user_id);

ALTER TABLE t_users ADD COLUMN elite_score INTEGER NOT NULL DEFAULT 0;

-- Backfill: existing verified roadmap evidence and accepted project applications
-- already represent earned reputation: award it now so current users don't show 0.
-- source_id matches SkillVerificationService's (userId, nodeId) natural key, not the progress
-- row's generated id, so a later re-run of the real verification flow for the same node can never
-- double-award against this backfilled row.
INSERT INTO t_reputation_events (user_id, source_type, source_id, points)
SELECT
    p.user_id,
    'ROADMAP_NODE_VERIFIED',
    p.user_id::text || ':' || p.node_id::text,
    20
FROM t_user_roadmap_progress p
WHERE p.status = 'VERIFIED'
ON CONFLICT (user_id, source_type, source_id) DO NOTHING;

INSERT INTO t_reputation_events (user_id, source_type, source_id, points)
SELECT
    a.applicant_id,
    'PROJECT_APPLICATION_ACCEPTED',
    a.id::text,
    10
FROM t_project_applications a
WHERE a.status = 'ACCEPTED'
ON CONFLICT (user_id, source_type, source_id) DO NOTHING;

UPDATE t_users u
SET elite_score = COALESCE(sub.total, 0)
FROM (
    SELECT user_id, SUM(points) AS total
    FROM t_reputation_events
    GROUP BY user_id
) sub
WHERE u.id = sub.user_id;
