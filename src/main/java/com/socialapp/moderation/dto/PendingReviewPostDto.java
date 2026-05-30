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
public class PendingReviewPostDto {
  private Integer postId;
  private Integer authorId;
  private String authorName;
  private String content;
  private List<String> images;
  private ModerationStatus currentStatus;
  private ModerationScores aiScores;
  private OffsetDateTime createdAt;
}
