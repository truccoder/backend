package com.socialapp.knowledge.dto;

import java.util.List;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class SaveExplanationRequestDto {
  @NotNull private Integer postId;
  @NotNull private String originalContent;
  @NotNull private String explanationContent;
  private List<String> concepts;
  private List<String> prerequisites;

  /**
   * Sent straight back from the generate response. Without this field the client had no way to
   * return what it had just been shown, so saving quietly discarded the "Read more" list.
   */
  private List<ExplanationResponseDto.ExternalLink> externalLinks;

  private Integer complexityScore;
}
