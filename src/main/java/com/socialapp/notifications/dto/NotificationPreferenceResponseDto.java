package com.socialapp.notifications.dto;

import java.time.OffsetDateTime;
import java.util.List;

import com.socialapp.notifications.entity.enums.EmailFrequency;

import lombok.Builder;
import lombok.Data;

/**
 * Read view of a user's notification settings. Deliberately omits {@code onesignalPlayerId}: the
 * client is the one that produces that device token and pushes it up, so echoing it back buys
 * nothing and puts a push-addressable device handle in every settings response.
 */
@Data
@Builder
public class NotificationPreferenceResponseDto {
  private Integer userId;
  private Boolean pushEnabled;
  private Boolean emailEnabled;
  private EmailFrequency emailFrequency;
  private List<String> mutedTypes;
  private OffsetDateTime updatedAt;
}
