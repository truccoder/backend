package com.socialapp.moderation.dto;

import java.time.OffsetDateTime;

import com.socialapp.moderation.entity.UserViolationEntity;
import com.socialapp.moderation.enums.ViolationSeverity;
import com.socialapp.moderation.enums.ViolationType;

import lombok.Builder;
import lombok.Data;

/**
 * A violation as the person it was recorded against sees it.
 *
 * <p>Nobody could see their own record before: sanctions were applied and the only signal was a
 * 403. An appeal is not a real option if you cannot find out what you are appealing, which is why
 * this exists alongside {@code AppealDto}.
 */
@Data
@Builder
public class UserViolationDto {
  private Long id;
  private Integer postId;
  private ViolationType violationType;
  private ViolationSeverity severity;
  private String description;
  private OffsetDateTime createdAt;

  /** Whether an appeal for this violation is already open — so the UI can hide the button. */
  private boolean appealPending;

  public static UserViolationDto from(UserViolationEntity violation, boolean appealPending) {
    return UserViolationDto.builder()
        .id(violation.getId())
        .postId(violation.getPostId())
        .violationType(violation.getViolationType())
        .severity(violation.getSeverity())
        .description(violation.getDescription())
        .createdAt(violation.getCreatedAt())
        .appealPending(appealPending)
        .build();
  }
}
