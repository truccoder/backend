package com.socialapp.matchmaking.dto;

import java.util.List;

import com.socialapp.knowledge.entity.enums.SeniorityLevel;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * One role on a project, filled in as a job description rather than as a sentence.
 *
 * <p><b>Why the required fields are required.</b> This used to be a title, a free-text
 * {@code description} and an optional skill list, and every weakness of matchmaking followed from
 * that shape. {@code MatchmakingService.suggestCandidates} returns an empty list outright when
 * {@code requiredSkills} is absent — so the commonest way to post a role was also the way to get
 * no candidates, silently, with a 200. And the experience bar, the scope of the work and what the
 * team actually needs were all prose: readable by a person, invisible to the matcher.
 *
 * <p>So the three sections a JD is made of are now mandatory and separate — {@link #roleSummary},
 * {@link #responsibilities}, {@link #requirements} — and each is per role, because they differ per
 * role. What does <em>not</em> differ per role (who the team is, how they work) belongs to the
 * project: see {@code ProjectRequestDTO.companyOverview} / {@code companyCulture}.
 *
 * <p>The minimum sizes are deliberately low bars, not quality gates: two responsibilities and two
 * requirements is the least that reads as a description of a job rather than a placeholder, and a
 * summary shorter than {@value #SUMMARY_MIN} characters is a title repeated twice. Nothing here
 * tries to judge whether the writing is good.
 *
 * <p>{@code description} is gone — {@link #roleSummary} replaced it. Existing rows keep whatever
 * text they had (the column is untouched by {@code V105}); it is simply no longer written.
 */
@Data
public class ProjectPositionRequestDTO {

  /** Shortest summary that can say something a title does not. */
  public static final int SUMMARY_MIN = 40;

  @NotBlank(message = "Position title is required")
  @Size(max = 255, message = "Position title must be at most 255 characters")
  private String title;

  @NotBlank(message = "Role summary is required")
  @Size(
      min = SUMMARY_MIN,
      max = 2000,
      message = "Role summary must be between {min} and {max} characters")
  private String roleSummary;

  @NotEmpty(message = "List at least 2 responsibilities for this role")
  @Size(min = 2, max = 15, message = "Responsibilities must be between {min} and {max} items")
  private List<@NotBlank @Size(max = 300) String> responsibilities;

  @NotEmpty(message = "List at least 2 requirements for this role")
  @Size(min = 2, max = 15, message = "Requirements must be between {min} and {max} items")
  private List<@NotBlank @Size(max = 300) String> requirements;

  /**
   * Optional, and the only list here that gates nothing: {@code MatchmakingService} never reads
   * it. It exists so an owner can write "would be nice" somewhere other than {@link
   * #requirements}, where it would quietly narrow who the role is offered to.
   */
  @Size(max = 10, message = "Nice-to-have must be at most {max} items")
  private List<@NotBlank @Size(max = 300) String> niceToHave;

  /**
   * Now mandatory. These are the words the matcher matches on — a role posted without them cannot
   * be matched in either direction, which is the state most roles were in.
   */
  @NotEmpty(message = "At least one required skill is needed for matching")
  @Size(max = 20, message = "Required skills must be at most {max} items")
  private List<@NotBlank @Size(max = 60) String> requiredSkills;

  /**
   * The experience bar. Optional — a role open to anyone leaves both this and {@link
   * #seniorityLevel} null — but a <em>hard filter</em> once set: see {@code MatchmakingService}.
   */
  @Min(value = 0, message = "Minimum years of experience cannot be negative")
  @Max(value = 50, message = "Minimum years of experience must be at most {value}")
  private Integer minYearsExperience;

  private SeniorityLevel seniorityLevel;

  @Min(value = 1, message = "Quantity must be at least 1")
  @Max(value = 50, message = "Quantity must be at most {value}")
  private Integer quantity;
}
