package com.socialapp.matchmaking.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import com.socialapp.common.utils.Constants;
import com.socialapp.matchmaking.dto.ApplicationRequestDTO;
import com.socialapp.matchmaking.dto.ProjectApplicationResponseDto;
import com.socialapp.matchmaking.dto.ProjectMemberDto;
import com.socialapp.matchmaking.dto.ProjectPageResponseDto;
import com.socialapp.matchmaking.dto.ProjectPositionRequestDTO;
import com.socialapp.matchmaking.dto.ProjectPositionResponseDto;
import com.socialapp.matchmaking.dto.ProjectRequestDTO;
import com.socialapp.matchmaking.dto.ProjectResponseDto;
import com.socialapp.matchmaking.dto.SuggestedCandidateDto;
import com.socialapp.matchmaking.dto.SuggestedProjectDto;
import com.socialapp.matchmaking.dto.UpdatePositionStatusRequestDTO;
import com.socialapp.matchmaking.dto.UpdateProjectRequestDTO;
import com.socialapp.matchmaking.dto.UpdateProjectStatusRequestDTO;
import com.socialapp.matchmaking.entity.ProjectEntity;
import com.socialapp.matchmaking.entity.ProjectPositionEntity;
import com.socialapp.matchmaking.service.MatchmakingService;
import com.socialapp.matchmaking.service.ProjectQueryService;
import com.socialapp.matchmaking.service.ProjectService;
import com.socialapp.security.util.SecurityUtils;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;

@Validated
@RestController
@RequestMapping("/v1/api/projects")
@RequiredArgsConstructor
public class ProjectController {
  private final ProjectService projectService;
  private final ProjectQueryService projectQueryService;
  private final MatchmakingService matchmakingService;

  /**
   * <p>Returns the created project instead of {@code void}. {@code ProjectService.createProject}
   * has always returned the saved entity — with its generated id — and this method threw it away,
   * so a client had no way to navigate to what it had just created except by listing projects and
   * guessing which one was new. The fix belongs here, not in the service.
   *
   * <p>A DTO, not the entity: see {@code ProjectResponseDto}. Positions come off the entity
   * directly because {@code createProject} built them in memory from the request, so they are
   * loaded whatever the session state.
   */
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public ProjectResponseDto createProject(@Valid @RequestBody ProjectRequestDTO request) {
    Integer authorId = SecurityUtils.getCurrentUserId();
    ProjectEntity project = projectService.createProject(authorId, request);

    List<ProjectPositionResponseDto> positions =
        project.getPositions() == null
            ? List.of()
            : project.getPositions().stream().map(ProjectPositionResponseDto::from).toList();
    return ProjectResponseDto.from(project, positions);
  }

  /** Browse projects, newest first. Cursor paging, same contract as posts and books. */
  @GetMapping
  public ProjectPageResponseDto getProjects(
      @RequestParam(required = false) Integer cursor,
      @RequestParam(defaultValue = Constants.DEFAULT_PAGINATION_PAGE_SIZE)
          @Positive
          @Max(Constants.MAX_PAGINATION_PAGE_SIZE)
          int limit) {
    return projectQueryService.getProjects(cursor, limit);
  }

  /**
   * One project with its roles.
   *
   * <p>The path variable is constrained to digits so it cannot swallow the sibling literal paths
   * under {@code /v1/api/projects} — without it, {@code /projects/applications} would try to bind
   * "applications" as a project id and answer 400 instead of routing.
   */
  @GetMapping("/{projectId:\\d+}")
  public ProjectResponseDto getProject(@PathVariable Integer projectId) {
    return projectQueryService.getProject(projectId);
  }

  /** A project owner's inbox. 403 for anyone else — see {@code ProjectQueryService}. */
  @GetMapping("/{projectId:\\d+}/applications")
  public List<ProjectApplicationResponseDto> getProjectApplications(
      @PathVariable Integer projectId) {
    return projectQueryService.getApplicationsForProject(
        projectId, SecurityUtils.getCurrentUserId());
  }

  /**
   * A project's team — everyone accepted onto it. Readable by any signed-in user, unlike the
   * application inbox above; see {@code ProjectQueryService.getMembers}.
   */
  @GetMapping("/{projectId:\\d+}/members")
  public List<ProjectMemberDto> getMembers(@PathVariable Integer projectId) {
    return projectQueryService.getMembers(projectId);
  }

  // ---- Owner project management ------------------------------------------------
  // Ownership is enforced in ProjectService, not here and not in SecurityConfig
  // (which only requires authentication for /v1/api/projects/**) — the same place
  // acceptApplication/rejectApplication already check it.

  /** Edit a project's title, description, banner and tags. Owner only; 409 once COMPLETED. */
  @PutMapping("/{projectId:\\d+}")
  public ProjectResponseDto updateProject(
      @PathVariable Integer projectId, @Valid @RequestBody UpdateProjectRequestDTO request) {
    projectService.updateProject(SecurityUtils.getCurrentUserId(), projectId, request);
    return projectQueryService.getProject(projectId);
  }

  /**
   * Move a project between OPEN, CLOSED and COMPLETED. Owner only. COMPLETED is terminal; setting
   * the current status is a no-op. See {@code ProjectService.updateStatus}.
   */
  @PatchMapping("/{projectId:\\d+}/status")
  public ProjectResponseDto updateProjectStatus(
      @PathVariable Integer projectId, @Valid @RequestBody UpdateProjectStatusRequestDTO request) {
    projectService.updateStatus(SecurityUtils.getCurrentUserId(), projectId, request.getStatus());
    return projectQueryService.getProject(projectId);
  }

  /**
   * Permanently delete a project, its positions and every application to it. Owner only.
   * Accepted members' reputation is revoked first — see {@code ProjectService.deleteProject}.
   */
  @DeleteMapping("/{projectId:\\d+}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deleteProject(@PathVariable Integer projectId) {
    projectService.deleteProject(SecurityUtils.getCurrentUserId(), projectId);
  }

  /** Add a role to a project. Owner only. Returns the created position. */
  @PostMapping("/{projectId:\\d+}/positions")
  @ResponseStatus(HttpStatus.CREATED)
  public ProjectPositionResponseDto addPosition(
      @PathVariable Integer projectId, @Valid @RequestBody ProjectPositionRequestDTO request) {
    ProjectPositionEntity position =
        projectService.addPosition(SecurityUtils.getCurrentUserId(), projectId, request);
    return ProjectPositionResponseDto.from(position);
  }

  /**
   * Remove an accepted member from a project's team (all roles they hold on it). Owner only.
   * Reputation is revoked and freed seats reopen — see {@code ProjectService.removeMember}.
   */
  @DeleteMapping("/{projectId:\\d+}/members/{userId:\\d+}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void removeMember(@PathVariable Integer projectId, @PathVariable Integer userId) {
    projectService.removeMember(SecurityUtils.getCurrentUserId(), projectId, userId);
  }

  /**
   * Edit one role — title, description, required skills, quantity. Owner only. Quantity cannot
   * drop below the seats already filled; see {@code ProjectService.updatePosition}.
   */
  @PutMapping("/positions/{positionId}")
  public ProjectPositionResponseDto updatePosition(
      @PathVariable Integer positionId, @Valid @RequestBody ProjectPositionRequestDTO request) {
    ProjectPositionEntity position =
        projectService.updatePosition(SecurityUtils.getCurrentUserId(), positionId, request);
    return ProjectPositionResponseDto.from(position);
  }

  /**
   * Open or close one role by hand. Owner only. FILLED is not settable; a role at capacity
   * cannot be reopened without raising its quantity. See {@code ProjectService.updatePositionStatus}.
   */
  @PatchMapping("/positions/{positionId}/status")
  public ProjectPositionResponseDto updatePositionStatus(
      @PathVariable Integer positionId,
      @Valid @RequestBody UpdatePositionStatusRequestDTO request) {
    ProjectPositionEntity position =
        projectService.updatePositionStatus(
            SecurityUtils.getCurrentUserId(), positionId, request.getStatus());
    return ProjectPositionResponseDto.from(position);
  }

  /**
   * Delete one role. Owner only. Refused while anyone is accepted into it — remove those members
   * first. See {@code ProjectService.deletePosition}.
   */
  @DeleteMapping("/positions/{positionId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deletePosition(@PathVariable Integer positionId) {
    projectService.deletePosition(SecurityUtils.getCurrentUserId(), positionId);
  }

  /**
   * Withdraw a pending application. The applicant's own action — 403 for anyone else, 409 once
   * the owner has decided it. See {@code ProjectService.withdrawApplication}.
   */
  @DeleteMapping("/applications/{applicationId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void withdrawApplication(@PathVariable Integer applicationId) {
    projectService.withdrawApplication(SecurityUtils.getCurrentUserId(), applicationId);
  }

  /** The caller's own applications. */
  @GetMapping("/applications/mine")
  public List<ProjectApplicationResponseDto> getMyApplications() {
    return projectQueryService.getMyApplications(SecurityUtils.getCurrentUserId());
  }

  @PostMapping("/positions/{positionId}/apply")
  public void applyToPosition(
      @PathVariable Integer positionId, @Valid @RequestBody ApplicationRequestDTO request) {
    Integer applicantId = SecurityUtils.getCurrentUserId();
    projectService.applyToPosition(applicantId, positionId, request.getMessage());
  }

  @PostMapping("/applications/{applicationId}/accept")
  public void acceptApplication(@PathVariable Integer applicationId) {
    Integer ownerId = SecurityUtils.getCurrentUserId();
    projectService.acceptApplication(ownerId, applicationId);
  }

  @PostMapping("/applications/{applicationId}/reject")
  public void rejectApplication(@PathVariable Integer applicationId) {
    Integer ownerId = SecurityUtils.getCurrentUserId();
    projectService.rejectApplication(ownerId, applicationId);
  }

  /**
   * Projects ranked against the caller's own professional profile.
   *
   * <p>The literal path sits beside {@code @GetMapping("/{projectId:\d+}")} above and routes
   * correctly only because of that digits-only constraint — without it "suggested" would bind as a
   * project id and answer 400. Do not loosen the regex.
   *
   * <p>A plain list rather than a cursor page: the order is by score, and the cursor convention
   * used everywhere else in this API is a descending id, which cannot express "resume from the
   * next-best match".
   */
  @GetMapping("/suggested")
  public List<SuggestedProjectDto> getSuggestedProjects(
      @RequestParam(defaultValue = Constants.DEFAULT_PAGINATION_PAGE_SIZE)
          @Positive
          @Max(Constants.MAX_PAGINATION_PAGE_SIZE)
          int limit) {
    return matchmakingService.suggestProjects(SecurityUtils.getCurrentUserId(), limit);
  }

  /** Candidate suggestions for one of the caller's own roles, best match first. 403 for anyone else. */
  @GetMapping("/positions/{positionId}/suggested-candidates")
  public List<SuggestedCandidateDto> getSuggestedCandidates(
      @PathVariable Integer positionId,
      @RequestParam(defaultValue = Constants.DEFAULT_PAGINATION_PAGE_SIZE)
          @Positive
          @Max(Constants.MAX_PAGINATION_PAGE_SIZE)
          int limit) {
    return matchmakingService.suggestCandidates(
        positionId, SecurityUtils.getCurrentUserId(), limit);
  }
}
