package com.socialapp.notifications;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The template keys and argument maps that go into {@code NotificationResponseDto.messageKey} /
 * {@code messageArgs} — FE's {@code docs/backend-plan.md} B40 (notification i18n).
 *
 * <p>Each producer sends one of these keys plus the variables its sentence needs; the client owns
 * the wording in every language it supports and just interpolates. The English {@code title} /
 * {@code body} on the notification are kept as-is — they are the text for push and email, and the
 * fallback for rows written before this existed.
 *
 * <p>Keys are a published contract. They match the sub-keys FE keeps under {@code
 * notifications.line.*}: one per {@link com.socialapp.notifications.entity.enums.NotificationType},
 * plus the two {@code EVENT_RSVP_*} variants that read differently ("is going to" vs "is interested
 * in"). Rename one only in step with the client.
 *
 * <p>Common argument names: {@code actor} (who acted — already resolved to a display name, or the
 * literal {@code "Someone"} when the user has no name set, which the client localises), {@code
 * book} / {@code project} / {@code event} / {@code skill} (the quoted subject of the sentence).
 */
public final class NotificationMessages {

  private NotificationMessages() {}

  public static final String POST_LIKED = "POST_LIKED";
  public static final String COMMENT_LIKED = "COMMENT_LIKED";
  public static final String POST_COMMENTED = "POST_COMMENTED";
  public static final String POST_TAGGED = "POST_TAGGED";
  public static final String USER_MENTIONED = "USER_MENTIONED";
  public static final String FRIEND_REQUEST = "FRIEND_REQUEST";
  public static final String FRIEND_ACCEPTED = "FRIEND_ACCEPTED";
  public static final String EVENT_RSVP_GOING = "EVENT_RSVP_GOING";
  public static final String EVENT_RSVP_INTERESTED = "EVENT_RSVP_INTERESTED";
  public static final String EVENT_REMINDER = "EVENT_REMINDER";
  public static final String BOOK_REVIEW = "BOOK_REVIEW";
  public static final String BOOK_PURCHASED = "BOOK_PURCHASED";
  public static final String SKILL_VERIFIED = "SKILL_VERIFIED";
  public static final String SKILL_REJECTED = "SKILL_REJECTED";
  public static final String PROJECT_APPLICATION_ACCEPTED = "PROJECT_APPLICATION_ACCEPTED";
  public static final String PROJECT_APPLICATION_REJECTED = "PROJECT_APPLICATION_REJECTED";
  public static final String PROJECT_MEMBER_REMOVED = "PROJECT_MEMBER_REMOVED";

  /**
   * Builds an argument map from alternating key/value pairs, preserving order and rejecting a null
   * value (a missing variable is a bug in the caller, not something to paper over at render time).
   */
  public static Map<String, String> args(String... keyValues) {
    if (keyValues.length % 2 != 0) {
      throw new IllegalArgumentException(
          "args() needs an even number of arguments (key, value, …)");
    }
    Map<String, String> map = new LinkedHashMap<>();
    for (int i = 0; i < keyValues.length; i += 2) {
      String key = keyValues[i];
      String value = keyValues[i + 1];
      if (value == null) {
        throw new IllegalArgumentException("Null value for notification message arg '" + key + "'");
      }
      map.put(key, value);
    }
    return map;
  }
}
