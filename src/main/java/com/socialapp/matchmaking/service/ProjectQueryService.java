package com.socialapp.matchmaking.service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialapp.common.exception.ForbiddenException;
import com.socialapp.common.exception.NotFoundException;
import com.socialapp.matchmaking.dto.ProjectApplicationResponseDto;
import com.socialapp.matchmaking.dto.ProjectMemberDto;
import com.socialapp.matchmaking.dto.ProjectPageResponseDto;
import com.socialapp.matchmaking.dto.ProjectPositionResponseDto;
import com.socialapp.matchmaking.dto.ProjectResponseDto;
import com.socialapp.matchmaking.entity.ProjectEntity;
import com.socialapp.matchmaking.entity.ProjectPositionEntity;
import com.socialapp.matchmaking.entity.enums.ApplicationStatus;
import com.socialapp.matchmaking.repository.ProjectApplicationRepository;
import com.socialapp.matchmaking.repository.ProjectPositionRepository;
import com.socialapp.matchmaking.repository.ProjectRepository;

import lombok.RequiredArgsConstructor;

/**
 * The read side of matchmaking, which until now did not exist: a project could be created and
 * applied to, but nothing could list projects, read one, or show either side its applications, so
 * the whole feature was reachable only by someone who already knew an id.
 *
 * <p>Split from {@link ProjectService} rather than added to it for the same reason {@code
 * BlockQueryService} is split from {@code BlockService} — and because {@code ProjectService} is
 * already the write path with its pessimistic locking, which reads have no business sharing.
 *
 * <p><b>Every method here is {@code @Transactional}.</b> {@code open-in-view} is off, so a DTO
 * mapper that walks {@code project.author} or {@code application.position} outside a transaction
 * throws {@code LazyInitializationException} rather than lazily loading. The mapping happens inside
 * these methods for that reason, never in the controller.
 */
@Service
@RequiredArgsConstructor
public class ProjectQueryService {

  private final ProjectRepository projectRepository;
  private final ProjectPositionRepository positionRepository;
  private final ProjectApplicationRepository applicationRepository;

  /**
   * One cursor page of projects, newest first, each with its open roles.
   *
   * <p>Two queries regardless of page size: the page itself (author join-fetched), then every
   * position on that page in one {@code IN} lookup. Reading {@code project.getPositions()} per row
   * instead would be one query per project.
   *
   * <p>Fetches {@code limit + 1} rows so {@code hasMore} needs no {@code COUNT(*)}.
   */
  @Transactional(readOnly = true)
  public ProjectPageResponseDto getProjects(Integer cursor, int limit) {
    List<ProjectEntity> page = projectRepository.findPage(cursor, PageRequest.of(0, limit + 1));

    boolean hasMore = page.size() > limit;
    List<ProjectEntity> visible = hasMore ? page.subList(0, limit) : page;

    Map<Integer, List<ProjectPositionResponseDto>> positionsByProject =
        loadPositions(visible.stream().map(ProjectEntity::getId).toList());

    List<ProjectResponseDto> items =
        visible.stream()
            .map(
                p ->
                    ProjectResponseDto.from(
                        p, positionsByProject.getOrDefault(p.getId(), List.of())))
            .toList();
    Integer nextCursor = visible.isEmpty() ? null : visible.get(visible.size() - 1).getId();

    return new ProjectPageResponseDto(items, nextCursor, hasMore);
  }

  /**
   * Projects matching a free-text query, newest first — the server side of the search page's
   * "Dự án" tab (backend-plan B33).
   *
   * <p>{@code sanitizedQuery} is expected already escaped for {@code LIKE}: the caller is {@code
   * SearchController}, which runs every branch's term through {@code SearchQuerySanitizer}, and
   * threading the sanitiser in here would make {@code matchmaking} depend on the {@code search}
   * package for a three-line utility.
   *
   * <p>Three queries regardless of hit count, mirroring {@link #getProjects}: the id match, the
   * authors, the positions. No block filtering — {@link #getProjects} has none either, and a
   * project board is a collaboration listing rather than a social feed; the two must not disagree
   * about whether a project exists.
   */
  @Transactional(readOnly = true)
  public List<ProjectResponseDto> searchProjects(String sanitizedQuery, int limit) {
    List<Integer> ids = projectRepository.searchIds(sanitizedQuery, limit);
    if (ids.isEmpty()) {
      return List.of();
    }

    Map<Integer, List<ProjectPositionResponseDto>> positionsByProject = loadPositions(ids);
    Map<Integer, ProjectEntity> byId =
        projectRepository.findAllByIdWithAuthor(ids).stream()
            .collect(Collectors.toMap(ProjectEntity::getId, Function.identity()));

    return ids.stream()
        .map(byId::get)
        .filter(Objects::nonNull)
        .map(p -> ProjectResponseDto.from(p, positionsByProject.getOrDefault(p.getId(), List.of())))
        .toList();
  }

  /** One project and its roles. Public to any signed-in user — a project exists to be found. */
  @Transactional(readOnly = true)
  public ProjectResponseDto getProject(Integer projectId) {
    ProjectEntity project =
        projectRepository
            .findByIdWithAuthor(projectId)
            .orElseThrow(() -> new NotFoundException("Project not found with ID: " + projectId));

    return ProjectResponseDto.from(
        project, loadPositions(List.of(projectId)).getOrDefault(projectId, List.of()));
  }

  /**
   * The applications sent to one project — <b>owner only</b>.
   *
   * <p>Unlike the project itself this is not public: it names everyone who asked to join and what
   * they wrote to ask. A 403 rather than an empty list, because an empty list would tell a
   * stranger the project has no applicants, which is also not theirs to know.
   */
  @Transactional(readOnly = true)
  public List<ProjectApplicationResponseDto> getApplicationsForProject(
      Integer projectId, Integer callerId) {
    ProjectEntity project =
        projectRepository
            .findByIdWithAuthor(projectId)
            .orElseThrow(() -> new NotFoundException("Project not found with ID: " + projectId));

    if (!project.getAuthor().getId().equals(callerId)) {
      throw new ForbiddenException("Not authorized to read applications for this project");
    }

    return applicationRepository.findByProjectIdForInbox(projectId).stream()
        .map(ProjectApplicationResponseDto::from)
        .toList();
  }

  /**
   * A project's team — everyone accepted onto it, with the role they hold.
   *
   * <p>Visible to any signed-in user, like the project itself: who is building a thing is part of
   * what a project board is for. The private half stays private — {@link
   * #getApplicationsForProject} (who <em>asked</em> to join, and what they wrote) is still owner
   * only.
   *
   * <p>404 rather than an empty list for a project that does not exist, so a caller can tell "no
   * members yet" from "no such project".
   */
  @Transactional(readOnly = true)
  public List<ProjectMemberDto> getMembers(Integer projectId) {
    if (!projectRepository.existsById(projectId)) {
      throw new NotFoundException("Project not found with ID: " + projectId);
    }
    return applicationRepository
        .findByProjectIdAndStatusForRoster(projectId, ApplicationStatus.ACCEPTED)
        .stream()
        .map(ProjectMemberDto::from)
        .toList();
  }

  /** The caller's own applications, newest first — "where did I apply, and did they answer". */
  @Transactional(readOnly = true)
  public List<ProjectApplicationResponseDto> getMyApplications(Integer applicantId) {
    return applicationRepository.findByApplicantId(applicantId).stream()
        .map(ProjectApplicationResponseDto::from)
        .toList();
  }

  private Map<Integer, List<ProjectPositionResponseDto>> loadPositions(List<Integer> projectIds) {
    if (projectIds.isEmpty()) {
      return Map.of();
    }
    return positionRepository.findByProjectIdIn(projectIds).stream()
        .collect(
            Collectors.groupingBy(
                (ProjectPositionEntity p) -> p.getProject().getId(),
                Collectors.mapping(ProjectPositionResponseDto::from, Collectors.toList())));
  }
}
