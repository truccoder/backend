package com.socialapp.matchmaking.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.socialapp.common.exception.ForbiddenException;
import com.socialapp.common.exception.NotFoundException;
import com.socialapp.matchmaking.dto.ProjectApplicationResponseDto;
import com.socialapp.matchmaking.dto.ProjectPageResponseDto;
import com.socialapp.matchmaking.dto.ProjectResponseDto;
import com.socialapp.matchmaking.entity.ProjectEntity;
import com.socialapp.matchmaking.entity.enums.ApplicationStatus;
import com.socialapp.matchmaking.service.MatchmakingService;
import com.socialapp.matchmaking.service.ProjectQueryService;
import com.socialapp.matchmaking.service.ProjectService;
import com.socialapp.moderation.service.BanDetailsService;
import com.socialapp.security.config.CustomAccessDeniedHandler;
import com.socialapp.security.config.CustomAuthenticationEntryPoint;
import com.socialapp.security.config.JwtAuthenticationFilter;
import com.socialapp.security.config.JwtProvider;
import com.socialapp.security.config.SecurityConfig;
import com.socialapp.security.entity.UserEntity;
import com.socialapp.security.entity.UserRole;
import com.socialapp.security.repository.UserRepository;

/**
 * System/API integration tests for {@link ProjectController}, per ISTQB CTFL v4.0.1 Section 2.2.2,
 * using {@code @WebMvcTest} + {@code MockMvc}. The services are mocked.
 *
 * <p>Covers the read side added in this session — until then matchmaking could only be written to,
 * so the whole feature was reachable only by someone who already knew an id — plus the change that
 * made {@code POST /projects} return the created project instead of {@code void}.
 */
@WebMvcTest(ProjectController.class)
@Import({
  SecurityConfig.class,
  CustomAuthenticationEntryPoint.class,
  CustomAccessDeniedHandler.class,
  JwtAuthenticationFilter.class
})
class ProjectControllerTest {

  private static final String URL = "/v1/api/projects";
  private static final String TOKEN = "a-valid-jwt-token";
  private static final Integer OWNER_ID = 1;

  @Autowired private MockMvc mockMvc;

  @MockBean private ProjectService projectService;
  @MockBean private ProjectQueryService projectQueryService;
  @MockBean private MatchmakingService matchmakingService;
  @MockBean private JwtProvider jwtProvider;
  @MockBean private BanDetailsService banDetailsService;
  @MockBean private UserRepository userRepository;

  private UserEntity owner;

  @BeforeEach
  void setUpCaller() {
    owner = new UserEntity();
    owner.setId(OWNER_ID);
    owner.setEmail("owner@example.com");
    owner.setUsername("owner");
    owner.setFullName("Owner One");
    owner.setRole(UserRole.USER);
    owner.setEmailVerified(true);

    when(jwtProvider.isTokenValid(TOKEN)).thenReturn(true);
    when(jwtProvider.extractEmail(TOKEN)).thenReturn(owner.getEmail());
    when(userRepository.findByEmailIgnoreCase(owner.getEmail())).thenReturn(Optional.of(owner));
  }

  private static MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder b) {
    return b.header("Authorization", "Bearer " + TOKEN);
  }

  private static ProjectResponseDto project(Integer id) {
    return ProjectResponseDto.builder()
        .id(id)
        .title("Project " + id)
        .authorId(OWNER_ID)
        .authorFullName("Owner One")
        .positions(List.of())
        .build();
  }

  // =====================================================================
  // POST /v1/api/projects
  // =====================================================================

  @Nested
  @DisplayName("POST /v1/api/projects")
  class CreateProjectTests {

    private static final String BODY =
        """
        { "title": "Tim dong doi", "description": "Spring Boot side project" }
        """;

    @Test
    @DisplayName("shouldReturn201AndTheCreatedId_happyPath")
    void shouldReturn201AndId() throws Exception {
      // Given
      ProjectEntity created = new ProjectEntity();
      created.setId(7);
      created.setTitle("Tim dong doi");
      created.setAuthor(owner);
      when(projectService.createProject(eq(OWNER_ID), any())).thenReturn(created);

      // When / Then: it used to be `void`, so a client had no way to navigate to what it had just
      // created except by listing projects and guessing which one was new (B24).
      mockMvc
          .perform(authed(post(URL)).contentType(MediaType.APPLICATION_JSON).content(BODY))
          .andExpect(status().isCreated())
          .andExpect(jsonPath("$.id").value(7))
          .andExpect(jsonPath("$.title").value("Tim dong doi"));
    }

    @Test
    @DisplayName("shouldReturn422_whenTitleIsMissing")
    void shouldReturn422WhenTitleMissing() throws Exception {
      // When / Then — @NotBlank on the body is a 422 in this API, see GlobalExceptionHandler
      mockMvc
          .perform(
              authed(post(URL))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{ \"description\": \"no title\" }"))
          .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("shouldReturn401_whenCalledWithNoAuthorizationHeader")
    void shouldReturn401ForGuest() throws Exception {
      mockMvc
          .perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(BODY))
          .andExpect(status().isUnauthorized());
    }
  }

  // =====================================================================
  // GET /v1/api/projects
  // =====================================================================

  @Nested
  @DisplayName("GET /v1/api/projects")
  class ListProjectsTests {

    @Test
    @DisplayName("shouldReturn200AndCursorPage_happyPath")
    void shouldReturnCursorPage() throws Exception {
      // Given
      when(projectQueryService.getProjects(any(), org.mockito.ArgumentMatchers.anyInt()))
          .thenReturn(new ProjectPageResponseDto(List.of(project(2)), 2, false));

      // When / Then
      mockMvc
          .perform(authed(get(URL)))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.items[0].id").value(2))
          .andExpect(jsonPath("$.nextCursor").value(2))
          .andExpect(jsonPath("$.hasMore").value(false));
    }

    @Test
    @DisplayName("shouldPassCursorAndLimitThrough_whenProvided")
    void shouldPassParamsThrough() throws Exception {
      // Given
      when(projectQueryService.getProjects(any(), org.mockito.ArgumentMatchers.anyInt()))
          .thenReturn(new ProjectPageResponseDto(List.of(), null, false));

      // When
      mockMvc
          .perform(authed(get(URL)).param("cursor", "30").param("limit", "5"))
          .andExpect(status().isOk());

      // Then
      verify(projectQueryService).getProjects(30, 5);
    }

    @Test
    @DisplayName("shouldReturn422_whenLimitExceedsTheCap_boundary")
    void shouldRejectLimitAboveCap() throws Exception {
      // When / Then — BVA: @Max(50); an uncapped limit is a cheaper way to page the whole table
      mockMvc
          .perform(authed(get(URL)).param("limit", "51"))
          .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("shouldReturn422_whenLimitIsZero_boundary")
    void shouldRejectZeroLimit() throws Exception {
      mockMvc
          .perform(authed(get(URL)).param("limit", "0"))
          .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("shouldReturn401_whenCalledWithNoAuthorizationHeader")
    void shouldReturn401ForGuest() throws Exception {
      mockMvc.perform(get(URL)).andExpect(status().isUnauthorized());
    }
  }

  // =====================================================================
  // GET /v1/api/projects/{projectId}
  // =====================================================================

  @Nested
  @DisplayName("GET /v1/api/projects/{projectId}")
  class GetProjectTests {

    @Test
    @DisplayName("shouldReturn200AndTheProject_happyPath")
    void shouldReturnProject() throws Exception {
      when(projectQueryService.getProject(2)).thenReturn(project(2));

      mockMvc
          .perform(authed(get(URL + "/2")))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.id").value(2));
    }

    @Test
    @DisplayName("shouldReturn404_whenTheProjectDoesNotExist")
    void shouldReturn404() throws Exception {
      when(projectQueryService.getProject(999))
          .thenThrow(new NotFoundException("Project not found with ID: 999"));

      mockMvc.perform(authed(get(URL + "/999"))).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("shouldNotSwallowTheSiblingLiteralPath_whenIdIsNotANumber")
    void shouldRouteLiteralSiblingPath() throws Exception {
      // The path variable is constrained to digits precisely so /projects/applications/mine keeps
      // routing instead of trying to bind "applications" as a project id.
      when(projectQueryService.getMyApplications(OWNER_ID)).thenReturn(List.of());

      mockMvc.perform(authed(get(URL + "/applications/mine"))).andExpect(status().isOk());
    }
  }

  // =====================================================================
  // GET /v1/api/projects/{projectId}/applications  — owner only
  // =====================================================================

  @Nested
  @DisplayName("GET /v1/api/projects/{projectId}/applications")
  class ProjectApplicationsTests {

    @Test
    @DisplayName("shouldReturn200AndTheInbox_whenCallerOwnsTheProject")
    void shouldReturnInboxToOwner() throws Exception {
      when(projectQueryService.getApplicationsForProject(2, OWNER_ID))
          .thenReturn(
              List.of(
                  ProjectApplicationResponseDto.builder()
                      .id(70)
                      .projectId(2)
                      .applicantId(9)
                      .applicantFullName("Someone Else")
                      .status(ApplicationStatus.PENDING)
                      .build()));

      mockMvc
          .perform(authed(get(URL + "/2/applications")))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$[0].applicantFullName").value("Someone Else"));
    }

    @Test
    @DisplayName("shouldReturn403_whenCallerDoesNotOwnTheProject")
    void shouldReturn403ForNonOwner() throws Exception {
      // A 403 rather than an empty list: an empty list would still tell a stranger that the
      // project has no applicants, which is also not theirs to know.
      doThrow(new ForbiddenException("Not authorized to read applications for this project"))
          .when(projectQueryService)
          .getApplicationsForProject(2, OWNER_ID);

      mockMvc.perform(authed(get(URL + "/2/applications"))).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("shouldReturn401_whenCalledWithNoAuthorizationHeader")
    void shouldReturn401ForGuest() throws Exception {
      mockMvc.perform(get(URL + "/2/applications")).andExpect(status().isUnauthorized());
    }
  }

  // =====================================================================
  // GET /v1/api/projects/applications/mine
  // =====================================================================

  @Nested
  @DisplayName("GET /v1/api/projects/applications/mine")
  class MyApplicationsTests {

    @Test
    @DisplayName("shouldReturn200AndOnlyTheCallersOwnApplications_happyPath")
    void shouldReturnOwnApplications() throws Exception {
      when(projectQueryService.getMyApplications(OWNER_ID))
          .thenReturn(
              List.of(
                  ProjectApplicationResponseDto.builder()
                      .id(70)
                      .projectTitle("Somebody's project")
                      .status(ApplicationStatus.REJECTED)
                      .build()));

      mockMvc
          .perform(authed(get(URL + "/applications/mine")))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$[0].status").value("REJECTED"));

      verify(projectQueryService).getMyApplications(OWNER_ID);
    }

    @Test
    @DisplayName("shouldReturn401_whenCalledWithNoAuthorizationHeader")
    void shouldReturn401ForGuest() throws Exception {
      mockMvc.perform(get(URL + "/applications/mine")).andExpect(status().isUnauthorized());
    }
  }
}
