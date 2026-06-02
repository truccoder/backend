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
  private Integer complexityScore;
}
