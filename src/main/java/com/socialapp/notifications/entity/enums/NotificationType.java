package com.socialapp.notifications.entity.enums;

/**
 * What a notification is about — one constant per kind this backend actually sends, nothing more.
 *
 * <p>{@code POST_SHARED} and {@code SYSTEM} were removed because no code path could produce either.
 * {@code POST_SHARED} was waiting on a share feature that does not exist: there is no share or
 * repost endpoint anywhere, so nothing ever reaches the point of notifying an author about one.
 * {@code SYSTEM} was reserved for broadcasts, and the only way to send any notification is {@code
 * NotificationService.send}, which no controller exposes — a value no caller can reach is not a
 * reservation, it is a promise the API does not keep. {@code EVENT_RSVP} and {@code EVENT_REMINDER}
 * were in the same state and got producers instead; these two had no feature behind them to wire
 * up.
 *
 * <p>Dropping the constants narrows {@code NotificationResponseDto.type} in the OpenAPI contract, so
 * the generated client has to be regenerated in step. It is safe on the data side: {@code
 * t_notifications.type} is a plain {@code varchar(50)} with no check constraint and no row has ever
 * carried either value, and {@code t_notification_preferences.muted_types} holds {@code
 * List<String>} rather than this enum, so a preference naming a dropped type still deserialises.
 * Note that the column is read through {@code @Enumerated(EnumType.STRING)}, so if a row ever did
 * carry one of these, reading it would throw — check before removing any further constant.
 *
 * <p>Building the share feature means adding {@code POST_SHARED} back here together with {@code
 * FeedPostDataDto.shareCount}, which was dropped in the same pass for the same reason.
 */
public enum NotificationType {
  POST_LIKED,
  POST_COMMENTED,
  POST_TAGGED,
  FRIEND_REQUEST,
  FRIEND_ACCEPTED,
  EVENT_RSVP,
  EVENT_REMINDER,
  BOOK_REVIEW,
  BOOK_PURCHASED
}
