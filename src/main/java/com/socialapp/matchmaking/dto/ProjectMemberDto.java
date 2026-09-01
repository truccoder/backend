package com.socialapp.matchmaking.dto;

import java.time.OffsetDateTime;

import com.socialapp.matchmaking.entity.ProjectApplicationEntity;

import lombok.Builder;
import lombok.Data;

/**
 * One person on a project's team — the roster the owner manages and anyone viewing the project
 * can see. Derived from an {@code ACCEPTED} application: there is no separate membership table,
 * an accepted application <em>is</em> the membership.
 *
 * <p>Carries {@code applicationId} because that is the handle the owner's "remove from team"
 * action needs, and {@code username} for the same reason every other matchmaking DTO does — the
 * profile route is keyed on the handle and there is no id→username lookup.
 */
@Data
@Builder
public class ProjectMemberDto {
  private Integer applicationId;
  private Integer userId;
  private String username;
  private String fullName;
  private String profilePictureUrl;
  private Integer positionId;
  private String positionTitle;
  private OffsetDateTime joinedAt;

  /**
   * Walks applicant and position; call with the persistence context open.
   *
   * <p>{@code joinedAt} is the application's {@code updatedAt} — the acceptance is the last thing
   * that writes an {@code ACCEPTED} row, so in practice this is when they joined. There is no
   * dedicated {@code acceptedAt} column and adding one is not worth a migration for a roster
   * timestamp.
   */
  public static ProjectMemberDto from(ProjectApplicationEntity application) {
    return ProjectMemberDto.builder()
        .applicationId(application.getId())
        .userId(application.getApplicant().getId())
        .username(application.getApplicant().getUsername())
        .fullName(application.getApplicant().getFullName())
        .profilePictureUrl(application.getApplicant().getProfilePictureUrl())
        .positionId(application.getPosition().getId())
        .positionTitle(application.getPosition().getTitle())
        .joinedAt(application.getUpdatedAt())
        .build();
  }
}
