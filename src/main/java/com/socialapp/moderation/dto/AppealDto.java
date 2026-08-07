package com.socialapp.moderation.dto;

import java.time.OffsetDateTime;

import com.socialapp.moderation.enums.AppealStatus;
import com.socialapp.moderation.enums.ViolationType;

import lombok.Builder;
import lombok.Data;

/**
 * One appeal, as both sides see it.
 *
 * <p>The appellant's identity fields ({@code userFullName}, {@code username}) are filled in for the
 * admin queue and left null on a user reading their own appeals — they already know who they are,
 * and populating them there would mean hydrating a user row per appeal for nothing.
 *
 * <p>Carries the violation it disputes ({@code violationType}, {@code violationDescription}) so the
 * admin queue does not need a second call per row to know what is being argued about, and so the
 * user's own list shows what they appealed rather than an opaque id.
 */
@Data
@Builder
public class AppealDto {
  private Long id;

  private Integer userId;
  private String username;
  private String userFullName;

  private Long violationId;
  private ViolationType violationType;
  private String violationDescription;

  private String reason;
  private AppealStatus status;
  private String reviewerNote;
  private OffsetDateTime reviewedAt;
  private OffsetDateTime createdAt;
}
