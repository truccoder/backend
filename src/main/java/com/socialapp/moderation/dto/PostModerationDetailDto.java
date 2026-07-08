package com.socialapp.moderation.dto;

import java.time.OffsetDateTime;
import java.util.List;

import com.socialapp.moderation.enums.ModerationStatus;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PostModerationDetailDto {
  private Integer postId;
  private Integer authorId;
  private String authorName;
  private String content;
  private List<String> images;
  private ModerationStatus currentStatus;
  private OffsetDateTime createdAt;
  private OffsetDateTime updatedAt;

  /**
   * Full state-machine history for this post, oldest first.
   */
  private List<ModerationLogDto> history;
}
