package com.socialapp.matchmaking.dto;

import java.util.List;

import com.socialapp.knowledge.entity.enums.SeniorityLevel;
import com.socialapp.matchmaking.entity.ProjectPositionEntity;
import com.socialapp.matchmaking.entity.enums.PositionStatus;

import lombok.Builder;
import lombok.Data;

/** One open (or filled) role on a project, as the browse and detail screens show it. */
@Data
@Builder
public class ProjectPositionResponseDto {
  private Integer id;
  private String title;

  /**
   * Free text from before {@code V105}, kept only so positions written under the old shape still
   * render. Nothing writes it any more — {@link #roleSummary} replaced it.
   */
  private String description;

  /** The job description of this role. Null on positions created before {@code V105}. */
  private String roleSummary;

  private List<String> responsibilities;
  private List<String> requirements;
  private List<String> niceToHave;
  private List<String> requiredSkills;

  /** The two hard filters matchmaking applies. Null on either means the role does not ask. */
  private Integer minYearsExperience;

  private SeniorityLevel seniorityLevel;
  private Integer quantity;
  private PositionStatus status;

  /**
   * Whether there is enough here to render the JD as a PDF — i.e. whether the client should show
   * the "view job description" affordance on this role's card at all.
   *
   * <p>Computed rather than stored, and deliberately not "has a rendered PDF": the PDF is built on
   * first request ({@code JobDescriptionService}), so keying the button on the cached object would
   * hide it until someone had already pressed the button that isn't there.
   */
  private boolean hasJobDescription;

  /**
   * Maps inside a transaction only — {@code requiredSkills} is a jsonb column on the entity, which
   * is eager, but the entity itself may still be a proxy when this is called from a lazily loaded
   * collection.
   */
  public static ProjectPositionResponseDto from(ProjectPositionEntity position) {
    return ProjectPositionResponseDto.builder()
        .id(position.getId())
        .title(position.getTitle())
        .description(position.getDescription())
        .roleSummary(position.getRoleSummary())
        .responsibilities(position.getResponsibilities())
        .requirements(position.getRequirements())
        .niceToHave(position.getNiceToHave())
        .requiredSkills(position.getRequiredSkills())
        .minYearsExperience(position.getMinYearsExperience())
        .seniorityLevel(position.getSeniorityLevel())
        .quantity(position.getQuantity())
        .status(position.getStatus())
        .hasJobDescription(
            position.getRoleSummary() != null && !position.getRoleSummary().isBlank())
        .build();
  }
}
