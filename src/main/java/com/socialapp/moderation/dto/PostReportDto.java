package com.socialapp.moderation.dto;

import java.time.OffsetDateTime;

import com.socialapp.moderation.entity.PostReportEntity;
import com.socialapp.moderation.enums.ReportReason;

import lombok.Builder;
import lombok.Data;

/**
 * A report as the moderation queue shows it — <b>admin-only</b>.
 *
 * <p>It carries {@code reporterId}, which is why it is never returned to the reporter's own client
 * or to the reported author. Telling an author who reported them is how reporting stops being
 * something people are willing to do.
 */
@Data
@Builder
public class PostReportDto {
  private Integer id;
  private Integer postId;
  private Integer reporterId;
  private ReportReason reason;
  private String details;
  private OffsetDateTime createdAt;

  public static PostReportDto from(PostReportEntity entity) {
    return PostReportDto.builder()
        .id(entity.getId())
        .postId(entity.getPostId())
        .reporterId(entity.getReporterId())
        .reason(entity.getReason())
        .details(entity.getDetails())
        .createdAt(entity.getCreatedAt())
        .build();
  }
}
