package com.socialapp.knowledge.dto;

import java.time.OffsetDateTime;
import java.util.List;

import com.socialapp.common.enums.LearningCategory;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExplanationResponseDto {
  private Integer id;
  private Integer postId;
  private String originalContent;
  private String explanationContent;
  private List<String> concepts;
  private List<String> prerequisites;
  private Integer complexityScore;

  /** Chủ đề để FE gom nhóm Kho lưu trữ. Luôn có giá trị; không phân loại được thì là OTHER. */
  private LearningCategory category;

  private Integer version;
  private List<ExternalLink> externalLinks;
  private OffsetDateTime createdAt;

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class ExternalLink {
    private String title;
    private String url;
    private String reason;
  }
}
