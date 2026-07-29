package com.socialapp.notifications.entity.enums;

/**
 * How often a user wants notification email — one constant per rhythm this backend actually
 * honours, nothing more.
 *
 * <p>{@code DAILY_DIGEST} and {@code WEEKLY_DIGEST} were removed because nothing ever batched
 * anything. The value was stored by {@code NotificationService.updatePreference} and echoed back in
 * {@code NotificationPreferenceResponseDto}, but {@code shouldSendEmail} never read it and no
 * scheduler existed to assemble a digest, so a user asking for a weekly summary kept receiving an
 * instant email per like. Offering a setting that changes nothing is worse than not offering it:
 * the round-trip looks like it worked.
 *
 * <p>{@code NONE} stayed and is now enforced — see {@code NotificationService.shouldSendEmail}. It
 * was in the same broken state before this change.
 *
 * <p>Dropping the constants narrows {@code NotificationPreferenceResponseDto.emailFrequency} in the
 * OpenAPI contract, so the generated client has to be regenerated in step.
 *
 * <p><b>The data side is NOT guaranteed safe, only measured safe at one moment.</b> There is no
 * migration behind this change. {@code t_notification_preferences.email_frequency} is {@code
 * VARCHAR(20) DEFAULT 'INSTANT'} with no check constraint, so the database will still accept and
 * keep a removed value — written by an older instance, a restored dump, or a seed script. Every row
 * carried {@code INSTANT} when the constants were dropped, and that is a snapshot, not an invariant.
 *
 * <p>The column is read through {@code @Enumerated(EnumType.STRING)}, and a row carrying a removed
 * value throws on read. Measured, not assumed — with one row set to {@code WEEKLY_DIGEST}:
 *
 * <pre>
 *   GET /v1/api/notifications/preferences → 500
 *     "No enum constant …EmailFrequency.WEEKLY_DIGEST"   (leaks the internal name to the client)
 *   GET /v1/api/notifications → 200      other reads are unaffected
 *   GET /v1/api/feed          → 200
 * </pre>
 *
 * Worse than the 500: {@code NotificationService.send} reads the preference as its first statement,
 * and {@code send} is {@code @Async} — so for an affected recipient the throw happens off-thread and
 * the notification is dropped without ever being saved. (Read from the code path, not measured.)
 *
 * <p>So before running anything against a database this code has not seen, check:
 *
 * <pre>
 *   select distinct email_frequency from socialapp.t_notification_preferences;
 * </pre>
 *
 * Anything other than {@code INSTANT} / {@code NONE} means the database predates {@code V45}.
 *
 * <p>{@code V45__constrain_email_frequency.sql} closes this: it repairs stored values and adds a
 * CHECK constraint so the column can no longer hold anything this enum cannot read. Consequently
 * <b>adding a constant here now requires a migration widening that constraint</b> — forgetting one
 * makes the insert fail, which is the intended trade: loud on first run beats silent in production.
 *
 * <p>Bringing digests back means adding the constants here together with the scheduler and the
 * "batched up to" bookmark that would make them mean something.
 */
public enum EmailFrequency {
  INSTANT,
  NONE
}
