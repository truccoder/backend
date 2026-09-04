package com.socialapp.matchmaking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
import com.socialapp.reputation.entity.enums.RepSourceType;
import com.socialapp.reputation.event.ReputationEventPublisher;
import com.socialapp.security.entity.UserEntity;
import com.socialapp.security.repository.UserRepository;

/**
 * Component (unit) tests for {@link ProjectService}, per ISTQB CTFL v4.0.1 (Section 2.2.1
 * component testing; Section 4.2.2 boundary value analysis around the default-quantity ternary and
 * the accepted-count-vs-quantity "position filled" threshold; Section 4.3.1 state transition
 * testing over the {@code PENDING}-only accept/reject guard and the {@code OPEN}-only apply/accept
 * guard; Section 4.3.2 branch testing).
 */
@ExtendWith(MockitoExtension.class)
class ProjectServiceTest {

  private static final Integer OWNER_ID = 1;
  private static final Integer APPLICANT_ID = 2;
  private static final Integer POSITION_ID = 10;
  private static final Integer APPLICATION_ID = 100;

  /** Matches the id {@link #project} stamps on every fixture. */
  private static final Integer PROJECT_ID = 1;

  @Mock private ProjectRepository projectRepository;
  @Mock private ProjectPositionRepository positionRepository;
  @Mock private ProjectApplicationRepository applicationRepository;
  @Mock private UserRepository userRepository;
  @Mock private ReputationEventPublisher reputationEventPublisher;
  @Mock private com.socialapp.notifications.services.NotificationService notificationService;

  @InjectMocks private ProjectService projectService;

  private static UserEntity user(Integer id) {
    UserEntity user = new UserEntity();
    user.setId(id);
    return user;
  }

  private static ProjectEntity project(Integer authorId) {
    ProjectEntity project = new ProjectEntity();
    project.setId(1);
    project.setTitle("Elite Nexus mobile app");
    project.setAuthor(user(authorId));
    project.setApplications(new ArrayList<>());
    return project;
  }

  private static ProjectPositionEntity position(
      ProjectEntity project, int quantity, PositionStatus status) {
    ProjectPositionEntity position = new ProjectPositionEntity();
    position.setId(POSITION_ID);
    position.setProject(project);
    position.setQuantity(quantity);
    position.setStatus(status);
    return position;
  }

  private static ProjectApplicationEntity application(
      Integer id,
      ProjectEntity project,
      ProjectPositionEntity position,
      Integer applicantId,
      ApplicationStatus status) {
    ProjectApplicationEntity application = new ProjectApplicationEntity();
    application.setId(id);
    application.setProject(project);
    application.setPosition(position);
    application.setApplicant(user(applicantId));
    application.setStatus(status);
    return application;
  }

  // =====================================================================
  // createProject
  // =====================================================================

  @Nested
  @DisplayName("createProject")
  class CreateProjectTests {

    @Test
    @DisplayName("should reject when the author does not exist")
    void shouldThrowNotFoundException_whenAuthorDoesNotExist() {
      // Given
      when(userRepository.findById(OWNER_ID)).thenReturn(Optional.empty());

      // When / Then
      assertThatThrownBy(() -> projectService.createProject(OWNER_ID, new ProjectRequestDTO()))
          .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("should save a project with no positions when none are provided")
    void shouldSaveWithoutPositions_whenPositionsListIsNull() {
      // Given
      when(userRepository.findById(OWNER_ID)).thenReturn(Optional.of(user(OWNER_ID)));
      when(projectRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
      ProjectRequestDTO request = new ProjectRequestDTO();
      request.setTitle("Elite Nexus mobile app");

      // When
      ProjectEntity saved = projectService.createProject(OWNER_ID, request);

      // Then
      assertThat(saved.getTitle()).isEqualTo("Elite Nexus mobile app");
      assertThat(saved.getPositions()).isNull();
    }

    @Test
    @DisplayName("should default a position's quantity to 1 when not provided")
    void shouldDefaultQuantityToOne_whenNotProvided() {
      // Given
      when(userRepository.findById(OWNER_ID)).thenReturn(Optional.of(user(OWNER_ID)));
      when(projectRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
      ProjectPositionRequestDTO posDto = new ProjectPositionRequestDTO();
      posDto.setTitle("Backend Developer");
      posDto.setQuantity(null);
      ProjectRequestDTO request = new ProjectRequestDTO();
      request.setPositions(List.of(posDto));

      // When
      ProjectEntity saved = projectService.createProject(OWNER_ID, request);

      // Then
      assertThat(saved.getPositions()).hasSize(1);
      assertThat(saved.getPositions().get(0).getQuantity()).isEqualTo(1);
      assertThat(saved.getPositions().get(0).getProject()).isSameAs(saved);
    }

    @Test
    @DisplayName("should use the provided quantity when one is given")
    void shouldUseProvidedQuantity_whenPresent() {
      // Given
      when(userRepository.findById(OWNER_ID)).thenReturn(Optional.of(user(OWNER_ID)));
      when(projectRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
      ProjectPositionRequestDTO posDto = new ProjectPositionRequestDTO();
      posDto.setTitle("Backend Developer");
      posDto.setQuantity(5);
      ProjectRequestDTO request = new ProjectRequestDTO();
      request.setPositions(List.of(posDto));

      // When
      ProjectEntity saved = projectService.createProject(OWNER_ID, request);

      // Then
      assertThat(saved.getPositions().get(0).getQuantity()).isEqualTo(5);
    }
  }

  // =====================================================================
  // applyToPosition
  // =====================================================================

  @Nested
  @DisplayName("applyToPosition")
  class ApplyToPositionTests {

    @Test
    @DisplayName("should reject when the applicant does not exist")
    void shouldThrowNotFoundException_whenApplicantDoesNotExist() {
      // Given
      when(userRepository.findById(APPLICANT_ID)).thenReturn(Optional.empty());

      // When / Then
      assertThatThrownBy(() -> projectService.applyToPosition(APPLICANT_ID, POSITION_ID, "hire me"))
          .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("should reject when the position does not exist")
    void shouldThrowNotFoundException_whenPositionDoesNotExist() {
      // Given
      when(userRepository.findById(APPLICANT_ID)).thenReturn(Optional.of(user(APPLICANT_ID)));
      when(positionRepository.findById(POSITION_ID)).thenReturn(Optional.empty());

      // When / Then
      assertThatThrownBy(() -> projectService.applyToPosition(APPLICANT_ID, POSITION_ID, "hire me"))
          .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("should reject when the position is not open")
    void shouldThrowIllegalStateException_whenPositionNotOpen() {
      // Given
      ProjectEntity project = project(OWNER_ID);
      ProjectPositionEntity position = position(project, 1, PositionStatus.FILLED);
      when(userRepository.findById(APPLICANT_ID)).thenReturn(Optional.of(user(APPLICANT_ID)));
      when(positionRepository.findById(POSITION_ID)).thenReturn(Optional.of(position));

      // When / Then
      assertThatThrownBy(() -> projectService.applyToPosition(APPLICANT_ID, POSITION_ID, "hire me"))
          .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("should refuse an application to the applicant's own project")
    void shouldThrowValidationException_whenApplyingToOwnProject() {
      // Given — the project author is the one applying. Left open, this is the whole of a
      // reputation-minting loop: apply to your own position, accept yourself, collect
      // PROJECT_APPLICATION_ACCEPTED, repeat for a new position. The ledger cannot de-duplicate
      // it because its idempotency key is the application id and each loop creates a fresh one.
      ProjectEntity project = project(OWNER_ID);
      ProjectPositionEntity position = position(project, 1, PositionStatus.OPEN);
      when(userRepository.findById(OWNER_ID)).thenReturn(Optional.of(user(OWNER_ID)));
      when(positionRepository.findById(POSITION_ID)).thenReturn(Optional.of(position));

      // When / Then
      assertThatThrownBy(() -> projectService.applyToPosition(OWNER_ID, POSITION_ID, "hire me"))
          .isInstanceOf(ValidationException.class)
          .hasMessageContaining("your own project");
      verify(applicationRepository, never()).save(any());
    }

    @Test
    @DisplayName("should refuse an application when the project itself is not open")
    void shouldThrowConflict_whenProjectNotOpen() {
      // Given: an OPEN position on a project the owner has since CLOSED. The owner is not made to
      // walk every role shut before closing a project, so the position status alone is not enough.
      ProjectEntity project = project(OWNER_ID);
      project.setStatus(ProjectStatus.CLOSED);
      ProjectPositionEntity position = position(project, 1, PositionStatus.OPEN);
      when(userRepository.findById(APPLICANT_ID)).thenReturn(Optional.of(user(APPLICANT_ID)));
      when(positionRepository.findById(POSITION_ID)).thenReturn(Optional.of(position));

      // When / Then
      assertThatThrownBy(() -> projectService.applyToPosition(APPLICANT_ID, POSITION_ID, "hi"))
          .isInstanceOf(ConflictException.class)
          .hasMessageContaining("Project is not open");
      verify(applicationRepository, never()).save(any());
    }

    @Test
    @DisplayName("should save a pending application when the position is open")
    void shouldSaveApplication_whenPositionIsOpen() {
      // Given
      ProjectEntity project = project(OWNER_ID);
      ProjectPositionEntity position = position(project, 1, PositionStatus.OPEN);
      when(userRepository.findById(APPLICANT_ID)).thenReturn(Optional.of(user(APPLICANT_ID)));
      when(positionRepository.findById(POSITION_ID)).thenReturn(Optional.of(position));
      when(applicationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

      // When
      ProjectApplicationEntity saved =
          projectService.applyToPosition(APPLICANT_ID, POSITION_ID, "hire me");

      // Then
      assertThat(saved.getProject()).isSameAs(project);
      assertThat(saved.getPosition()).isSameAs(position);
      assertThat(saved.getApplicant().getId()).isEqualTo(APPLICANT_ID);
      assertThat(saved.getMessage()).isEqualTo("hire me");
    }
  }

  // =====================================================================
  // acceptApplication
  // =====================================================================

  @Nested
  @DisplayName("acceptApplication")
  class AcceptApplicationTests {

    @Test
    @DisplayName("should reject when the application does not exist")
    void shouldThrowNotFoundException_whenApplicationDoesNotExist() {
      // Given
      when(applicationRepository.findById(APPLICATION_ID)).thenReturn(Optional.empty());

      // When / Then
      assertThatThrownBy(() -> projectService.acceptApplication(OWNER_ID, APPLICATION_ID))
          .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("should reject when the caller does not own the project")
    void shouldThrowForbiddenException_whenCallerIsNotOwner() {
      // Given
      ProjectEntity project = project(OWNER_ID);
      ProjectPositionEntity position = position(project, 1, PositionStatus.OPEN);
      ProjectApplicationEntity application =
          application(APPLICATION_ID, project, position, APPLICANT_ID, ApplicationStatus.PENDING);
      when(applicationRepository.findById(APPLICATION_ID)).thenReturn(Optional.of(application));

      // When / Then
      Integer intruderId = 999;
      assertThatThrownBy(() -> projectService.acceptApplication(intruderId, APPLICATION_ID))
          .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("should reject an application that is no longer pending")
    void shouldThrowIllegalStateException_whenApplicationNotPending() {
      // Given
      ProjectEntity project = project(OWNER_ID);
      ProjectPositionEntity position = position(project, 1, PositionStatus.OPEN);
      ProjectApplicationEntity application =
          application(APPLICATION_ID, project, position, APPLICANT_ID, ApplicationStatus.REJECTED);
      when(applicationRepository.findById(APPLICATION_ID)).thenReturn(Optional.of(application));

      // When / Then
      assertThatThrownBy(() -> projectService.acceptApplication(OWNER_ID, APPLICATION_ID))
          .isInstanceOf(ConflictException.class);
      verify(applicationRepository, never()).save(any());
    }

    @Test
    @DisplayName("should not award reputation when the owner accepts their own application")
    void shouldNotAwardReputation_whenApplicantIsTheOwner() {
      // Given — a row where the applicant and the project owner are the same person.
      // applyToPosition now refuses to create one, so this covers the second guard: even if such a
      // row reaches acceptApplication by another route, the points must not be granted. The accept
      // itself still succeeds — it is the self-crediting that is refused, not the state change.
      ProjectEntity project = project(OWNER_ID);
      ProjectPositionEntity position = position(project, 1, PositionStatus.OPEN);
      ProjectApplicationEntity application =
          application(APPLICATION_ID, project, position, OWNER_ID, ApplicationStatus.PENDING);
      when(applicationRepository.findById(APPLICATION_ID)).thenReturn(Optional.of(application));
      when(positionRepository.findByIdForUpdate(POSITION_ID)).thenReturn(Optional.of(position));
      when(applicationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

      // When
      projectService.acceptApplication(OWNER_ID, APPLICATION_ID);

      // Then
      verify(reputationEventPublisher, never()).award(any(), any(), any());
    }

    @Test
    @DisplayName("should reject when the position is no longer open")
    void shouldThrowIllegalStateException_whenPositionNotOpen() {
      // Given
      ProjectEntity project = project(OWNER_ID);
      ProjectPositionEntity position = position(project, 1, PositionStatus.CLOSED);
      ProjectApplicationEntity application =
          application(APPLICATION_ID, project, position, APPLICANT_ID, ApplicationStatus.PENDING);
      when(applicationRepository.findById(APPLICATION_ID)).thenReturn(Optional.of(application));
      when(positionRepository.findByIdForUpdate(POSITION_ID)).thenReturn(Optional.of(position));

      // When / Then
      assertThatThrownBy(() -> projectService.acceptApplication(OWNER_ID, APPLICATION_ID))
          .isInstanceOf(ConflictException.class);
      verify(applicationRepository, never()).save(any());
    }

    @Test
    @DisplayName("should reject when the position row backing the application is gone")
    void shouldThrowNotFoundException_whenPositionDoesNotExist() {
      // Given: the application still references a position id that no longer resolves under
      // the pessimistic-write lock lookup (e.g. deleted between the application read and now).
      ProjectEntity project = project(OWNER_ID);
      ProjectPositionEntity position = position(project, 1, PositionStatus.OPEN);
      ProjectApplicationEntity application =
          application(APPLICATION_ID, project, position, APPLICANT_ID, ApplicationStatus.PENDING);
      when(applicationRepository.findById(APPLICATION_ID)).thenReturn(Optional.of(application));
      when(positionRepository.findByIdForUpdate(POSITION_ID)).thenReturn(Optional.empty());

      // When / Then
      assertThatThrownBy(() -> projectService.acceptApplication(OWNER_ID, APPLICATION_ID))
          .isInstanceOf(NotFoundException.class);
      verify(applicationRepository, never()).save(any());
    }

    @Test
    @DisplayName("should stay open when accepted count is still below the position's quantity")
    void shouldStayOpen_whenAcceptedCountBelowQuantity() {
      // Given: quantity 2, only this application accepted so far (count query reflects that
      // after this application's own save, exactly 1 ACCEPTED application exists for the
      // position — distractors for other positions/statuses are the repository query's concern,
      // not this unit test's, since countByPositionIdAndStatus is a derived query method).
      ProjectEntity project = project(OWNER_ID);
      ProjectPositionEntity position = position(project, 2, PositionStatus.OPEN);
      ProjectApplicationEntity application =
          application(APPLICATION_ID, project, position, APPLICANT_ID, ApplicationStatus.PENDING);
      when(applicationRepository.findById(APPLICATION_ID)).thenReturn(Optional.of(application));
      when(positionRepository.findByIdForUpdate(POSITION_ID)).thenReturn(Optional.of(position));
      when(applicationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
      when(applicationRepository.countByPositionIdAndStatus(
              POSITION_ID, ApplicationStatus.ACCEPTED))
          .thenReturn(1L);

      // When
      projectService.acceptApplication(OWNER_ID, APPLICATION_ID);

      // Then
      assertThat(application.getStatus()).isEqualTo(ApplicationStatus.ACCEPTED);
      assertThat(position.getStatus()).isEqualTo(PositionStatus.OPEN);
      verify(positionRepository, never()).save(any());
      verify(reputationEventPublisher)
          .award(
              APPLICANT_ID, RepSourceType.PROJECT_APPLICATION_ACCEPTED, APPLICATION_ID.toString());
      // The decision reaches the applicant rather than ending in silence (mirrors skill verify).
      verify(notificationService)
          .send(
              org.mockito.ArgumentMatchers.argThat(
                  r ->
                      r.getRecipientId().equals(APPLICANT_ID)
                          && r.getType()
                              == com.socialapp.notifications.entity.enums.NotificationType
                                  .PROJECT_APPLICATION_ACCEPTED
                          && r.getActorId() == null));
    }

    @Test
    @DisplayName("should fill the position once accepted count reaches its quantity")
    void shouldFillPosition_whenAcceptedCountReachesQuantity() {
      // Given: quantity 1, this is the only application for the position.
      ProjectEntity project = project(OWNER_ID);
      ProjectPositionEntity position = position(project, 1, PositionStatus.OPEN);
      ProjectApplicationEntity application =
          application(APPLICATION_ID, project, position, APPLICANT_ID, ApplicationStatus.PENDING);
      when(applicationRepository.findById(APPLICATION_ID)).thenReturn(Optional.of(application));
      when(positionRepository.findByIdForUpdate(POSITION_ID)).thenReturn(Optional.of(position));
      when(applicationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
      when(applicationRepository.countByPositionIdAndStatus(
              POSITION_ID, ApplicationStatus.ACCEPTED))
          .thenReturn(1L);

      // When
      projectService.acceptApplication(OWNER_ID, APPLICATION_ID);

      // Then
      assertThat(position.getStatus()).isEqualTo(PositionStatus.FILLED);
      verify(positionRepository).save(position);
    }

    @Test
    @DisplayName("should fill the position when combined with a previously accepted application")
    void shouldFillPosition_whenCombinedWithPriorAcceptedApplication() {
      // Given: quantity 2 — one applicant already ACCEPTED earlier, this one tips it over.
      ProjectEntity project = project(OWNER_ID);
      ProjectPositionEntity position = position(project, 2, PositionStatus.OPEN);
      ProjectApplicationEntity application =
          application(APPLICATION_ID, project, position, APPLICANT_ID, ApplicationStatus.PENDING);
      when(applicationRepository.findById(APPLICATION_ID)).thenReturn(Optional.of(application));
      when(positionRepository.findByIdForUpdate(POSITION_ID)).thenReturn(Optional.of(position));
      when(applicationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
      when(applicationRepository.countByPositionIdAndStatus(
              POSITION_ID, ApplicationStatus.ACCEPTED))
          .thenReturn(2L);

      // When
      projectService.acceptApplication(OWNER_ID, APPLICATION_ID);

      // Then
      assertThat(position.getStatus()).isEqualTo(PositionStatus.FILLED);
      verify(positionRepository).save(position);
    }
  }

  // =====================================================================
  // rejectApplication
  // =====================================================================

  @Nested
  @DisplayName("rejectApplication")
  class RejectApplicationTests {

    @Test
    @DisplayName("should reject when the application does not exist")
    void shouldThrowNotFoundException_whenApplicationDoesNotExist() {
      // Given
      when(applicationRepository.findById(APPLICATION_ID)).thenReturn(Optional.empty());

      // When / Then
      assertThatThrownBy(() -> projectService.rejectApplication(OWNER_ID, APPLICATION_ID))
          .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("should reject when the caller does not own the project")
    void shouldThrowForbiddenException_whenCallerIsNotOwner() {
      // Given
      ProjectEntity project = project(OWNER_ID);
      ProjectPositionEntity position = position(project, 1, PositionStatus.OPEN);
      ProjectApplicationEntity application =
          application(APPLICATION_ID, project, position, APPLICANT_ID, ApplicationStatus.PENDING);
      when(applicationRepository.findById(APPLICATION_ID)).thenReturn(Optional.of(application));

      // When / Then
      Integer intruderId = 999;
      assertThatThrownBy(() -> projectService.rejectApplication(intruderId, APPLICATION_ID))
          .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("should reject an application that is no longer pending")
    void shouldThrowIllegalStateException_whenApplicationNotPending() {
      // Given
      ProjectEntity project = project(OWNER_ID);
      ProjectPositionEntity position = position(project, 1, PositionStatus.OPEN);
      ProjectApplicationEntity application =
          application(APPLICATION_ID, project, position, APPLICANT_ID, ApplicationStatus.ACCEPTED);
      when(applicationRepository.findById(APPLICATION_ID)).thenReturn(Optional.of(application));

      // When / Then
      assertThatThrownBy(() -> projectService.rejectApplication(OWNER_ID, APPLICATION_ID))
          .isInstanceOf(ConflictException.class);
      verify(applicationRepository, never()).save(any());
    }

    @Test
    @DisplayName("should mark a pending application as rejected without touching the position")
    void shouldReject_whenPendingAndOwnerMatches() {
      // Given
      ProjectEntity project = project(OWNER_ID);
      ProjectPositionEntity position = position(project, 1, PositionStatus.OPEN);
      ProjectApplicationEntity application =
          application(APPLICATION_ID, project, position, APPLICANT_ID, ApplicationStatus.PENDING);
      when(applicationRepository.findById(APPLICATION_ID)).thenReturn(Optional.of(application));
      when(applicationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

      // When
      ProjectApplicationEntity result = projectService.rejectApplication(OWNER_ID, APPLICATION_ID);

      // Then
      assertThat(result.getStatus()).isEqualTo(ApplicationStatus.REJECTED);
      assertThat(position.getStatus()).isEqualTo(PositionStatus.OPEN);
      verify(positionRepository, never()).save(any());
      verify(notificationService)
          .send(
              org.mockito.ArgumentMatchers.argThat(
                  r ->
                      r.getRecipientId().equals(APPLICANT_ID)
                          && r.getType()
                              == com.socialapp.notifications.entity.enums.NotificationType
                                  .PROJECT_APPLICATION_REJECTED));
    }
  }

  // =====================================================================
  // updateProject
  // =====================================================================

  @Nested
  @DisplayName("updateProject")
  class UpdateProjectTests {

    private UpdateProjectRequestDTO request() {
      UpdateProjectRequestDTO dto = new UpdateProjectRequestDTO();
      dto.setTitle("New title");
      dto.setDescription("New description");
      dto.setBannerUrl("https://cdn/x.png");
      dto.setTags(List.of("mobile"));
      return dto;
    }

    @Test
    @DisplayName("should 404 when the project does not exist")
    void shouldThrowNotFound_whenMissing() {
      when(projectRepository.findByIdWithAuthor(PROJECT_ID)).thenReturn(Optional.empty());

      assertThatThrownBy(() -> projectService.updateProject(OWNER_ID, PROJECT_ID, request()))
          .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("should 403 when the caller is not the owner")
    void shouldThrowForbidden_whenNotOwner() {
      when(projectRepository.findByIdWithAuthor(PROJECT_ID))
          .thenReturn(Optional.of(project(OWNER_ID)));

      assertThatThrownBy(() -> projectService.updateProject(999, PROJECT_ID, request()))
          .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("should 409 when the project is already completed")
    void shouldThrowConflict_whenCompleted() {
      ProjectEntity project = project(OWNER_ID);
      project.setStatus(ProjectStatus.COMPLETED);
      when(projectRepository.findByIdWithAuthor(PROJECT_ID)).thenReturn(Optional.of(project));

      assertThatThrownBy(() -> projectService.updateProject(OWNER_ID, PROJECT_ID, request()))
          .isInstanceOf(ConflictException.class);
      verify(projectRepository, never()).save(any());
    }

    @Test
    @DisplayName("should overwrite title, description, banner and tags")
    void shouldOverwriteFields() {
      ProjectEntity project = project(OWNER_ID);
      when(projectRepository.findByIdWithAuthor(PROJECT_ID)).thenReturn(Optional.of(project));
      when(projectRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

      ProjectEntity saved = projectService.updateProject(OWNER_ID, PROJECT_ID, request());

      assertThat(saved.getTitle()).isEqualTo("New title");
      assertThat(saved.getDescription()).isEqualTo("New description");
      assertThat(saved.getBannerUrl()).isEqualTo("https://cdn/x.png");
      assertThat(saved.getTags()).containsExactly("mobile");
    }
  }

  // =====================================================================
  // updateStatus
  // =====================================================================

  @Nested
  @DisplayName("updateStatus")
  class UpdateStatusTests {

    @Test
    @DisplayName("should 403 when the caller is not the owner")
    void shouldThrowForbidden_whenNotOwner() {
      when(projectRepository.findByIdWithAuthor(PROJECT_ID))
          .thenReturn(Optional.of(project(OWNER_ID)));

      assertThatThrownBy(() -> projectService.updateStatus(999, PROJECT_ID, ProjectStatus.CLOSED))
          .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("should be a no-op when the project already has the target status")
    void shouldNoOp_whenAlreadyInTargetStatus() {
      ProjectEntity project = project(OWNER_ID); // defaults to OPEN
      when(projectRepository.findByIdWithAuthor(PROJECT_ID)).thenReturn(Optional.of(project));

      ProjectEntity result = projectService.updateStatus(OWNER_ID, PROJECT_ID, ProjectStatus.OPEN);

      assertThat(result).isSameAs(project);
      verify(projectRepository, never()).save(any());
    }

    @Test
    @DisplayName("should refuse to move a completed project out of that state")
    void shouldThrowConflict_whenLeavingCompleted() {
      ProjectEntity project = project(OWNER_ID);
      project.setStatus(ProjectStatus.COMPLETED);
      when(projectRepository.findByIdWithAuthor(PROJECT_ID)).thenReturn(Optional.of(project));

      assertThatThrownBy(
              () -> projectService.updateStatus(OWNER_ID, PROJECT_ID, ProjectStatus.OPEN))
          .isInstanceOf(ConflictException.class);
      verify(projectRepository, never()).save(any());
    }

    @Test
    @DisplayName("should move an open project to closed")
    void shouldCloseAnOpenProject() {
      ProjectEntity project = project(OWNER_ID);
      when(projectRepository.findByIdWithAuthor(PROJECT_ID)).thenReturn(Optional.of(project));
      when(projectRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

      ProjectEntity result =
          projectService.updateStatus(OWNER_ID, PROJECT_ID, ProjectStatus.CLOSED);

      assertThat(result.getStatus()).isEqualTo(ProjectStatus.CLOSED);
    }
  }

  // =====================================================================
  // deleteProject
  // =====================================================================

  @Nested
  @DisplayName("deleteProject")
  class DeleteProjectTests {

    @Test
    @DisplayName("should 403 when the caller is not the owner")
    void shouldThrowForbidden_whenNotOwner() {
      when(projectRepository.findByIdWithAuthor(PROJECT_ID))
          .thenReturn(Optional.of(project(OWNER_ID)));

      assertThatThrownBy(() -> projectService.deleteProject(999, PROJECT_ID))
          .isInstanceOf(ForbiddenException.class);
      verify(projectRepository, never()).delete(any());
    }

    @Test
    @DisplayName("should revoke each accepted member's reputation before deleting the project")
    void shouldRevokeReputationThenDelete() {
      ProjectEntity project = project(OWNER_ID);
      ProjectPositionEntity position = position(project, 1, PositionStatus.FILLED);
      ProjectApplicationEntity member =
          application(APPLICATION_ID, project, position, APPLICANT_ID, ApplicationStatus.ACCEPTED);
      when(projectRepository.findByIdWithAuthor(PROJECT_ID)).thenReturn(Optional.of(project));
      when(applicationRepository.findByProjectIdAndStatusForRoster(
              PROJECT_ID, ApplicationStatus.ACCEPTED))
          .thenReturn(List.of(member));

      projectService.deleteProject(OWNER_ID, PROJECT_ID);

      verify(reputationEventPublisher)
          .revoke(
              APPLICANT_ID, RepSourceType.PROJECT_APPLICATION_ACCEPTED, APPLICATION_ID.toString());
      verify(projectRepository).delete(project);
    }
  }

  // =====================================================================
  // addPosition
  // =====================================================================

  @Nested
  @DisplayName("addPosition")
  class AddPositionTests {

    private ProjectPositionRequestDTO request(Integer quantity) {
      ProjectPositionRequestDTO dto = new ProjectPositionRequestDTO();
      dto.setTitle("Frontend Developer");
      dto.setRoleSummary(
          "Build the screens the project is judged on, alongside one other engineer.");
      dto.setResponsibilities(List.of("Build screens", "Review UI pull requests"));
      dto.setRequirements(List.of("Two years of React", "Reads English documentation"));
      dto.setRequiredSkills(List.of("react"));
      dto.setQuantity(quantity);
      return dto;
    }

    @Test
    @DisplayName("should 403 when the caller is not the owner")
    void shouldThrowForbidden_whenNotOwner() {
      when(projectRepository.findByIdWithAuthor(PROJECT_ID))
          .thenReturn(Optional.of(project(OWNER_ID)));

      assertThatThrownBy(() -> projectService.addPosition(999, PROJECT_ID, request(1)))
          .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("should 409 when the project is completed")
    void shouldThrowConflict_whenCompleted() {
      ProjectEntity project = project(OWNER_ID);
      project.setStatus(ProjectStatus.COMPLETED);
      when(projectRepository.findByIdWithAuthor(PROJECT_ID)).thenReturn(Optional.of(project));

      assertThatThrownBy(() -> projectService.addPosition(OWNER_ID, PROJECT_ID, request(1)))
          .isInstanceOf(ConflictException.class);
      verify(positionRepository, never()).save(any());
    }

    @Test
    @DisplayName("should default quantity to 1 and attach the position to the project")
    void shouldDefaultQuantityAndAttach() {
      ProjectEntity project = project(OWNER_ID);
      when(projectRepository.findByIdWithAuthor(PROJECT_ID)).thenReturn(Optional.of(project));
      when(positionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

      ProjectPositionEntity saved = projectService.addPosition(OWNER_ID, PROJECT_ID, request(null));

      assertThat(saved.getQuantity()).isEqualTo(1);
      assertThat(saved.getProject()).isSameAs(project);
      assertThat(saved.getTitle()).isEqualTo("Frontend Developer");
    }
  }

  // =====================================================================
  // updatePosition
  // =====================================================================

  @Nested
  @DisplayName("updatePosition")
  class UpdatePositionTests {

    private ProjectPositionRequestDTO request(Integer quantity) {
      ProjectPositionRequestDTO dto = new ProjectPositionRequestDTO();
      dto.setTitle("Backend Developer");
      dto.setRoleSummary("Own the API layer and the jobs behind it, from schema to deploy.");
      dto.setResponsibilities(List.of("Design endpoints", "Keep the migrations honest"));
      dto.setRequirements(List.of("Three years of Java", "Has shipped a REST API"));
      dto.setRequiredSkills(List.of("java"));
      dto.setQuantity(quantity);
      return dto;
    }

    @Test
    @DisplayName("should 403 when the caller does not own the position's project")
    void shouldThrowForbidden_whenNotOwner() {
      ProjectEntity project = project(OWNER_ID);
      when(positionRepository.findByIdWithProjectAuthor(POSITION_ID))
          .thenReturn(Optional.of(position(project, 2, PositionStatus.OPEN)));

      assertThatThrownBy(() -> projectService.updatePosition(999, POSITION_ID, request(2)))
          .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("should refuse a quantity below the seats already filled")
    void shouldThrowConflict_whenQuantityBelowAcceptedCount() {
      ProjectEntity project = project(OWNER_ID);
      when(positionRepository.findByIdWithProjectAuthor(POSITION_ID))
          .thenReturn(Optional.of(position(project, 3, PositionStatus.OPEN)));
      when(applicationRepository.countByPositionIdAndStatus(
              POSITION_ID, ApplicationStatus.ACCEPTED))
          .thenReturn(2L);

      assertThatThrownBy(() -> projectService.updatePosition(OWNER_ID, POSITION_ID, request(1)))
          .isInstanceOf(ConflictException.class);
      verify(positionRepository, never()).save(any());
    }

    @Test
    @DisplayName("should reopen a filled position when its quantity is raised")
    void shouldReopen_whenFilledAndQuantityRaised() {
      ProjectEntity project = project(OWNER_ID);
      ProjectPositionEntity position = position(project, 1, PositionStatus.FILLED);
      when(positionRepository.findByIdWithProjectAuthor(POSITION_ID))
          .thenReturn(Optional.of(position));
      when(applicationRepository.countByPositionIdAndStatus(
              POSITION_ID, ApplicationStatus.ACCEPTED))
          .thenReturn(1L);
      when(positionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

      ProjectPositionEntity saved =
          projectService.updatePosition(OWNER_ID, POSITION_ID, request(2));

      assertThat(saved.getStatus()).isEqualTo(PositionStatus.OPEN);
      assertThat(saved.getQuantity()).isEqualTo(2);
    }

    @Test
    @DisplayName("should fill an open position when its quantity is lowered to the accepted count")
    void shouldFill_whenOpenAndQuantityLoweredToAcceptedCount() {
      ProjectEntity project = project(OWNER_ID);
      ProjectPositionEntity position = position(project, 5, PositionStatus.OPEN);
      when(positionRepository.findByIdWithProjectAuthor(POSITION_ID))
          .thenReturn(Optional.of(position));
      when(applicationRepository.countByPositionIdAndStatus(
              POSITION_ID, ApplicationStatus.ACCEPTED))
          .thenReturn(2L);
      when(positionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

      ProjectPositionEntity saved =
          projectService.updatePosition(OWNER_ID, POSITION_ID, request(2));

      assertThat(saved.getStatus()).isEqualTo(PositionStatus.FILLED);
    }

    @Test
    @DisplayName("should keep the existing quantity when the request omits it")
    void shouldKeepQuantity_whenRequestQuantityNull() {
      ProjectEntity project = project(OWNER_ID);
      ProjectPositionEntity position = position(project, 4, PositionStatus.OPEN);
      when(positionRepository.findByIdWithProjectAuthor(POSITION_ID))
          .thenReturn(Optional.of(position));
      when(applicationRepository.countByPositionIdAndStatus(
              POSITION_ID, ApplicationStatus.ACCEPTED))
          .thenReturn(0L);
      when(positionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

      ProjectPositionEntity saved =
          projectService.updatePosition(OWNER_ID, POSITION_ID, request(null));

      assertThat(saved.getQuantity()).isEqualTo(4);
    }
  }

  // =====================================================================
  // updatePositionStatus
  // =====================================================================

  @Nested
  @DisplayName("updatePositionStatus")
  class UpdatePositionStatusTests {

    @Test
    @DisplayName("should reject FILLED as a target — it is not settable by hand")
    void shouldThrowValidation_whenTargetIsFilled() {
      assertThatThrownBy(
              () ->
                  projectService.updatePositionStatus(OWNER_ID, POSITION_ID, PositionStatus.FILLED))
          .isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("should 403 when the caller does not own the position's project")
    void shouldThrowForbidden_whenNotOwner() {
      ProjectEntity project = project(OWNER_ID);
      when(positionRepository.findByIdWithProjectAuthor(POSITION_ID))
          .thenReturn(Optional.of(position(project, 1, PositionStatus.OPEN)));

      assertThatThrownBy(
              () -> projectService.updatePositionStatus(999, POSITION_ID, PositionStatus.CLOSED))
          .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("should refuse reopening a position that is already at capacity")
    void shouldThrowConflict_whenReopeningAtCapacity() {
      ProjectEntity project = project(OWNER_ID);
      when(positionRepository.findByIdWithProjectAuthor(POSITION_ID))
          .thenReturn(Optional.of(position(project, 1, PositionStatus.CLOSED)));
      when(applicationRepository.countByPositionIdAndStatus(
              POSITION_ID, ApplicationStatus.ACCEPTED))
          .thenReturn(1L);

      assertThatThrownBy(
              () -> projectService.updatePositionStatus(OWNER_ID, POSITION_ID, PositionStatus.OPEN))
          .isInstanceOf(ConflictException.class);
      verify(positionRepository, never()).save(any());
    }

    @Test
    @DisplayName("should close an open position")
    void shouldCloseAnOpenPosition() {
      ProjectEntity project = project(OWNER_ID);
      ProjectPositionEntity position = position(project, 2, PositionStatus.OPEN);
      when(positionRepository.findByIdWithProjectAuthor(POSITION_ID))
          .thenReturn(Optional.of(position));
      when(positionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

      ProjectPositionEntity saved =
          projectService.updatePositionStatus(OWNER_ID, POSITION_ID, PositionStatus.CLOSED);

      assertThat(saved.getStatus()).isEqualTo(PositionStatus.CLOSED);
    }
  }

  // =====================================================================
  // deletePosition
  // =====================================================================

  @Nested
  @DisplayName("deletePosition")
  class DeletePositionTests {

    @Test
    @DisplayName("should 403 when the caller does not own the position's project")
    void shouldThrowForbidden_whenNotOwner() {
      ProjectEntity project = project(OWNER_ID);
      when(positionRepository.findByIdWithProjectAuthor(POSITION_ID))
          .thenReturn(Optional.of(position(project, 1, PositionStatus.OPEN)));

      assertThatThrownBy(() -> projectService.deletePosition(999, POSITION_ID))
          .isInstanceOf(ForbiddenException.class);
      verify(positionRepository, never()).delete(any());
    }

    @Test
    @DisplayName("should refuse deletion while a member is accepted into the position")
    void shouldThrowConflict_whenAcceptedMemberExists() {
      ProjectEntity project = project(OWNER_ID);
      when(positionRepository.findByIdWithProjectAuthor(POSITION_ID))
          .thenReturn(Optional.of(position(project, 1, PositionStatus.FILLED)));
      when(applicationRepository.existsByPositionIdAndStatus(
              POSITION_ID, ApplicationStatus.ACCEPTED))
          .thenReturn(true);

      assertThatThrownBy(() -> projectService.deletePosition(OWNER_ID, POSITION_ID))
          .isInstanceOf(ConflictException.class);
      verify(positionRepository, never()).delete(any());
    }

    @Test
    @DisplayName("should clear non-membership applications then delete the position")
    void shouldClearApplicationsThenDelete() {
      ProjectEntity project = project(OWNER_ID);
      ProjectPositionEntity position = position(project, 1, PositionStatus.OPEN);
      when(positionRepository.findByIdWithProjectAuthor(POSITION_ID))
          .thenReturn(Optional.of(position));
      when(applicationRepository.existsByPositionIdAndStatus(
              POSITION_ID, ApplicationStatus.ACCEPTED))
          .thenReturn(false);

      projectService.deletePosition(OWNER_ID, POSITION_ID);

      verify(applicationRepository).deleteByPositionId(POSITION_ID);
      verify(positionRepository).delete(position);
    }
  }

  // =====================================================================
  // removeMember
  // =====================================================================

  @Nested
  @DisplayName("removeMember")
  class RemoveMemberTests {

    @Test
    @DisplayName("should 403 when the caller is not the project owner")
    void shouldThrowForbidden_whenNotOwner() {
      when(projectRepository.findByIdWithAuthor(PROJECT_ID))
          .thenReturn(Optional.of(project(OWNER_ID)));

      assertThatThrownBy(() -> projectService.removeMember(999, PROJECT_ID, APPLICANT_ID))
          .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("should 404 when the user is not an accepted member")
    void shouldThrowNotFound_whenNoMembership() {
      when(projectRepository.findByIdWithAuthor(PROJECT_ID))
          .thenReturn(Optional.of(project(OWNER_ID)));
      when(applicationRepository.findByProjectAndApplicantAndStatus(
              PROJECT_ID, APPLICANT_ID, ApplicationStatus.ACCEPTED))
          .thenReturn(List.of());

      assertThatThrownBy(() -> projectService.removeMember(OWNER_ID, PROJECT_ID, APPLICANT_ID))
          .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("should flag REMOVED, revoke reputation, and reopen a freed filled position")
    void shouldRemoveRevokeAndReopen() {
      ProjectEntity project = project(OWNER_ID);
      ProjectPositionEntity position = position(project, 1, PositionStatus.FILLED);
      ProjectApplicationEntity membership =
          application(APPLICATION_ID, project, position, APPLICANT_ID, ApplicationStatus.ACCEPTED);
      when(projectRepository.findByIdWithAuthor(PROJECT_ID)).thenReturn(Optional.of(project));
      when(applicationRepository.findByProjectAndApplicantAndStatus(
              PROJECT_ID, APPLICANT_ID, ApplicationStatus.ACCEPTED))
          .thenReturn(List.of(membership));
      when(positionRepository.findByIdForUpdate(POSITION_ID)).thenReturn(Optional.of(position));
      when(applicationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
      // after this membership flips to REMOVED, no ACCEPTED rows remain for the position
      when(applicationRepository.countByPositionIdAndStatus(
              POSITION_ID, ApplicationStatus.ACCEPTED))
          .thenReturn(0L);

      projectService.removeMember(OWNER_ID, PROJECT_ID, APPLICANT_ID);

      assertThat(membership.getStatus()).isEqualTo(ApplicationStatus.REMOVED);
      verify(reputationEventPublisher)
          .revoke(
              APPLICANT_ID, RepSourceType.PROJECT_APPLICATION_ACCEPTED, APPLICATION_ID.toString());
      assertThat(position.getStatus()).isEqualTo(PositionStatus.OPEN);
      verify(positionRepository).save(position);
      verify(notificationService)
          .send(
              org.mockito.ArgumentMatchers.argThat(
                  r ->
                      r.getRecipientId().equals(APPLICANT_ID)
                          && r.getType()
                              == com.socialapp.notifications.entity.enums.NotificationType
                                  .PROJECT_MEMBER_REMOVED));
    }
  }

  // =====================================================================
  // withdrawApplication
  // =====================================================================

  @Nested
  @DisplayName("withdrawApplication")
  class WithdrawApplicationTests {

    @Test
    @DisplayName("should 404 when the application does not exist")
    void shouldThrowNotFound_whenMissing() {
      when(applicationRepository.findById(APPLICATION_ID)).thenReturn(Optional.empty());

      assertThatThrownBy(() -> projectService.withdrawApplication(APPLICANT_ID, APPLICATION_ID))
          .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("should 403 when the caller is not the applicant")
    void shouldThrowForbidden_whenNotApplicant() {
      ProjectEntity project = project(OWNER_ID);
      ProjectPositionEntity position = position(project, 1, PositionStatus.OPEN);
      ProjectApplicationEntity application =
          application(APPLICATION_ID, project, position, APPLICANT_ID, ApplicationStatus.PENDING);
      when(applicationRepository.findById(APPLICATION_ID)).thenReturn(Optional.of(application));

      assertThatThrownBy(() -> projectService.withdrawApplication(999, APPLICATION_ID))
          .isInstanceOf(ForbiddenException.class);
      verify(applicationRepository, never()).delete(any());
    }

    @Test
    @DisplayName("should refuse to withdraw an application the owner has already decided")
    void shouldThrowConflict_whenNotPending() {
      ProjectEntity project = project(OWNER_ID);
      ProjectPositionEntity position = position(project, 1, PositionStatus.OPEN);
      ProjectApplicationEntity application =
          application(APPLICATION_ID, project, position, APPLICANT_ID, ApplicationStatus.ACCEPTED);
      when(applicationRepository.findById(APPLICATION_ID)).thenReturn(Optional.of(application));

      assertThatThrownBy(() -> projectService.withdrawApplication(APPLICANT_ID, APPLICATION_ID))
          .isInstanceOf(ConflictException.class);
      verify(applicationRepository, never()).delete(any());
    }

    @Test
    @DisplayName("should delete a pending application belonging to the caller")
    void shouldDelete_whenPendingAndOwnApplication() {
      ProjectEntity project = project(OWNER_ID);
      ProjectPositionEntity position = position(project, 1, PositionStatus.OPEN);
      ProjectApplicationEntity application =
          application(APPLICATION_ID, project, position, APPLICANT_ID, ApplicationStatus.PENDING);
      when(applicationRepository.findById(APPLICATION_ID)).thenReturn(Optional.of(application));

      projectService.withdrawApplication(APPLICANT_ID, APPLICATION_ID);

      verify(applicationRepository).delete(application);
    }
  }
}
