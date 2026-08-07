-- An appeal is a banned or sanctioned user's only way to say "this was wrong". Until now the
-- product enforced sanctions and offered nothing on the other side: the 403 said "please contact
-- support" and there was no support to contact.
CREATE TABLE socialapp.t_moderation_appeals (
    id BIGSERIAL PRIMARY KEY,

    user_id INT NOT NULL REFERENCES socialapp.t_users(id) ON DELETE CASCADE,

    -- The violation being appealed.
    --
    -- NULLable, and ON DELETE SET NULL rather than CASCADE. CASCADE reads like the obvious choice
    -- and is exactly wrong here: upholding an appeal DELETES the violation (see
    -- UserBanService.revokeViolation), so cascading would delete the appeal at the moment it
    -- succeeded. The user would win and their record of winning — the reviewer's note included —
    -- would vanish from "my appeals", leaving only the appeals they lost.
    violation_id BIGINT REFERENCES socialapp.t_user_violations(id) ON DELETE SET NULL,

    -- What the user wrote. Bounded rather than TEXT: this is a form field a locked-out and
    -- frustrated person types into, and an unbounded one is a free write-amplification endpoint.
    reason VARCHAR(2000) NOT NULL,

    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',

    -- Filled in when an admin decides. reviewer_id is SET NULL rather than CASCADE: deleting a
    -- staff account must not erase the decisions they made.
    reviewer_id INT REFERENCES socialapp.t_users(id) ON DELETE SET NULL,
    reviewer_note VARCHAR(2000),
    reviewed_at TIMESTAMP WITH TIME ZONE,

    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- One open appeal per violation. Without this, refreshing the submit form is a way to flood the
-- admin queue with copies of the same complaint; the partial index lets a user appeal again only
-- after the first attempt has actually been decided.
--
-- A NULL violation_id does not collide in a unique index, which is the behaviour we want: those
-- rows are decided appeals whose violation was removed, and they are not competing for anything.
CREATE UNIQUE INDEX uq_appeal_one_pending_per_violation
    ON socialapp.t_moderation_appeals (violation_id)
    WHERE status = 'PENDING';

-- The admin queue reads "oldest pending first"; the user's own list reads by user.
CREATE INDEX idx_appeals_status_created ON socialapp.t_moderation_appeals (status, created_at);
CREATE INDEX idx_appeals_user_id ON socialapp.t_moderation_appeals (user_id, created_at DESC);
