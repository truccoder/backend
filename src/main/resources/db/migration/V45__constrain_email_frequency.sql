-- EmailFrequency dropped DAILY_DIGEST and WEEKLY_DIGEST: both were stored and echoed back by the
-- API but never read when deciding to send, and no scheduler ever assembled a digest, so asking
-- for a weekly summary still produced an instant email per like.
--
-- That change shipped WITHOUT this migration. Every row happened to carry INSTANT when it was
-- made, which is a snapshot, not an invariant: the column is VARCHAR(20) with no constraint, so an
-- older instance, a restored dump, or a seed script could still put a dropped value back. The
-- entity reads it through @Enumerated(EnumType.STRING), and reading a dropped value throws.
-- Measured, with one row set to WEEKLY_DIGEST:
--     GET /v1/api/notifications/preferences -> 500 "No enum constant ...EmailFrequency.WEEKLY_DIGEST"
-- and worse, NotificationService.send reads the preference as its first statement while being
-- @Async, so for an affected recipient the notification is dropped off-thread without being saved.

-- 1. Fix whatever is already stored. Deliberately `NOT IN (...)` rather than naming the two dropped
-- values: the point is to leave the table holding only what the enum can read, whatever junk got in.
-- NULL is left alone — the code treats it as "send", the same as INSTANT.
UPDATE socialapp.t_notification_preferences
   SET email_frequency = 'INSTANT'
 WHERE email_frequency IS NOT NULL
   AND email_frequency NOT IN ('INSTANT', 'NONE');

-- 2. Stop the next one at the door. Without this the fix above is good only until the next writer,
-- which is exactly how the situation arose the first time.
--
-- The cost is real and worth stating: adding a constant to EmailFrequency now REQUIRES a migration
-- to widen this constraint, and forgetting it makes the insert fail. That trade is deliberate — a
-- failed insert is loud and shows up the first time anyone runs the app, whereas an unconstrained
-- bad value is silent until it drops someone's notification in production.
ALTER TABLE socialapp.t_notification_preferences
    ADD CONSTRAINT ck_notification_preferences_email_frequency
    CHECK (email_frequency IS NULL OR email_frequency IN ('INSTANT', 'NONE'));
