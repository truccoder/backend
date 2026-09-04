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

  /**
   * The disputed post, and a snapshot of its content taken when the violation was recorded — same
   * fields and same reasoning as {@code UserViolationDto.postId}/{@code postExcerpt} (B47): {@code
   * postId} goes null if the post is later deleted ({@code ON DELETE SET NULL}), so the excerpt is
   * what keeps "which post" answerable. Both null once the appeal is approved and its violation
   * erased, same as {@code violationType}/{@code violationDescription} above.
   */
  private Integer postId;

  private String postExcerpt;

  private String reason;
  private AppealStatus status;
  private String reviewerNote;
  private OffsetDateTime reviewedAt;
  private OffsetDateTime createdAt;
}
