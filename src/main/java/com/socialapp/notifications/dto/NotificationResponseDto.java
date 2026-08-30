package com.socialapp.notifications.dto;

import java.time.OffsetDateTime;

import com.fasterxml.jackson.annotation.JsonInclude;
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
public class NotificationResponseDto {
  private Integer id;
  private Integer actorId;
  private NotificationType type;
  private String title;
  private String body;
  private Integer referenceId;
  private String referenceType;

  /**
   * Where to send a reader who taps a {@code COMMENT} notification.
   *
   * <p>{@code referenceId} is deliberately the comment id — a thread can run to hundreds of
   * replies and the point is to open at the one that named you — but no client route is keyed by
   * comment id, so on its own it addresses nothing. This is the other half of that address, and it
   * unlocks {@code USER_MENTIONED} and {@code COMMENT_LIKED} together.
   *
   * <p>Omitted from the payload rather than sent as null when there is no post: this project sets
   * no global {@code NON_NULL}, so without the annotation every friend request and book purchase
   * would carry a {@code "postId": null} that means nothing.
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  private Integer postId;

  private NotificationChannel channel;
  private Boolean isRead;
  private OffsetDateTime createdAt;
}
