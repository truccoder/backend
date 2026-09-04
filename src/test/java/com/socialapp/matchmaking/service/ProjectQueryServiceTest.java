package com.socialapp.matchmaking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

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
import com.socialapp.matchmaking.dto.ProjectApplicationResponseDto;
import com.socialapp.matchmaking.dto.ProjectPageResponseDto;
import com.socialapp.matchmaking.dto.ProjectResponseDto;
import com.socialapp.matchmaking.entity.ProjectApplicationEntity;
import com.socialapp.matchmaking.entity.ProjectEntity;
import com.socialapp.matchmaking.entity.ProjectPositionEntity;
import com.socialapp.matchmaking.entity.enums.ApplicationStatus;
import com.socialapp.matchmaking.repository.ProjectApplicationRepository;
import com.socialapp.matchmaking.repository.ProjectPositionRepository;
import com.socialapp.matchmaking.repository.ProjectRepository;
import com.socialapp.security.entity.UserEntity;

/**
 * Component (unit) tests for {@link ProjectQueryService}, per ISTQB CTFL v4.0.1 (Section 2.2.1
 * component testing, Section 4.3.2 branch testing, Section 2.1.3 BDD Given/When/Then).
 */
@ExtendWith(MockitoExtension.class)
class ProjectQueryServiceTest {

  private static final Integer OWNER_ID = 1;
  private static final Integer STRANGER_ID = 2;
  private static final Integer PROJECT_ID = 30;

  @Mock private ProjectRepository projectRepository;
  @Mock private ProjectPositionRepository positionRepository;
  @Mock private ProjectApplicationRepository applicationRepository;

  @InjectMocks private ProjectQueryService projectQueryService;

  private static UserEntity user(Integer id) {
    UserEntity user = new UserEntity();
    user.setId(id);
    user.setUsername("user" + id);
    user.setFullName("User " + id);
    return user;
  }

  private static ProjectEntity project(Integer id) {
    ProjectEntity project = new ProjectEntity();
    project.setId(id);
    project.setTitle("Project " + id);
    project.setAuthor(user(OWNER_ID));
    return project;
  }

  private static ProjectPositionEntity position(Integer id, ProjectEntity project) {
    ProjectPositionEntity pos = new ProjectPositionEntity();
    pos.setId(id);
    pos.setProject(project);
    pos.setTitle("Backend engineer");
    pos.setRequiredSkills(List.of("java"));
    return pos;
  }

  // =====================================================================
  // getProjects
  // =====================================================================

  @Nested
  @DisplayName("getProjects")
  class GetProjectsTests {

    @Test
    @DisplayName("should attach each project's positions without a query per project")
    void shouldGroupPositionsByProject() {
      // Given
      ProjectEntity p = project(PROJECT_ID);
      when(projectRepository.findPage(any(), any())).thenReturn(List.of(p));
      when(positionRepository.findByProjectIdIn(List.of(PROJECT_ID)))
          .thenReturn(List.of(position(5, p)));

      // When
      ProjectPageResponseDto result = projectQueryService.getProjects(null, 10);

      // Then: one positions query for the whole page, grouped in memory
      assertThat(result.items()).hasSize(1);
      assertThat(result.items().get(0).getPositions()).hasSize(1);
      assertThat(result.items().get(0).getAuthorFullName()).isEqualTo("User 1");
      // B35: the owner's handle rides along so the card can link to /u/{username}
      assertThat(result.items().get(0).getAuthorUsername()).isEqualTo("user1");
    }

    @Test
    @DisplayName("should report hasMore and trim the extra row when a further page exists")
    void shouldTrimTheLookaheadRow() {
      // Given: the repository is asked for limit + 1 so hasMore needs no COUNT(*)
      when(projectRepository.findPage(any(), any()))
          .thenReturn(List.of(project(30), project(29), project(28)));
      when(positionRepository.findByProjectIdIn(any())).thenReturn(List.of());

      // When
      ProjectPageResponseDto result = projectQueryService.getProjects(null, 2);

      // Then
      assertThat(result.items()).hasSize(2);
      assertThat(result.hasMore()).isTrue();
      assertThat(result.nextCursor()).isEqualTo(29);
    }

    @Test
    @DisplayName("should return a null cursor for an empty page")
    void shouldReturnNullCursorWhenEmpty() {
      // Given
      when(projectRepository.findPage(any(), any())).thenReturn(List.of());

      // When
      ProjectPageResponseDto result = projectQueryService.getProjects(null, 10);

      // Then
      assertThat(result.items()).isEmpty();
      assertThat(result.nextCursor()).isNull();
      assertThat(result.hasMore()).isFalse();
    }
  }

  // =====================================================================
  // getProject
  // =====================================================================

  @Nested
  @DisplayName("getProject")
  class GetProjectTests {

    @Test
    @DisplayName("should return the project with its roles")
    void shouldReturnProjectWithPositions() {
      // Given
      ProjectEntity p = project(PROJECT_ID);
      when(projectRepository.findByIdWithAuthor(PROJECT_ID)).thenReturn(Optional.of(p));
      when(positionRepository.findByProjectIdIn(List.of(PROJECT_ID)))
          .thenReturn(List.of(position(5, p)));

      // When
      ProjectResponseDto result = projectQueryService.getProject(PROJECT_ID);

      // Then
      assertThat(result.getId()).isEqualTo(PROJECT_ID);
      assertThat(result.getPositions()).hasSize(1);
    }

    @Test
    @DisplayName("should 404 for a project that does not exist")
    void shouldThrowWhenMissing() {
      // Given
      when(projectRepository.findByIdWithAuthor(PROJECT_ID)).thenReturn(Optional.empty());

      // When / Then
      assertThatThrownBy(() -> projectQueryService.getProject(PROJECT_ID))
          .isInstanceOf(NotFoundException.class);
    }
  }

  // =====================================================================
  // getApplicationsForProject
  // =====================================================================

  @Nested
  @DisplayName("getApplicationsForProject")
  class GetApplicationsForProjectTests {

    @Test
    @DisplayName("should return the inbox to the project owner")
    void shouldReturnInboxToOwner() {
      // Given
      ProjectEntity p = project(PROJECT_ID);
      ProjectApplicationEntity application = new ProjectApplicationEntity();
      application.setId(70);
      application.setProject(p);
      application.setPosition(position(5, p));
      application.setApplicant(user(STRANGER_ID));
      application.setStatus(ApplicationStatus.PENDING);

      when(projectRepository.findByIdWithAuthor(PROJECT_ID)).thenReturn(Optional.of(p));
      when(applicationRepository.findByProjectIdForInbox(PROJECT_ID))
          .thenReturn(List.of(application));

      // When
      List<ProjectApplicationResponseDto> result =
          projectQueryService.getApplicationsForProject(PROJECT_ID, OWNER_ID);

      // Then
      assertThat(result).hasSize(1);
      assertThat(result.get(0).getApplicantFullName()).isEqualTo("User 2");
      // B35: the applicant's handle too, so the owner's inbox can link each row to a profile
      assertThat(result.get(0).getApplicantUsername()).isEqualTo("user2");
    }

    @Test
    @DisplayName("should refuse anyone who does not own the project")
    void shouldRefuseNonOwner() {
      // Given
      when(projectRepository.findByIdWithAuthor(PROJECT_ID))
          .thenReturn(Optional.of(project(PROJECT_ID)));

      // When / Then: 403 rather than an empty list — an empty list would still tell a stranger
      // how many people applied
      assertThatThrownBy(
              () -> projectQueryService.getApplicationsForProject(PROJECT_ID, STRANGER_ID))
          .isInstanceOf(ForbiddenException.class);
    }
  }

  // =====================================================================
  // getMembers
  // =====================================================================

  @Nested
  @DisplayName("getMembers")
  class GetMembersTests {

    @Test
    @DisplayName("should map every accepted application to a roster row — for any signed-in caller")
    void shouldReturnRoster() {
      // Given: unlike the application inbox this takes no caller and applies no ownership check
      ProjectEntity p = project(PROJECT_ID);
      ProjectApplicationEntity accepted = new ProjectApplicationEntity();
      accepted.setId(80);
      accepted.setProject(p);
      accepted.setPosition(position(5, p));
      accepted.setApplicant(user(STRANGER_ID));
      accepted.setStatus(ApplicationStatus.ACCEPTED);

      when(projectRepository.existsById(PROJECT_ID)).thenReturn(true);
      when(applicationRepository.findByProjectIdAndStatusForRoster(
              PROJECT_ID, ApplicationStatus.ACCEPTED))
          .thenReturn(List.of(accepted));

      // When
      var result = projectQueryService.getMembers(PROJECT_ID);

      // Then
      assertThat(result).hasSize(1);
      assertThat(result.get(0).getApplicationId()).isEqualTo(80);
      assertThat(result.get(0).getUserId()).isEqualTo(STRANGER_ID);
      assertThat(result.get(0).getUsername()).isEqualTo("user2");
      assertThat(result.get(0).getPositionId()).isEqualTo(5);
    }

    @Test
    @DisplayName("should 404 for a project that does not exist rather than return an empty roster")
    void shouldThrowWhenProjectMissing() {
      // Given
      when(projectRepository.existsById(PROJECT_ID)).thenReturn(false);

      // When / Then
      assertThatThrownBy(() -> projectQueryService.getMembers(PROJECT_ID))
          .isInstanceOf(NotFoundException.class);
    }
  }
}
