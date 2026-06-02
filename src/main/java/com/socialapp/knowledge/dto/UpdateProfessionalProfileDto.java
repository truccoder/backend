package com.socialapp.knowledge.dto;

import java.util.List;

import com.socialapp.knowledge.entity.WorkExperience;
import com.socialapp.knowledge.entity.enums.SeniorityLevel;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class UpdateProfessionalProfileDto {
  private String jobTitle;

  @NotNull private SeniorityLevel seniorityLevel;

  @Min(0)
  @Max(50)
  private Integer yearsOfExperience;

  private List<String> knownTechStack;

  private List<WorkExperience> workHistory;

  private List<String> interestedDomains;
}
