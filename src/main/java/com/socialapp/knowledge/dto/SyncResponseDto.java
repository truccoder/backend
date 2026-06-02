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
public class SyncResponseDto {
  private List<ExplanationResponseDto> explanations;
  private OffsetDateTime syncedAt;
}
