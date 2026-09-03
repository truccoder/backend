package com.socialapp.matchmaking.service;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialapp.common.exception.ConflictException;
import com.socialapp.common.exception.ForbiddenException;
import com.socialapp.common.exception.NotFoundException;
import com.socialapp.common.exception.ValidationException;
import com.socialapp.matchmaking.dto.ProjectPositionRequestDTO;
import com.socialapp.matchmaking.dto.ProjectRequestDTO;
import com.socialapp.matchmaking.dto.UpdateProjectRequestDTO;
import com.socialapp.matchmaking.entity.ProjectApplicationEntity;
import com.socialapp.matchmaking.entity.ProjectEntity;
import com.socialapp.matchmaking.entity.ProjectPositionEntity;
import com.socialapp.matchmaking.entity.enums.ApplicationStatus;
import com.socialapp.matchmaking.entity.enums.PositionStatus;
import com.socialapp.matchmaking.entity.enums.ProjectStatus;
import com.socialapp.matchmaking.repository.ProjectApplicationRepository;
import com.socialapp.matchmaking.repository.ProjectPositionRepository;
import com.socialapp.matchmaking.repository.ProjectRepository;
import com.socialapp.notifications.NotificationMessages;
import com.socialapp.notifications.dto.SendNotificationRequest;
import com.socialapp.notifications.entity.enums.NotificationType;
import com.socialapp.notifications.services.NotificationService;
import com.socialapp.reputation.entity.enums.RepSourceType;
import com.socialapp.reputation.event.ReputationEventPublisher;
import com.socialapp.security.entity.UserEntity;
import com.socialapp.security.repository.UserRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ProjectService {
  private final ProjectRepository projectRepository;
  private final ProjectPositionRepository positionRepository;
  private final ProjectApplicationRepository applicationRepository;
  private final UserRepository userRepository;
  private final ReputationEventPublisher reputationEventPublisher;
  private final NotificationService notificationService;

  @Transactional
  public ProjectEntity createProject(Integer authorId, ProjectRequestDTO request) {
    UserEntity author =
        userRepository
            .findById(authorId)
            .orElseThrow(() -> new NotFoundException("User not found"));

    ProjectEntity project = new ProjectEntity();
    project.setAuthor(author);
    project.setTitle(request.getTitle());
    project.setDescription(request.getDescription());
    project.setBannerUrl(request.getBannerUrl());
    project.setTags(request.getTags());
    project.setCompanyOverview(request.getCompanyOverview());
    project.setCompanyCulture(request.getCompanyCulture());

    if (request.getPositions() != null) {
      project.setPositions(
          request.getPositions().stream()
              .map(
                  posDto -> {
                    ProjectPositionEntity pos = new ProjectPositionEntity();
                    pos.setProject(project);
                    applyJobDescription(pos, posDto);
                    pos.setQuantity(posDto.getQuantity() != null ? posDto.getQuantity() : 1);
                    return pos;
                  })
              .collect(Collectors.toList()));
    }

    return projectRepository.save(project);
  }

  @Transactional
  public ProjectApplicationEntity applyToPosition(
      Integer applicantId, Integer positionId, String message) {
    UserEntity applicant =
        userRepository
            .findById(applicantId)
            .orElseThrow(() -> new NotFoundException("User not found"));
    ProjectPositionEntity position =
        positionRepository
            .findById(positionId)
            .orElseThrow(() -> new NotFoundException("Position not found"));

    if (position.getStatus() != PositionStatus.OPEN) {
      throw new ConflictException("Position is not open for applications");
    }

    // The project as a whole must still be recruiting. A position can be OPEN on a project the
    // owner has since CLOSED or marked COMPLETED — the owner is not required to walk every role
    // shut before closing the project — and an application to one of those is not something they
    // asked to receive.
    if (position.getProject().getStatus() != ProjectStatus.OPEN) {
      throw new ConflictException("Project is not open for applications");
    }

    // Applying to your own project is refused, and this is a security check rather than a
    // usability one. Accepting an application awards PROJECT_APPLICATION_ACCEPTED to the
    // applicant, and the owner is the one who accepts — so without this an author could create a
    // position, apply to it, accept themselves, and repeat for as many positions as they cared to
    // create. The reputation ledger cannot catch it either: its idempotency key is the
    // application id, and every loop mints a fresh one.
    //
    // Guarded here as well as at the award (see acceptApplication) because the two block different
    // things: this one stops the row ever existing, that one stops the points even if some other
    // path creates it.
    if (applicantId.equals(position.getProject().getAuthor().getId())) {
      throw new ValidationException("You cannot apply to your own project");
    }

    // One application per person per position. There was no check at all and no unique index
    // behind it, so the same applicant could fill an owner's inbox with the same request.
    if (applicationRepository.existsByPositionIdAndApplicantId(positionId, applicantId)) {
      throw new ConflictException("You have already applied to this position");
    }

    ProjectApplicationEntity application = new ProjectApplicationEntity();
    application.setProject(position.getProject());
    application.setPosition(position);
    application.setApplicant(applicant);
    application.setMessage(message);

    return applicationRepository.save(application);
  }

  @Transactional
  public ProjectApplicationEntity acceptApplication(Integer ownerId, Integer applicationId) {
    ProjectApplicationEntity application = requireOwnedPendingApplication(ownerId, applicationId);

    // Locks the position row so concurrent accepts against the same position serialize; without
    // this, each transaction only sees its own not-yet-committed accept and the quantity cap can
    // be exceeded when several applications are accepted at the same time.
    ProjectPositionEntity position =
        positionRepository
            .findByIdForUpdate(application.getPosition().getId())
            .orElseThrow(() -> new NotFoundException("Position not found"));
    if (position.getStatus() != PositionStatus.OPEN) {
      throw new ConflictException(
          "Cannot accept an application for a position that is already " + position.getStatus());
    }

    application.setStatus(ApplicationStatus.ACCEPTED);
    ProjectApplicationEntity savedApp = applicationRepository.save(application);

    // Check if position is filled
    long acceptedCount =
        applicationRepository.countByPositionIdAndStatus(
            position.getId(), ApplicationStatus.ACCEPTED);

    if (acceptedCount >= position.getQuantity()) {
      position.setStatus(PositionStatus.FILLED);
      positionRepository.save(position);
    }

    // No self-crediting, the same rule PostService#acceptAnswer and MomoService#notifyBookAuthor
    // already apply. applyToPosition refuses an owner's own application outright, so this branch
    // should be unreachable for rows created through the API; it stays because the award is the
    // thing actually worth protecting, and a row inserted by hand or by a future code path must
    // not be able to mint points either.
    if (!application.getApplicant().getId().equals(ownerId)) {
      reputationEventPublisher.award(
          application.getApplicant().getId(),
          RepSourceType.PROJECT_APPLICATION_ACCEPTED,
          applicationId.toString());
    }

    notifyApplicant(
        application,
        NotificationType.PROJECT_APPLICATION_ACCEPTED,
        "Application accepted",
        "Your application to \"" + application.getProject().getTitle() + "\" was accepted");

    return savedApp;
  }

  @Transactional
  public ProjectApplicationEntity rejectApplication(Integer ownerId, Integer applicationId) {
    ProjectApplicationEntity application = requireOwnedPendingApplication(ownerId, applicationId);

    application.setStatus(ApplicationStatus.REJECTED);
    ProjectApplicationEntity saved = applicationRepository.save(application);

    notifyApplicant(
        application,
        NotificationType.PROJECT_APPLICATION_REJECTED,
        "Application declined",
        "Your application to \"" + application.getProject().getTitle() + "\" was declined");

    return saved;
  }

  /**
   * Tells an applicant how their application went — the matchmaking half of what {@code
   * SkillVerificationService.notifyDecision} does for skill claims, and added for the same reason:
   * the decision used to reach the applicant only if they thought to reopen the page.
   *
   * <p><b>No {@code actorId}</b>, exactly as {@code notifyDecision} does it: a decision on your
   * application is an outcome, not a person acting on you, and routing it through the block check
   * would let an applicant who blocked the owner (or the reverse) sit forever on an answer that
   * has already been given. {@code referenceId} is the project — the thing the client can open.
   */
  private void notifyApplicant(
      ProjectApplicationEntity application, NotificationType type, String title, String body) {
    String messageKey =
        NotificationType.PROJECT_APPLICATION_ACCEPTED.equals(type)
            ? NotificationMessages.PROJECT_APPLICATION_ACCEPTED
            : NotificationMessages.PROJECT_APPLICATION_REJECTED;
    notificationService.send(
        SendNotificationRequest.builder()
            .recipientId(application.getApplicant().getId())
            .type(type)
            .title(title)
            .body(body)
            .messageKey(messageKey)
            .messageArgs(NotificationMessages.args("project", application.getProject().getTitle()))
            .referenceId(application.getProject().getId())
            .referenceType("PROJECT")
            .build());
  }

  /**
   * Shared guard for {@link #acceptApplication} and {@link #rejectApplication}: the application
   * must exist, belong to a project owned by {@code ownerId}, and still be {@code PENDING} — an
   * already-decided application (ACCEPTED or REJECTED) cannot be re-decided.
   */
  private ProjectApplicationEntity requireOwnedPendingApplication(
      Integer ownerId, Integer applicationId) {
    ProjectApplicationEntity application =
        applicationRepository
            .findById(applicationId)
            .orElseThrow(() -> new NotFoundException("Application not found"));

    if (!application.getProject().getAuthor().getId().equals(ownerId)) {
      throw new ForbiddenException("Not authorized to decide on this application");
    }

    if (application.getStatus() != ApplicationStatus.PENDING) {
      throw new ConflictException(
          "Cannot decide on an application that is already " + application.getStatus());
    }

    return application;
  }

  // =====================================================================
  // Owner project management — edit, lifecycle, positions, team roster
  // =====================================================================

  /**
   * Edits a project's metadata (title, description, banner, tags). Owner only. Positions are not
   * touched here — see {@link #addPosition}, {@link #updatePosition}, {@link #deletePosition}.
   *
   * <p>Refused once a project is {@code COMPLETED}: that state is the owner's declaration that the
   * project is history, and history does not get re-titled.
   */
  @Transactional
  public ProjectEntity updateProject(
      Integer ownerId, Integer projectId, UpdateProjectRequestDTO request) {
    ProjectEntity project = requireOwnedProject(ownerId, projectId);
    if (project.getStatus() == ProjectStatus.COMPLETED) {
      throw new ConflictException("A completed project cannot be edited");
    }

    project.setTitle(request.getTitle());
    project.setDescription(request.getDescription());
    project.setBannerUrl(request.getBannerUrl());
    project.setTags(request.getTags());
    project.setCompanyOverview(request.getCompanyOverview());
    project.setCompanyCulture(request.getCompanyCulture());
    return projectRepository.save(project);
  }

  /**
   * Moves a project between {@code OPEN}, {@code CLOSED} and {@code COMPLETED}. Owner only.
   *
   * <ul>
   *   <li>{@code OPEN ↔ CLOSED} freely — closing stops new applications ({@link #applyToPosition}
   *       enforces it), opening resumes them. The positions keep whatever status they had.
   *   <li>anything {@code → COMPLETED}.
   *   <li>{@code COMPLETED → *} is refused: completion is terminal, the one status change that
   *       cannot be walked back.
   * </ul>
   *
   * <p>Setting the status it already has is a no-op that returns the project unchanged, rather
   * than a 409 — the caller's intent is already satisfied.
   */
  @Transactional
  public ProjectEntity updateStatus(Integer ownerId, Integer projectId, ProjectStatus target) {
    ProjectEntity project = requireOwnedProject(ownerId, projectId);

    if (project.getStatus() == target) {
      return project;
    }
    if (project.getStatus() == ProjectStatus.COMPLETED) {
      throw new ConflictException("A completed project cannot change status");
    }

    project.setStatus(target);
    return projectRepository.save(project);
  }

  /**
   * Permanently deletes a project and, by the {@code ON DELETE CASCADE} on their foreign keys,
   * its positions and every application to it. Owner only.
   *
   * <p>Before the row goes, the {@code PROJECT_APPLICATION_ACCEPTED} points awarded to each
   * accepted member are revoked — the same reasoning as {@link #removeMember}: the reputation was
   * granted for being on this team, and the team no longer exists. A hard delete rather than a
   * soft one because nothing else in the schema references a project, and a tombstoned project
   * board is a maintenance burden every read query would carry forever.
   */
  @Transactional
  public void deleteProject(Integer ownerId, Integer projectId) {
    ProjectEntity project = requireOwnedProject(ownerId, projectId);

    List<ProjectApplicationEntity> acceptedMembers =
        applicationRepository.findByProjectIdAndStatusForRoster(
            projectId, ApplicationStatus.ACCEPTED);
    for (ProjectApplicationEntity member : acceptedMembers) {
      reputationEventPublisher.revoke(
          member.getApplicant().getId(),
          RepSourceType.PROJECT_APPLICATION_ACCEPTED,
          member.getId().toString());
    }

    projectRepository.delete(project);
  }

  /**
   * Adds a role to an existing project. Owner only. Allowed while the project is {@code OPEN} or
   * {@code CLOSED} — an owner may line roles up before reopening — but not once it is {@code
   * COMPLETED}.
   */
  @Transactional
  public ProjectPositionEntity addPosition(
      Integer ownerId, Integer projectId, ProjectPositionRequestDTO request) {
    ProjectEntity project = requireOwnedProject(ownerId, projectId);
    if (project.getStatus() == ProjectStatus.COMPLETED) {
      throw new ConflictException("Cannot add a position to a completed project");
    }

    ProjectPositionEntity position = new ProjectPositionEntity();
    position.setProject(project);
    applyJobDescription(position, request);
    position.setQuantity(request.getQuantity() != null ? request.getQuantity() : 1);
    return positionRepository.save(position);
  }

  /**
   * Edits one role. Owner only. A {@code null} field on the request means "leave as is" for
   * {@code quantity} (so a client can PATCH-style send only what changed); {@code title},
   * validated {@code @NotBlank}, is always replaced.
   *
   * <p>The quantity is the interesting case. It cannot drop below the number of people already
   * accepted into the role — that would claim seats that are taken. Where it lands relative to
   * the accepted count also reconciles the position's own status: a {@code FILLED} role whose
   * quantity is raised goes back to {@code OPEN}, and an {@code OPEN} role whose quantity is
   * lowered to exactly its accepted count becomes {@code FILLED}. A {@code CLOSED} role is left
   * {@code CLOSED} — reopening it is a separate, explicit act ({@link #updatePositionStatus}).
   */
  @Transactional
  public ProjectPositionEntity updatePosition(
      Integer ownerId, Integer positionId, ProjectPositionRequestDTO request) {
    ProjectPositionEntity position = requireOwnedPosition(ownerId, positionId);
    if (position.getProject().getStatus() == ProjectStatus.COMPLETED) {
      throw new ConflictException("Cannot edit a position on a completed project");
    }

    long acceptedCount =
        applicationRepository.countByPositionIdAndStatus(positionId, ApplicationStatus.ACCEPTED);
    int newQuantity =
        request.getQuantity() != null ? request.getQuantity() : position.getQuantity();
    if (newQuantity < acceptedCount) {
      throw new ConflictException(
          "Quantity cannot be below the " + acceptedCount + " seat(s) already filled");
    }

    applyJobDescription(position, request);
    position.setQuantity(newQuantity);

    if (position.getStatus() == PositionStatus.FILLED && acceptedCount < newQuantity) {
      position.setStatus(PositionStatus.OPEN);
    } else if (position.getStatus() == PositionStatus.OPEN && acceptedCount >= newQuantity) {
      position.setStatus(PositionStatus.FILLED);
    }

    return positionRepository.save(position);
  }

  /**
   * Opens or closes a role by hand. Owner only.
   *
   * <p>{@code FILLED} is not a value a caller may set — it is the automatic consequence of
   * accepting the last seat — so it is rejected here. {@code CLOSED} can be set from any state.
   * {@code OPEN} can be set only while the role still has a free seat: reopening a role that is
   * at capacity is refused with the instruction to raise its quantity first.
   */
  @Transactional
  public ProjectPositionEntity updatePositionStatus(
      Integer ownerId, Integer positionId, PositionStatus target) {
    if (target == PositionStatus.FILLED) {
      throw new ValidationException("FILLED is set by accepting applications, not directly");
    }

    ProjectPositionEntity position = requireOwnedPosition(ownerId, positionId);
    if (position.getProject().getStatus() == ProjectStatus.COMPLETED) {
      throw new ConflictException("Cannot change a position on a completed project");
    }

    if (position.getStatus() == target) {
      return position;
    }

    if (target == PositionStatus.OPEN) {
      long acceptedCount =
          applicationRepository.countByPositionIdAndStatus(positionId, ApplicationStatus.ACCEPTED);
      if (acceptedCount >= position.getQuantity()) {
        throw new ConflictException(
            "Position is at capacity; raise its quantity before reopening it");
      }
    }

    position.setStatus(target);
    return positionRepository.save(position);
  }

  /**
   * Removes a role from a project. Owner only.
   *
   * <p>Refused while anyone is {@code ACCEPTED} into it: the owner must remove those members
   * first ({@link #removeMember}), which is the point at which their reputation is settled.
   * Applications that never became memberships ({@code PENDING}, {@code REJECTED}, {@code
   * REMOVED}) are deleted along with the position — they carry no points and there is nothing to
   * settle.
   */
  @Transactional
  public void deletePosition(Integer ownerId, Integer positionId) {
    ProjectPositionEntity position = requireOwnedPosition(ownerId, positionId);

    if (applicationRepository.existsByPositionIdAndStatus(positionId, ApplicationStatus.ACCEPTED)) {
      throw new ConflictException(
          "Remove the accepted member(s) from this position before deleting it");
    }

    applicationRepository.deleteByPositionId(positionId);
    positionRepository.delete(position);
  }

  /**
   * Takes an accepted member off a project's team. Owner only.
   *
   * <p>A person accepted into two roles on the same project has two {@code ACCEPTED} rows and is
   * removed from both — "remove this person from the project", not "from one seat". Each removed
   * row flips to {@code REMOVED} (distinct from {@code REJECTED}: they <em>were</em> on the
   * team), gives back the {@code PROJECT_APPLICATION_ACCEPTED} points that acceptance awarded,
   * and frees the seat — a {@code FILLED} position with a seat now open goes back to {@code
   * OPEN}.
   *
   * <p>The position row is locked for the recount for the same reason {@link #acceptApplication}
   * locks it: a concurrent accept and remove on the same position must not both read a stale
   * accepted count.
   */
  @Transactional
  public void removeMember(Integer ownerId, Integer projectId, Integer memberUserId) {
    ProjectEntity project = requireOwnedProject(ownerId, projectId);

    List<ProjectApplicationEntity> memberships =
        applicationRepository.findByProjectAndApplicantAndStatus(
            projectId, memberUserId, ApplicationStatus.ACCEPTED);
    if (memberships.isEmpty()) {
      throw new NotFoundException("That user is not an accepted member of this project");
    }

    for (ProjectApplicationEntity membership : memberships) {
      ProjectPositionEntity position =
          positionRepository
              .findByIdForUpdate(membership.getPosition().getId())
              .orElseThrow(() -> new NotFoundException("Position not found"));

      membership.setStatus(ApplicationStatus.REMOVED);
      applicationRepository.save(membership);

      reputationEventPublisher.revoke(
          memberUserId, RepSourceType.PROJECT_APPLICATION_ACCEPTED, membership.getId().toString());

      if (position.getStatus() == PositionStatus.FILLED) {
        long stillAccepted =
            applicationRepository.countByPositionIdAndStatus(
                position.getId(), ApplicationStatus.ACCEPTED);
        if (stillAccepted < position.getQuantity()) {
          position.setStatus(PositionStatus.OPEN);
          positionRepository.save(position);
        }
      }
    }

    // Once, not per membership: being removed from two roles on one project is still one event to
    // the person it happened to.
    notificationService.send(
        SendNotificationRequest.builder()
            .recipientId(memberUserId)
            .type(NotificationType.PROJECT_MEMBER_REMOVED)
            .title("Removed from a project")
            .body("You were removed from the team on \"" + project.getTitle() + "\"")
            .messageKey(NotificationMessages.PROJECT_MEMBER_REMOVED)
            .messageArgs(NotificationMessages.args("project", project.getTitle()))
            .referenceId(projectId)
            .referenceType("PROJECT")
            .build());
  }

  /**
   * Withdraws a pending application. The applicant's own action, not the owner's — the caller
   * must be the person who applied.
   *
   * <p>Only a {@code PENDING} application can be withdrawn: once the owner has accepted or
   * rejected it, the outcome stands, and leaving a team the applicant has been accepted onto is
   * the owner's {@link #removeMember} call, not a self-service one. Hard-deleted rather than
   * status-flagged — an un-decided request the applicant retracted leaves nothing worth keeping.
   */
  @Transactional
  public void withdrawApplication(Integer applicantId, Integer applicationId) {
    ProjectApplicationEntity application =
        applicationRepository
            .findById(applicationId)
            .orElseThrow(() -> new NotFoundException("Application not found"));

    if (!application.getApplicant().getId().equals(applicantId)) {
      throw new ForbiddenException("Not authorized to withdraw this application");
    }
    if (application.getStatus() != ApplicationStatus.PENDING) {
      throw new ConflictException(
          "Cannot withdraw an application that is already " + application.getStatus());
    }

    applicationRepository.delete(application);
  }

  /**
   * Copies a role's job description off the request and onto the entity — the one place the
   * mapping lives, so {@link #createProject}, {@link #addPosition} and {@link #updatePosition}
   * cannot end up writing three different subsets of it. Forgetting one field in one of the three
   * is exactly the kind of bug that shows up as "the requirements I typed vanished when I edited
   * the role".
   *
   * <p>Every field is replaced, never merged: the request is validated as a complete JD
   * ({@code ProjectPositionRequestDTO}), so a null {@code niceToHave} means the owner cleared that
   * section rather than that they did not mention it.
   *
   * <p>{@code quantity} is left to the caller — it is the one field with a rule of its own
   * (it cannot drop below the seats already filled), and that rule lives in {@link
   * #updatePosition}.
   *
   * <p>The cached JD PDF is <b>not</b> invalidated here. {@code @UpdateTimestamp} moves {@code
   * updatedAt} past {@code jdRenderedAt} on any write, and {@code JobDescriptionService} treats
   * that as stale — one clock instead of two flags that can disagree.
   */
  private void applyJobDescription(ProjectPositionEntity position, ProjectPositionRequestDTO dto) {
    position.setTitle(dto.getTitle());
    position.setRoleSummary(dto.getRoleSummary());
    position.setResponsibilities(dto.getResponsibilities());
    position.setRequirements(dto.getRequirements());
    position.setNiceToHave(dto.getNiceToHave());
    position.setRequiredSkills(dto.getRequiredSkills());
    position.setMinYearsExperience(dto.getMinYearsExperience());
    position.setSeniorityLevel(dto.getSeniorityLevel());
  }

  /**
   * Loads a project by id and asserts {@code ownerId} authored it — the shared guard for every
   * owner-scoped project operation. {@code findByIdWithAuthor} join-fetches the author so the
   * check needs no lazy load.
   */
  private ProjectEntity requireOwnedProject(Integer ownerId, Integer projectId) {
    ProjectEntity project =
        projectRepository
            .findByIdWithAuthor(projectId)
            .orElseThrow(() -> new NotFoundException("Project not found with ID: " + projectId));
    if (!project.getAuthor().getId().equals(ownerId)) {
      throw new ForbiddenException("Not authorized to manage this project");
    }
    return project;
  }

  /**
   * Loads a position by id and asserts {@code ownerId} authored its project — the guard for
   * every owner-scoped position operation. {@code findByIdWithProjectAuthor} join-fetches the
   * project and its author.
   */
  private ProjectPositionEntity requireOwnedPosition(Integer ownerId, Integer positionId) {
    ProjectPositionEntity position =
        positionRepository
            .findByIdWithProjectAuthor(positionId)
            .orElseThrow(() -> new NotFoundException("Position not found with ID: " + positionId));
    if (!position.getProject().getAuthor().getId().equals(ownerId)) {
      throw new ForbiddenException("Not authorized to manage this position");
    }
    return position;
  }
}
