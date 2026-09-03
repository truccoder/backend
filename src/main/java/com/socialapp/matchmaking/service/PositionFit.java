package com.socialapp.matchmaking.service;

import java.util.List;

import com.socialapp.knowledge.entity.UserProfessionalProfileEntity;
import com.socialapp.knowledge.entity.enums.SeniorityLevel;
import com.socialapp.knowledge.service.ProfileMatchScorer;
import com.socialapp.matchmaking.entity.ProjectPositionEntity;

/**
 * How well one person fits one role, and — the part that did not exist before — whether they
 * qualify for it at all.
 *
 * <p>Matchmaking used to have a single question: how many skills do these two lists share? One
 * shared word out of six was a match, and so was six out of six; the experience a role asked for
 * lived in a paragraph of prose no code read. Both directions of the feature therefore suggested
 * people who could not do the job and jobs the person could not take, ranked by how loudly they
 * overlapped.
 *
 * <p>{@code V105} gave a role a structured job description, and this record is what reads it. It
 * answers in two parts, kept separate on purpose:
 *
 * <ul>
 *   <li><b>{@link #qualified()}</b> — a yes/no gate. Below the bar is not a weak match, it is a
 *       wrong one, and no amount of skill overlap should be able to buy past a role that asks for
 *       five years from someone with one.
 *   <li><b>the counts</b> — how strong the match is, for ranking the people who did qualify. The
 *       weights that turn these into a number stay in {@code MatchmakingService}, which is where
 *       the two directions' scoring differs.
 * </ul>
 *
 * <p>Absent data on the <em>role</em> is permissive: a role that names no experience bar has no
 * bar. Absent data on the <em>person</em> is not: someone who never said how much experience they
 * have cannot be shown to clear a bar that was explicitly set. The asymmetry is deliberate — the
 * first is a role that did not ask, the second is a claim nobody made.
 */
public record PositionFit(
    List<String> matchedSkills,
    int requiredSkillCount,
    boolean meetsExperienceBar,
    boolean meetsSeniorityBar) {

  /**
   * The share of a role's required skills a person must have before they are worth suggesting.
   *
   * <p>One skill in common used to be enough, which is how a "Senior Go, Kubernetes, Terraform,
   * PostgreSQL" role ended up recommending someone whose only overlap was PostgreSQL. Half is a
   * low bar deliberately: this is a collaboration board where people join projects to learn, and a
   * threshold high enough to demand every skill would leave both lists empty.
   */
  public static final double MIN_SKILL_COVERAGE = 0.5;

  /**
   * A candidate's fit for a role, with the shared skills spelled the way the <b>position</b>
   * spells them — the project owner wrote those words and is the one reading this list.
   */
  public static PositionFit forCandidate(
      ProjectPositionEntity position, UserProfessionalProfileEntity profile) {
    return evaluate(
        position,
        profile,
        ProfileMatchScorer.matchedSkills(
            position.getRequiredSkills(), profile.getKnownTechStack()));
  }

  /**
   * The same fit seen from the other side, with the shared skills spelled the way the <b>profile
   * owner</b> spells them — they are the one reading it, and their own capitalisation of their own
   * stack is what reads as an answer.
   */
  public static PositionFit forSeeker(
      ProjectPositionEntity position, UserProfessionalProfileEntity profile) {
    return evaluate(
        position,
        profile,
        ProfileMatchScorer.matchedSkills(
            profile.getKnownTechStack(), position.getRequiredSkills()));
  }

  private static PositionFit evaluate(
      ProjectPositionEntity position,
      UserProfessionalProfileEntity profile,
      List<String> matchedSkills) {
    List<String> required = position.getRequiredSkills();
    return new PositionFit(
        matchedSkills,
        required == null ? 0 : required.size(),
        meetsExperienceBar(position.getMinYearsExperience(), profile.getYearsOfExperience()),
        meetsSeniorityBar(position.getSeniorityLevel(), profile.getSeniorityLevel()));
  }

  /**
   * Whether this person should be suggested for this role at all: enough of the required skills,
   * and both stated bars cleared.
   *
   * <p>A role with no required skills is never qualified-for. That is not a punishment for an
   * incomplete posting — {@code ProjectPositionRequestDTO} now refuses to create one — but the
   * only honest answer for the positions that predate {@code V105}: with nothing to match on,
   * "this person fits" is a claim with no evidence behind it.
   */
  public boolean qualified() {
    return requiredSkillCount > 0
        && matchedSkills.size() >= minimumSkillsRequired()
        && meetsExperienceBar
        && meetsSeniorityBar;
  }

  /** Every required skill matched — the tiebreak that puts an exact fit above a partial one. */
  public boolean fullCoverage() {
    return requiredSkillCount > 0 && matchedSkills.size() >= requiredSkillCount;
  }

  /** How much of the role this person covers, 0–100, for showing the owner why. */
  public int coveragePercent() {
    if (requiredSkillCount == 0) {
      return 0;
    }
    return Math.round(matchedSkills.size() * 100f / requiredSkillCount);
  }

  /** Half the required skills, rounded up, and never fewer than one. */
  private int minimumSkillsRequired() {
    return Math.max(1, (int) Math.ceil(requiredSkillCount * MIN_SKILL_COVERAGE));
  }

  private static boolean meetsExperienceBar(Integer required, Integer candidateYears) {
    if (required == null) {
      return true;
    }
    return candidateYears != null && candidateYears >= required;
  }

  /**
   * Seniority is ranked by the declaration order of {@link SeniorityLevel} (JUNIOR … PRINCIPAL),
   * so a role asking for SENIOR also accepts a LEAD. Reordering that enum silently reorders this.
   */
  private static boolean meetsSeniorityBar(SeniorityLevel required, SeniorityLevel candidate) {
    if (required == null) {
      return true;
    }
    return candidate != null && candidate.compareTo(required) >= 0;
  }
}
