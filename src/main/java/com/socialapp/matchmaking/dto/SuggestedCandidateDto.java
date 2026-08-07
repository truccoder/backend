package com.socialapp.matchmaking.dto;

import java.util.List;

import com.socialapp.knowledge.entity.enums.PrimaryRole;
import com.socialapp.knowledge.entity.enums.SeniorityLevel;

import lombok.Builder;
import lombok.Data;

/**
 * A shortlisted candidate for a project position. Only the fields a project owner needs to judge
 * fit — the full professional profile (work history, explanation style, interested domains) belongs
 * to its owner and is not a project owner's to read through the matchmaking endpoint.
 */
@Data
@Builder
public class SuggestedCandidateDto {
  private Integer userId;
  private String jobTitle;
  private SeniorityLevel seniorityLevel;
  private Integer yearsOfExperience;
  private PrimaryRole primaryRole;
  private List<String> knownTechStack;
}
