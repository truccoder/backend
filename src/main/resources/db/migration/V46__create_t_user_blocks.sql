-- Blocking, which the app has never had.
--
-- It is arriving now because the product is opening up: public profiles, a discovery feed of
-- everyone's public posts, and messaging that no longer requires being friends. Each of those
-- exposes a user to strangers, and the only tool they had against an unwanted stranger was to not
-- be their friend — which those three changes have just made irrelevant. An open app without a
-- block button is an app that is not ready to be open.

CREATE TABLE t_user_blocks (
    blocker_id INTEGER NOT NULL REFERENCES t_users(id) ON DELETE CASCADE,
    blocked_id INTEGER NOT NULL REFERENCES t_users(id) ON DELETE CASCADE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,

    -- The pair is the identity of the row: blocking someone twice is the same block, and the
    -- primary key is what makes a repeated POST idempotent at the database rather than only in
    -- whichever service happened to check first.
    CONSTRAINT pk_user_blocks PRIMARY KEY (blocker_id, blocked_id),

    -- Self-blocking is meaningless, and left unguarded it would silently remove the user from
    -- their own feed and search results, since the filter treats the relation as two-way.
    CONSTRAINT ck_user_blocks_not_self CHECK (blocker_id <> blocked_id)
);

-- The primary key already indexes (blocker_id, blocked_id), which serves "who have I blocked".
-- This one serves the other half of every filter — "who has blocked me" — which is read just as
-- often: blocking is one-sided as an action but two-way as a filter, so every feed, search and
-- notification path looks up both directions on the same call.
CREATE INDEX idx_user_blocks_blocked ON t_user_blocks(blocked_id);
