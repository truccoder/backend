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
  private Integer referenceId;
  private String referenceType;
  @Builder.Default private NotificationChannel channel = NotificationChannel.BOTH;
  private Map<String, String> pushData;
}
