package com.socialapp.knowledge.dto;

import java.time.OffsetDateTime;
import java.util.List;

import com.socialapp.knowledge.entity.WorkExperience;
import com.socialapp.knowledge.entity.enums.ExplanationStyle;
import com.socialapp.knowledge.entity.enums.PrimaryRole;
import com.socialapp.knowledge.entity.enums.SeniorityLevel;

import lombok.Builder;
import lombok.Data;

/** Owner-facing view of the professional profile — the caller only ever reads their own. */
@Data
@Builder
public class ProfessionalProfileResponseDto {
  private Integer userId;
  private String jobTitle;
  private SeniorityLevel seniorityLevel;
  private Integer yearsOfExperience;
  private PrimaryRole primaryRole;
  private ExplanationStyle explanationStyle;
  private List<String> knownTechStack;
  private List<WorkExperience> workHistory;
  private List<String> interestedDomains;
  private OffsetDateTime createdAt;
  private OffsetDateTime updatedAt;
}
