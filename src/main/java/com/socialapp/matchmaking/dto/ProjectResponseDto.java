package com.socialapp.matchmaking.dto;

import java.time.OffsetDateTime;
import java.util.List;

import com.socialapp.matchmaking.entity.ProjectEntity;
import com.socialapp.matchmaking.entity.enums.ProjectStatus;

import lombok.Builder;
import lombok.Data;

/**
 * A project as every read endpoint returns it — and as {@code POST /v1/api/projects} now returns
 * it too.
 *
 * <p>A DTO rather than the entity: {@code ProjectEntity} carries {@code author} (the whole {@code
 * UserEntity}, password hash and all), {@code applications}, and two lazy collections that
 * serialise into either an accidental data leak or a lazy-initialisation failure depending on
 * where the session ended. This is the same call that was made for the six entity schemas removed
 * in {@code B13}.
 */
@Data
@Builder
public class ProjectResponseDto {
  private Integer id;
  private String title;
  private String description;
  private String bannerUrl;
  private List<String> tags;

  /** The company half of the job description, shared by every role — see {@code V105}. */
  private String companyOverview;

  private String companyCulture;

  private ProjectStatus status;
  private Integer authorId;
  private String authorUsername;
  private String authorFullName;
  private String authorProfilePictureUrl;
  private List<ProjectPositionResponseDto> positions;
  private OffsetDateTime createdAt;

  /**
   * Must be called with the persistence context still open, or with {@code positions} already
   * loaded: it walks two lazy associations.
   *
   * <p>{@code positions} is passed in rather than read off the entity so the list endpoint can
   * load every page's positions in one query and hand each project its own slice, instead of
   * triggering one lazy load per project.
   */
  public static ProjectResponseDto from(
      ProjectEntity project, List<ProjectPositionResponseDto> positions) {
    return ProjectResponseDto.builder()
        .id(project.getId())
        .title(project.getTitle())
        .description(project.getDescription())
        .bannerUrl(project.getBannerUrl())
        .tags(project.getTags())
        .companyOverview(project.getCompanyOverview())
        .companyCulture(project.getCompanyCulture())
        .status(project.getStatus())
        .authorId(project.getAuthor() == null ? null : project.getAuthor().getId())
        // authorUsername mirrors B13's feed fix: /u/{username} is keyed on the handle and there is
        // no id->username endpoint, so without this the owner's name and avatar on a project card
        // have nowhere to link. The author is already join-fetched everywhere this DTO is built.
        .authorUsername(project.getAuthor() == null ? null : project.getAuthor().getUsername())
        .authorFullName(project.getAuthor() == null ? null : project.getAuthor().getFullName())
        .authorProfilePictureUrl(
            project.getAuthor() == null ? null : project.getAuthor().getProfilePictureUrl())
        .positions(positions)
        .createdAt(project.getCreatedAt())
        .build();
  }
}
