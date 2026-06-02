package com.socialapp.knowledge.dto;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeLibraryResponseDto {
  private List<ExplanationResponseDto> explanations;
  private int totalCount;
}
