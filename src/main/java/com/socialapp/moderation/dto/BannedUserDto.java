package com.socialapp.moderation.dto;

import java.time.OffsetDateTime;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BannedUserDto {
  private Integer userId;
  private String email;
  private String fullName;
  private boolean currentlyBanned;
  private OffsetDateTime bannedUntil;
  private long remainingSeconds;

  /**
   * Total number of times this user has been banned; the latest ban is the Nth one.
   */
  private long banCount;

  /**
   * Post IDs that triggered this user's bans; frontend resolves/redirects to each.
   */
  private List<Integer> triggeringPostIds;
}
