package com.socialapp.roadmap.dto;

import com.socialapp.roadmap.enums.VerificationTier;

import lombok.Data;

@Data
public class SkillVerificationRequestDto {
  private Integer nodeId;
  private VerificationTier tier;
  private String proofUrl;
  private String proofImageKey;
}
