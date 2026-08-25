package com.socialapp.matchmaking.service;

import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialapp.common.exception.ConflictException;
import com.socialapp.common.exception.ForbiddenException;
import com.socialapp.common.exception.NotFoundException;
import com.socialapp.common.exception.ValidationException;
import com.socialapp.matchmaking.dto.ProjectRequestDTO;
import com.socialapp.matchmaking.entity.ProjectApplicationEntity;
import com.socialapp.matchmaking.entity.ProjectEntity;
import com.socialapp.matchmaking.entity.ProjectPositionEntity;
import com.socialapp.matchmaking.entity.enums.ApplicationStatus;
import com.socialapp.matchmaking.entity.enums.PositionStatus;
import com.socialapp.matchmaking.repository.ProjectApplicationRepository;
import com.socialapp.matchmaking.repository.ProjectPositionRepository;
import com.socialapp.matchmaking.repository.ProjectRepository;
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

    if (request.getPositions() != null) {
      project.setPositions(
          request.getPositions().stream()
              .map(
                  posDto -> {
                    ProjectPositionEntity pos = new ProjectPositionEntity();
                    pos.setProject(project);
                    pos.setTitle(posDto.getTitle());
                    pos.setDescription(posDto.getDescription());
                    pos.setRequiredSkills(posDto.getRequiredSkills());
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

    return savedApp;
  }

  @Transactional
  public ProjectApplicationEntity rejectApplication(Integer ownerId, Integer applicationId) {
    ProjectApplicationEntity application = requireOwnedPendingApplication(ownerId, applicationId);

    application.setStatus(ApplicationStatus.REJECTED);
    return applicationRepository.save(application);
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
}
