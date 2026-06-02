package com.socialapp.knowledge.dto;

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
public class ExplanationResponseDto {
  private Integer id;
  private Integer postId;
  private String originalContent;
  private String explanationContent;
  private List<String> concepts;
  private List<String> prerequisites;
  private Integer complexityScore;
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
