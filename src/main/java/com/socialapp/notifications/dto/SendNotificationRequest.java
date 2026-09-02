package com.socialapp.notifications.dto;

import java.util.Map;

import com.socialapp.notifications.entity.enums.NotificationChannel;
import com.socialapp.notifications.entity.enums.NotificationType;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SendNotificationRequest {
  private Integer recipientId;
  private Integer actorId;
  private NotificationType type;
  private String title;
  private String body;

  /**
   * Structured, language-agnostic form of {@link #body} — a {@code NotificationMessages} key and
   * its args. When set, the stored row and the API carry it alongside the English {@code body}
   * (which is still what push/email send). See B40 in {@code docs/backend-plan.md}.
   */
  private String messageKey;

  private Map<String, String> messageArgs;

  private Integer referenceId;
  private String referenceType;

  /** The post containing {@code referenceId} when it is a comment; null for everything else. */
  private Integer postId;

  @Builder.Default private NotificationChannel channel = NotificationChannel.BOTH;
  private Map<String, String> pushData;
}
