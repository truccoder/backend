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

import com.socialapp.common.exception.ForbiddenException;
import com.socialapp.common.exception.NotFoundException;
import com.socialapp.matchmaking.dto.ProjectPositionRequestDTO;
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

  @Mock private ProjectRepository projectRepository;
  @Mock private ProjectPositionRepository positionRepository;
  @Mock private ProjectApplicationRepository applicationRepository;
  @Mock private UserRepository userRepository;
  @Mock private ReputationEventPublisher reputationEventPublisher;

  @InjectMocks private ProjectService projectService;

  private static UserEntity user(Integer id) {
    UserEntity user = new UserEntity();
    user.setId(id);
    return user;
  }

  private static ProjectEntity project(Integer authorId) {
    ProjectEntity project = new ProjectEntity();
    project.setId(1);
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
          .isInstanceOf(IllegalStateException.class);
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
          .isInstanceOf(IllegalStateException.class);
      verify(applicationRepository, never()).save(any());
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
          .isInstanceOf(IllegalStateException.class);
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
          .isInstanceOf(IllegalStateException.class);
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
    }
  }
}
