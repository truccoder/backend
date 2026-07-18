package com.socialapp.roadmap.dto;

import com.socialapp.roadmap.enums.VerificationTier;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class SkillVerificationRequestDto {
  @NotNull(message = "Node ID is required")
  private Integer nodeId;

  @NotNull(message = "Verification tier is required")
  private VerificationTier tier;

  private String proofUrl;
  private String proofImageKey;
}
