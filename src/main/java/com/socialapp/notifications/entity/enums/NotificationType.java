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

  /**
   * Somebody reacted to one of the caller's comments.
   *
   * <p>The sibling of {@link #POST_LIKED}, added with comment reactions. Reusing {@code POST_LIKED}
   * with a {@code referenceType} of "COMMENT" was the cheaper option and the wrong one: the client
   * routes on the type, so every comment reaction would have opened the post rather than the reply
   * it was about, and a notification list could not have worded the two differently.
   *
   * <p>{@code referenceId} is the COMMENT id, not the post id — see {@code
   * CommentReactionService#notifyCommentAuthor}.
   */
  COMMENT_LIKED,

  POST_COMMENTED,
  POST_TAGGED,
  FRIEND_REQUEST,
  FRIEND_ACCEPTED,
  EVENT_RSVP,
  EVENT_REMINDER,
  BOOK_REVIEW,
  BOOK_PURCHASED,

  /**
   * A moderator approved a skill verification request.
   *
   * <p>This closes the product's central loop — real work, entered in the ledger, turned into
   * reputation — which used to end in silence: {@code SkillVerificationService.approveRequest}
   * awarded the points and told nobody, so the only way to learn that a claim had been accepted was
   * to reopen the page and notice the score had moved.
   */
  SKILL_VERIFIED,

  /**
   * A moderator turned a skill verification request down.
   *
   * <p>Sent for the same reason as {@link #SKILL_VERIFIED} and not as an afterthought: a request
   * that is refused silently is indistinguishable from one still sitting in the queue, so without
   * this the claimant waits forever on an answer that has already been given.
   */
  SKILL_REJECTED
}
