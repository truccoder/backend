package com.socialapp.notifications.dto;

import java.time.OffsetDateTime;

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
  private NotificationChannel channel;
  private Boolean isRead;
  private OffsetDateTime createdAt;
}
