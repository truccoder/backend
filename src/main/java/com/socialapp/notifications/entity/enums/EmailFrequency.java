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
 * OpenAPI contract, so the generated client has to be regenerated in step. It is safe on the data
 * side: {@code t_notification_preferences.email_frequency} is {@code VARCHAR(20) DEFAULT 'INSTANT'}
 * with no check constraint, and every row measured before the change carried {@code INSTANT}. The
 * column is read through {@code @Enumerated(EnumType.STRING)}, so a row carrying a removed value
 * would throw on read — check the table before removing any further constant.
 *
 * <p>Bringing digests back means adding the constants here together with the scheduler and the
 * "batched up to" bookmark that would make them mean something.
 */
public enum EmailFrequency {
  INSTANT,
  NONE
}
