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

  /**
   * Why this person is in the list, and how strongly. The list was previously returned in
   * arbitrary order with nothing to distinguish a one-skill match from a perfect one, which left
   * a project owner to eyeball {@code knownTechStack} against their own posting.
   *
   * <p>{@code matchedSkills} is the intersection with the position's required skills, spelled the
   * way the <em>position</em> spells them — the owner wrote those words, so they are the ones
   * that read as an answer to their posting.
   */
  private int matchScore;

  private List<String> matchedSkills;

  /**
   * How much of the role this person covers, 0–100. Everyone in this list already clears the
   * minimum ({@code PositionFit.MIN_SKILL_COVERAGE}) and both bars the job description states, so
   * this is the difference between "qualifies" and "qualifies comfortably" — the thing an owner
   * was previously counting by hand off two lists.
   */
  private int skillCoveragePercent;
}
