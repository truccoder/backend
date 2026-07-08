package com.socialapp.moderation.dto;

import java.time.OffsetDateTime;
import java.util.List;

import com.socialapp.moderation.enums.ModerationStatus;
import com.socialapp.moderation.enums.ViolationType;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModerationLogDto {
  private Long id;
  private Integer postId;
  private ModerationStatus status;
  private ViolationType violationType;
  private Double textToxicityScore;
  private Double imageSafeScore;
  private List<String> ruleViolations;
  private OffsetDateTime reviewedAt;
  private OffsetDateTime createdAt;
}
