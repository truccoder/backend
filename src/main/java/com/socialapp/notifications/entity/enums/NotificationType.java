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
 *
 * <p>Adding a constant is the safe direction and needs no migration — the column is a plain {@code
 * varchar(50)} with no check constraint — but it widens {@code NotificationResponseDto.type} in the
 * OpenAPI contract, so the generated client has to be regenerated in step.
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

  /**
   * Somebody wrote {@code @handle} in a comment and the handle is the caller's.
   *
   * <p>The comment-level counterpart of {@link #POST_TAGGED}, and the same asymmetry that comment
   * reactions closed one release earlier: a post has always had structured tagging ({@code
   * CreatePostRequest.taggedUserIds}) and a notification to go with it, while comments — where
   * people actually address each other by name — had neither. A reply that named you was
   * discoverable only by reopening the post.
   *
   * <p>Its own constant rather than reusing {@code POST_TAGGED} with a {@code referenceType} of
   * "COMMENT", for the reason {@link #COMMENT_LIKED} spells out: the client routes on the type, so
   * sharing one would open the post instead of the reply, and the two could not be worded
   * differently in a notification list.
   *
   * <p>{@code referenceId} is the COMMENT id — see {@code CommentService#notifyMentionedUsers}.
   */
  USER_MENTIONED,
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
