package com.socialapp.matchmaking.dto;

import java.time.OffsetDateTime;

import com.socialapp.matchmaking.entity.ProjectApplicationEntity;
import com.socialapp.matchmaking.entity.enums.ApplicationStatus;

import lombok.Builder;
import lombok.Data;

/**
 * One application, shown either in a project owner's inbox or in the applicant's own "where did I
 * apply" list. The same shape serves both: an owner needs to know who applied, an applicant needs
 * to know which project and role they applied to, and neither list is worth two payload shapes.
 */
@Data
@Builder
public class ProjectApplicationResponseDto {
  private Integer id;
  private Integer projectId;
  private String projectTitle;
  private Integer positionId;
  private String positionTitle;
  private Integer applicantId;
  private String applicantFullName;
  private String applicantProfilePictureUrl;
  private String message;
  private ApplicationStatus status;
  private OffsetDateTime createdAt;

  /** Walks three lazy associations; call with the persistence context open. */
  public static ProjectApplicationResponseDto from(ProjectApplicationEntity application) {
    return ProjectApplicationResponseDto.builder()
        .id(application.getId())
        .projectId(application.getProject().getId())
        .projectTitle(application.getProject().getTitle())
        .positionId(application.getPosition().getId())
        .positionTitle(application.getPosition().getTitle())
        .applicantId(application.getApplicant().getId())
        .applicantFullName(application.getApplicant().getFullName())
        .applicantProfilePictureUrl(application.getApplicant().getProfilePictureUrl())
        .message(application.getMessage())
        .status(application.getStatus())
        .createdAt(application.getCreatedAt())
        .build();
  }
}
