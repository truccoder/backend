package com.socialapp.matchmaking.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
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

import com.socialapp.common.exception.ConflictException;
import com.socialapp.common.exception.ForbiddenException;
import com.socialapp.common.exception.NotFoundException;
import com.socialapp.matchmaking.dto.JobDescriptionUrlResponse;
import com.socialapp.matchmaking.dto.ProjectApplicationResponseDto;
import com.socialapp.matchmaking.dto.ProjectMemberDto;
import com.socialapp.matchmaking.dto.ProjectPageResponseDto;
import com.socialapp.matchmaking.dto.ProjectResponseDto;
import com.socialapp.matchmaking.dto.SuggestedCandidateDto;
import com.socialapp.matchmaking.dto.SuggestedProjectDto;
import com.socialapp.matchmaking.entity.ProjectEntity;
import com.socialapp.matchmaking.entity.ProjectPositionEntity;
import com.socialapp.matchmaking.entity.enums.ApplicationStatus;
import com.socialapp.matchmaking.entity.enums.PositionStatus;
import com.socialapp.matchmaking.entity.enums.ProjectStatus;
import com.socialapp.matchmaking.service.JobDescriptionService;
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

  /** {@code Constants.DEFAULT_PAGINATION_PAGE_SIZE} — what the controller binds when the
   * caller omits {@code limit}. Asserted rather than matched loosely, because "the default
   * actually reaches the service" is part of the contract. */
  private static final int DEFAULT_LIMIT = 10;

  @Autowired private MockMvc mockMvc;

  @MockBean private ProjectService projectService;
  @MockBean private ProjectQueryService projectQueryService;
  @MockBean private MatchmakingService matchmakingService;
  @MockBean private JobDescriptionService jobDescriptionService;
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
        .authorUsername("owner")
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
          .andExpect(jsonPath("$.items[0].authorUsername").value("owner"))
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
                      .applicantUsername("someone")
                      .applicantFullName("Someone Else")
                      .status(ApplicationStatus.PENDING)
                      .build()));

      mockMvc
          .perform(authed(get(URL + "/2/applications")))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$[0].applicantFullName").value("Someone Else"))
          .andExpect(jsonPath("$[0].applicantUsername").value("someone"));
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

  // =====================================================================
  // POST /v1/api/projects/positions/{positionId}/apply
  // =====================================================================

  @Nested
  @DisplayName("POST /v1/api/projects/positions/{positionId}/apply")
  class ApplyToPositionTests {

    private static final Integer POSITION_ID = 30;

    @Test
    @DisplayName("shouldReturn200AndRecordTheApplication_happyPath")
    void shouldApply() throws Exception {
      // When
      mockMvc
          .perform(
              authed(post(URL + "/positions/" + POSITION_ID + "/apply"))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"message\":\"I would like to join\"}"))
          .andExpect(status().isOk());

      // Then: the applicant is the token holder, never a client-supplied id
      verify(projectService).applyToPosition(OWNER_ID, POSITION_ID, "I would like to join");
    }

    @Test
    @DisplayName("shouldReturn200_whenTheMessageIsOmitted_becauseItIsOptional")
    void shouldAllowNoMessage() throws Exception {
      // Given: ApplicationRequestDTO.message carries no constraint, so an empty body is valid

      // When / Then
      mockMvc
          .perform(
              authed(post(URL + "/positions/" + POSITION_ID + "/apply"))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{}"))
          .andExpect(status().isOk());

      verify(projectService).applyToPosition(OWNER_ID, POSITION_ID, null);
    }

    @Test
    @DisplayName("shouldReturn404_whenThePositionDoesNotExist")
    void shouldReturn404() throws Exception {
      // Given
      doThrow(new NotFoundException("Position not found"))
          .when(projectService)
          .applyToPosition(OWNER_ID, 999, null);

      // When / Then
      mockMvc
          .perform(
              authed(post(URL + "/positions/999/apply"))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{}"))
          .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("shouldReturn409_whenThePositionIsNoLongerOpen")
    void shouldReturn409WhenFilled() throws Exception {
      // Given: a wrong-state request is IllegalStateException -> 409, the convention this
      // codebase settled on for state-transition guards.
      doThrow(new ConflictException("This position is no longer open"))
          .when(projectService)
          .applyToPosition(OWNER_ID, POSITION_ID, null);

      // When / Then
      mockMvc
          .perform(
              authed(post(URL + "/positions/" + POSITION_ID + "/apply"))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{}"))
          .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("shouldReturn400_whenTheBodyIsMalformedJson")
    void shouldReturn400OnMalformedJson() throws Exception {
      // When / Then
      mockMvc
          .perform(
              authed(post(URL + "/positions/" + POSITION_ID + "/apply"))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"message\":"))
          .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("shouldReturn401_whenCalledWithNoAuthorizationHeader")
    void shouldReturn401ForGuest() throws Exception {
      // When / Then
      mockMvc
          .perform(
              post(URL + "/positions/" + POSITION_ID + "/apply")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{}"))
          .andExpect(status().isUnauthorized());

      verify(projectService, never()).applyToPosition(any(), any(), any());
    }
  }

  // =====================================================================
  // POST /v1/api/projects/applications/{applicationId}/accept
  // =====================================================================

  @Nested
  @DisplayName("POST /v1/api/projects/applications/{applicationId}/accept")
  class AcceptApplicationTests {

    private static final Integer APPLICATION_ID = 70;

    @Test
    @DisplayName("shouldReturn200AndAcceptTheApplication_happyPath")
    void shouldAccept() throws Exception {
      // When
      mockMvc
          .perform(authed(post(URL + "/applications/" + APPLICATION_ID + "/accept")))
          .andExpect(status().isOk());

      // Then: the owner id comes from the token — the service re-checks ownership itself
      verify(projectService).acceptApplication(OWNER_ID, APPLICATION_ID);
    }

    @Test
    @DisplayName("shouldReturn403_whenTheCallerDoesNotOwnTheProject")
    void shouldReturn403ForNonOwner() throws Exception {
      // Given: accepting somebody into a project you do not own is the core IDOR risk here
      doThrow(new ForbiddenException("Not authorized to decide this application"))
          .when(projectService)
          .acceptApplication(OWNER_ID, APPLICATION_ID);

      // When / Then
      mockMvc
          .perform(authed(post(URL + "/applications/" + APPLICATION_ID + "/accept")))
          .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("shouldReturn409_whenTheApplicationWasAlreadyDecided")
    void shouldReturn409OnAlreadyDecided() throws Exception {
      // Given: the state guard added alongside rejectApplication — a decided application may
      // not be re-decided.
      doThrow(new ConflictException("This application has already been decided"))
          .when(projectService)
          .acceptApplication(OWNER_ID, APPLICATION_ID);

      // When / Then
      mockMvc
          .perform(authed(post(URL + "/applications/" + APPLICATION_ID + "/accept")))
          .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("shouldReturn404_whenTheApplicationDoesNotExist")
    void shouldReturn404() throws Exception {
      // Given
      doThrow(new NotFoundException("Application not found"))
          .when(projectService)
          .acceptApplication(OWNER_ID, 999);

      // When / Then
      mockMvc
          .perform(authed(post(URL + "/applications/999/accept")))
          .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("shouldReturn401_whenCalledWithNoAuthorizationHeader")
    void shouldReturn401ForGuest() throws Exception {
      // When / Then
      mockMvc
          .perform(post(URL + "/applications/" + APPLICATION_ID + "/accept"))
          .andExpect(status().isUnauthorized());

      verify(projectService, never()).acceptApplication(any(), any());
    }
  }

  // =====================================================================
  // POST /v1/api/projects/applications/{applicationId}/reject
  // =====================================================================

  @Nested
  @DisplayName("POST /v1/api/projects/applications/{applicationId}/reject")
  class RejectApplicationTests {

    private static final Integer APPLICATION_ID = 70;

    @Test
    @DisplayName("shouldReturn200AndRejectTheApplication_happyPath")
    void shouldReject() throws Exception {
      // When
      mockMvc
          .perform(authed(post(URL + "/applications/" + APPLICATION_ID + "/reject")))
          .andExpect(status().isOk());

      // Then
      verify(projectService).rejectApplication(OWNER_ID, APPLICATION_ID);
    }

    @Test
    @DisplayName("shouldReturn403_whenTheCallerDoesNotOwnTheProject")
    void shouldReturn403ForNonOwner() throws Exception {
      // Given
      doThrow(new ForbiddenException("Not authorized to decide this application"))
          .when(projectService)
          .rejectApplication(OWNER_ID, APPLICATION_ID);

      // When / Then
      mockMvc
          .perform(authed(post(URL + "/applications/" + APPLICATION_ID + "/reject")))
          .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("shouldReturn409_whenTheApplicationWasAlreadyDecided")
    void shouldReturn409OnAlreadyDecided() throws Exception {
      // Given
      doThrow(new ConflictException("This application has already been decided"))
          .when(projectService)
          .rejectApplication(OWNER_ID, APPLICATION_ID);

      // When / Then
      mockMvc
          .perform(authed(post(URL + "/applications/" + APPLICATION_ID + "/reject")))
          .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("shouldReturn401_whenCalledWithNoAuthorizationHeader")
    void shouldReturn401ForGuest() throws Exception {
      // When / Then
      mockMvc
          .perform(post(URL + "/applications/" + APPLICATION_ID + "/reject"))
          .andExpect(status().isUnauthorized());

      verify(projectService, never()).rejectApplication(any(), any());
    }
  }

  // =====================================================================
  // GET /v1/api/projects/suggested
  // =====================================================================

  @Nested
  @DisplayName("GET /v1/api/projects/suggested")
  class SuggestedProjectsTests {

    private static SuggestedProjectDto suggestion(Integer projectId, int score) {
      return new SuggestedProjectDto(
          ProjectResponseDto.builder().id(projectId).title("Project " + projectId).build(),
          score,
          List.of("Java"),
          List.of("API Design"),
          List.of(1));
    }

    @Test
    @DisplayName("shouldReturn200AndTheRankedProjects_happyPath")
    void shouldReturnSuggestions() throws Exception {
      // Given
      when(matchmakingService.suggestProjects(OWNER_ID, DEFAULT_LIMIT))
          .thenReturn(List.of(suggestion(4001, 9), suggestion(4002, 3)));

      // When / Then: the reason ships with the recommendation, so a client can explain the order.
      mockMvc
          .perform(authed(get(URL + "/suggested")))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.length()").value(2))
          .andExpect(jsonPath("$[0].project.id").value(4001))
          .andExpect(jsonPath("$[0].matchScore").value(9))
          .andExpect(jsonPath("$[0].matchedSkills[0]").value("Java"))
          .andExpect(jsonPath("$[0].matchedDomains[0]").value("API Design"))
          .andExpect(jsonPath("$[1].matchScore").value(3));
    }

    @Test
    @DisplayName("shouldReturn200AndAnEmptyList_whenTheCallerHasNoProfessionalProfile")
    void shouldReturnEmptyList() throws Exception {
      // Given: with nothing to rank against, the honest answer is an empty list rather than the
      // ordinary newest-first project list wearing a "suggested" label.
      when(matchmakingService.suggestProjects(OWNER_ID, DEFAULT_LIMIT)).thenReturn(List.of());

      // When / Then
      mockMvc
          .perform(authed(get(URL + "/suggested")))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("shouldPassTheRequestedLimitThrough_whenGiven")
    void shouldHonourExplicitLimit() throws Exception {
      // Given
      when(matchmakingService.suggestProjects(OWNER_ID, 3)).thenReturn(List.of());

      // When / Then
      mockMvc.perform(authed(get(URL + "/suggested?limit=3"))).andExpect(status().isOk());

      verify(matchmakingService).suggestProjects(OWNER_ID, 3);
    }

    @Test
    @DisplayName("shouldReturn422_whenLimitExceedsTheMaximumPageSize")
    void shouldReject_whenLimitTooLarge() throws Exception {
      // When / Then: same @Max(Constants.MAX_PAGINATION_PAGE_SIZE) guard as the browse endpoint,
      // so one caller cannot ask for the whole pool in a single request.
      //
      // 422 rather than the 400 that GlobalExceptionHandler documents for @RequestParam failures:
      // ProjectController carries @Validated, which moves its parameter constraints onto the older
      // AOP path (ConstraintViolationException -> the jakarta ValidationException handler) instead
      // of Spring 6.1's native HandlerMethodValidationException. BookController and
      // SearchController
      // are on the same path. Pre-existing and deliberate — asserting the real behaviour here
      // rather than flipping a shared handler to make one new endpoint read nicer.
      mockMvc
          .perform(authed(get(URL + "/suggested?limit=51")))
          .andExpect(status().isUnprocessableEntity());

      verify(matchmakingService, never()).suggestProjects(any(), anyInt());
    }

    @Test
    @DisplayName("shouldReturn422_whenLimitIsNotPositive")
    void shouldReject_whenLimitIsZero() throws Exception {
      // When / Then: see the note above on 422 vs 400 for this controller.
      mockMvc
          .perform(authed(get(URL + "/suggested?limit=0")))
          .andExpect(status().isUnprocessableEntity());

      verify(matchmakingService, never()).suggestProjects(any(), anyInt());
    }

    @Test
    @DisplayName("shouldRouteToSuggested_ratherThanBindingItAsAProjectId")
    void shouldNotBeSwallowedByTheProjectIdRoute() throws Exception {
      // Given: /{projectId} is constrained to digits precisely so literal sibling paths like this
      // one keep routing. Loosening that regex turns this endpoint into a 400.
      when(matchmakingService.suggestProjects(OWNER_ID, DEFAULT_LIMIT)).thenReturn(List.of());

      // When / Then
      mockMvc.perform(authed(get(URL + "/suggested"))).andExpect(status().isOk());

      verify(projectQueryService, never()).getProject(any());
    }

    @Test
    @DisplayName("shouldReturn401_whenCalledWithNoAuthorizationHeader")
    void shouldReturn401ForGuest() throws Exception {
      // When / Then
      mockMvc.perform(get(URL + "/suggested")).andExpect(status().isUnauthorized());

      verify(matchmakingService, never()).suggestProjects(any(), anyInt());
    }
  }

  // =====================================================================
  // GET /v1/api/projects/positions/{positionId}/suggested-candidates
  // =====================================================================

  @Nested
  @DisplayName("GET /v1/api/projects/positions/{positionId}/suggested-candidates")
  class SuggestedCandidatesTests {

    private static final Integer POSITION_ID = 30;

    @Test
    @DisplayName("shouldReturn200AndTheCandidateList_happyPath")
    void shouldReturnCandidates() throws Exception {
      // Given
      when(matchmakingService.suggestCandidates(POSITION_ID, OWNER_ID, DEFAULT_LIMIT))
          .thenReturn(
              List.of(
                  SuggestedCandidateDto.builder()
                      .userId(77)
                      .jobTitle("Backend Engineer")
                      .yearsOfExperience(4)
                      .knownTechStack(List.of("Java", "Spring"))
                      .matchScore(6)
                      .matchedSkills(List.of("Java", "Spring"))
                      .build()));

      // When / Then
      mockMvc
          .perform(authed(get(URL + "/positions/" + POSITION_ID + "/suggested-candidates")))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$[0].userId").value(77))
          .andExpect(jsonPath("$[0].jobTitle").value("Backend Engineer"));
    }

    @Test
    @DisplayName("shouldReturn200AndAnEmptyList_whenThePositionListsNoRequiredSkills")
    void shouldReturnEmpty() throws Exception {
      // Given: suggestCandidates short-circuits to List.of() for a null/empty skill list
      when(matchmakingService.suggestCandidates(POSITION_ID, OWNER_ID, DEFAULT_LIMIT))
          .thenReturn(List.of());

      // When / Then
      mockMvc
          .perform(authed(get(URL + "/positions/" + POSITION_ID + "/suggested-candidates")))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("shouldReturn404_whenThePositionDoesNotExist")
    void shouldReturn404() throws Exception {
      // Given
      when(matchmakingService.suggestCandidates(999, OWNER_ID, DEFAULT_LIMIT))
          .thenThrow(new NotFoundException("Position not found"));

      // When / Then
      mockMvc
          .perform(authed(get(URL + "/positions/999/suggested-candidates")))
          .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("shouldReturn403_whenTheCallerDoesNotOwnTheProject")
    void shouldReturn403ForNonOwner() throws Exception {
      // Given: the reply carries other users' job title, seniority, years of experience and tech
      // stack. This endpoint used to take only a position id, so any signed-in caller could walk
      // ids and harvest the directory — it now passes the caller through like its siblings.
      when(matchmakingService.suggestCandidates(POSITION_ID, OWNER_ID, DEFAULT_LIMIT))
          .thenThrow(new ForbiddenException("Not authorized to view candidates for this position"));

      // When / Then
      mockMvc
          .perform(authed(get(URL + "/positions/" + POSITION_ID + "/suggested-candidates")))
          .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("shouldReturn401_whenCalledWithNoAuthorizationHeader")
    void shouldReturn401ForGuest() throws Exception {
      // When / Then
      mockMvc
          .perform(get(URL + "/positions/" + POSITION_ID + "/suggested-candidates"))
          .andExpect(status().isUnauthorized());

      verify(matchmakingService, never()).suggestCandidates(any(), any(), anyInt());
    }
  }

  // =====================================================================
  // Owner project management — PUT/PATCH/DELETE, positions, members
  // =====================================================================

  private static ProjectPositionEntity positionEntity(Integer id, PositionStatus status) {
    ProjectPositionEntity p = new ProjectPositionEntity();
    p.setId(id);
    p.setTitle("Backend Developer");
    p.setQuantity(2);
    p.setStatus(status);
    return p;
  }

  @Nested
  @DisplayName("PUT /v1/api/projects/{projectId}")
  class UpdateProjectTests {

    private static final String BODY =
        "{ \"title\": \"Renamed\", \"description\": \"Now with a scope\" }";

    @Test
    @DisplayName("shouldReturn200AndTheUpdatedProject_happyPath")
    void shouldUpdate() throws Exception {
      when(projectQueryService.getProject(2)).thenReturn(project(2));

      mockMvc
          .perform(authed(put(URL + "/2")).contentType(MediaType.APPLICATION_JSON).content(BODY))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.id").value(2));

      verify(projectService)
          .updateProject(
              org.mockito.ArgumentMatchers.eq(OWNER_ID), org.mockito.ArgumentMatchers.eq(2), any());
    }

    @Test
    @DisplayName("shouldReturn422_whenTitleIsBlank")
    void shouldReturn422OnBlankTitle() throws Exception {
      mockMvc
          .perform(
              authed(put(URL + "/2"))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{ \"title\": \"\", \"description\": \"x\" }"))
          .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("shouldReturn403_whenTheCallerIsNotTheOwner")
    void shouldReturn403ForNonOwner() throws Exception {
      doThrow(new ForbiddenException("Not authorized to manage this project"))
          .when(projectService)
          .updateProject(
              org.mockito.ArgumentMatchers.eq(OWNER_ID), org.mockito.ArgumentMatchers.eq(2), any());

      mockMvc
          .perform(authed(put(URL + "/2")).contentType(MediaType.APPLICATION_JSON).content(BODY))
          .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("shouldReturn409_whenTheProjectIsCompleted")
    void shouldReturn409WhenCompleted() throws Exception {
      doThrow(new ConflictException("A completed project cannot be edited"))
          .when(projectService)
          .updateProject(
              org.mockito.ArgumentMatchers.eq(OWNER_ID), org.mockito.ArgumentMatchers.eq(2), any());

      mockMvc
          .perform(authed(put(URL + "/2")).contentType(MediaType.APPLICATION_JSON).content(BODY))
          .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("shouldReturn401_whenCalledWithNoAuthorizationHeader")
    void shouldReturn401ForGuest() throws Exception {
      mockMvc
          .perform(put(URL + "/2").contentType(MediaType.APPLICATION_JSON).content(BODY))
          .andExpect(status().isUnauthorized());

      verify(projectService, never()).updateProject(any(), any(), any());
    }
  }

  @Nested
  @DisplayName("PATCH /v1/api/projects/{projectId}/status")
  class UpdateProjectStatusTests {

    @Test
    @DisplayName("shouldReturn200AndMoveTheProjectToTheTargetStatus_happyPath")
    void shouldUpdateStatus() throws Exception {
      when(projectQueryService.getProject(2)).thenReturn(project(2));

      mockMvc
          .perform(
              authed(patch(URL + "/2/status"))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{ \"status\": \"CLOSED\" }"))
          .andExpect(status().isOk());

      verify(projectService).updateStatus(OWNER_ID, 2, ProjectStatus.CLOSED);
    }

    @Test
    @DisplayName("shouldReturn422_whenStatusIsMissing")
    void shouldReturn422WhenStatusMissing() throws Exception {
      mockMvc
          .perform(
              authed(patch(URL + "/2/status"))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{}"))
          .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("shouldReturn409_whenLeavingACompletedProject")
    void shouldReturn409WhenLeavingCompleted() throws Exception {
      doThrow(new ConflictException("A completed project cannot change status"))
          .when(projectService)
          .updateStatus(OWNER_ID, 2, ProjectStatus.OPEN);

      mockMvc
          .perform(
              authed(patch(URL + "/2/status"))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{ \"status\": \"OPEN\" }"))
          .andExpect(status().isConflict());
    }
  }

  @Nested
  @DisplayName("DELETE /v1/api/projects/{projectId}")
  class DeleteProjectTests {

    @Test
    @DisplayName("shouldReturn204_happyPath")
    void shouldDelete() throws Exception {
      mockMvc.perform(authed(delete(URL + "/2"))).andExpect(status().isNoContent());

      verify(projectService).deleteProject(OWNER_ID, 2);
    }

    @Test
    @DisplayName("shouldReturn403_whenTheCallerIsNotTheOwner")
    void shouldReturn403ForNonOwner() throws Exception {
      doThrow(new ForbiddenException("Not authorized to manage this project"))
          .when(projectService)
          .deleteProject(OWNER_ID, 2);

      mockMvc.perform(authed(delete(URL + "/2"))).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("shouldReturn401_whenCalledWithNoAuthorizationHeader")
    void shouldReturn401ForGuest() throws Exception {
      mockMvc.perform(delete(URL + "/2")).andExpect(status().isUnauthorized());

      verify(projectService, never()).deleteProject(any(), any());
    }
  }

  @Nested
  @DisplayName("GET /v1/api/projects/{projectId}/members")
  class GetMembersTests {

    @Test
    @DisplayName("shouldReturn200AndTheRoster_forAnySignedInCaller")
    void shouldReturnRoster() throws Exception {
      when(projectQueryService.getMembers(2))
          .thenReturn(
              List.of(
                  ProjectMemberDto.builder()
                      .applicationId(80)
                      .userId(9)
                      .username("teammate")
                      .positionId(5)
                      .positionTitle("Backend Developer")
                      .build()));

      mockMvc
          .perform(authed(get(URL + "/2/members")))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$[0].userId").value(9))
          .andExpect(jsonPath("$[0].username").value("teammate"))
          .andExpect(jsonPath("$[0].applicationId").value(80));
    }

    @Test
    @DisplayName("shouldReturn404_whenTheProjectDoesNotExist")
    void shouldReturn404() throws Exception {
      when(projectQueryService.getMembers(999))
          .thenThrow(new NotFoundException("Project not found with ID: 999"));

      mockMvc.perform(authed(get(URL + "/999/members"))).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("shouldReturn401_whenCalledWithNoAuthorizationHeader")
    void shouldReturn401ForGuest() throws Exception {
      mockMvc.perform(get(URL + "/2/members")).andExpect(status().isUnauthorized());
    }
  }

  @Nested
  @DisplayName("DELETE /v1/api/projects/{projectId}/members/{userId}")
  class RemoveMemberTests {

    @Test
    @DisplayName("shouldReturn204AndPassBothIdsThrough_happyPath")
    void shouldRemoveMember() throws Exception {
      mockMvc.perform(authed(delete(URL + "/2/members/9"))).andExpect(status().isNoContent());

      verify(projectService).removeMember(OWNER_ID, 2, 9);
    }

    @Test
    @DisplayName("shouldReturn404_whenThatUserIsNotAnAcceptedMember")
    void shouldReturn404() throws Exception {
      doThrow(new NotFoundException("That user is not an accepted member of this project"))
          .when(projectService)
          .removeMember(OWNER_ID, 2, 9);

      mockMvc.perform(authed(delete(URL + "/2/members/9"))).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("shouldReturn403_whenTheCallerIsNotTheOwner")
    void shouldReturn403ForNonOwner() throws Exception {
      doThrow(new ForbiddenException("Not authorized to manage this project"))
          .when(projectService)
          .removeMember(OWNER_ID, 2, 9);

      mockMvc.perform(authed(delete(URL + "/2/members/9"))).andExpect(status().isForbidden());
    }
  }

  @Nested
  @DisplayName("POST /v1/api/projects/{projectId}/positions")
  class AddPositionTests {

    /**
     * A whole job description, because {@code ProjectPositionRequestDTO} now requires one: a role
     * summary, at least two responsibilities, at least two requirements, and the skills the
     * matcher matches on. A title alone is a 422 — see {@code shouldReturn422_whenTheRoleHasNoJobDescription}.
     */
    private static final String BODY =
        """
        {
          "title": "Frontend Developer",
          "roleSummary": "Build the screens this project is judged on, next to one other engineer.",
          "responsibilities": ["Build the screens", "Review UI pull requests"],
          "requirements": ["Two years with React", "Comfortable reading English docs"],
          "requiredSkills": ["React", "TypeScript"],
          "quantity": 1
        }
        """;

    @Test
    @DisplayName("shouldReturn201AndTheCreatedPosition_happyPath")
    void shouldAddPosition() throws Exception {
      when(projectService.addPosition(
              org.mockito.ArgumentMatchers.eq(OWNER_ID), org.mockito.ArgumentMatchers.eq(2), any()))
          .thenReturn(positionEntity(55, PositionStatus.OPEN));

      mockMvc
          .perform(
              authed(post(URL + "/2/positions"))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(BODY))
          .andExpect(status().isCreated())
          .andExpect(jsonPath("$.id").value(55))
          .andExpect(jsonPath("$.status").value("OPEN"));
    }

    @Test
    @DisplayName("shouldReturn422_whenPositionTitleIsMissing")
    void shouldReturn422OnMissingTitle() throws Exception {
      mockMvc
          .perform(
              authed(post(URL + "/2/positions"))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{ \"roleSummary\": \"no title\" }"))
          .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("shouldReturn422_whenTheRoleHasNoJobDescription")
    void shouldReturn422WithoutJobDescription() throws Exception {
      // The posting shape that used to be accepted, and the reason matchmaking could not work:
      // a title with no summary, no responsibilities, no requirements and no skills to match on
      // produced a role the candidate endpoint answered with an empty list, silently, with a 200.
      mockMvc
          .perform(
              authed(post(URL + "/2/positions"))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{ \"title\": \"Frontend Developer\" }"))
          .andExpect(status().isUnprocessableEntity());

      verify(projectService, never()).addPosition(any(), any(), any());
    }

    @Test
    @DisplayName("shouldReturn422_whenTheRoleListsOnlyOneResponsibility")
    void shouldReturn422OnThinResponsibilities() throws Exception {
      mockMvc
          .perform(
              authed(post(URL + "/2/positions"))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      BODY.replace(
                          "[\"Build the screens\", \"Review UI pull requests\"]",
                          "[\"Build the screens\"]")))
          .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("shouldReturn409_whenTheProjectIsCompleted")
    void shouldReturn409WhenCompleted() throws Exception {
      doThrow(new ConflictException("Cannot add a position to a completed project"))
          .when(projectService)
          .addPosition(
              org.mockito.ArgumentMatchers.eq(OWNER_ID), org.mockito.ArgumentMatchers.eq(2), any());

      mockMvc
          .perform(
              authed(post(URL + "/2/positions"))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(BODY))
          .andExpect(status().isConflict());
    }
  }

  @Nested
  @DisplayName("PUT /v1/api/projects/positions/{positionId}")
  class UpdatePositionTests {

    private static final String BODY =
        """
        {
          "title": "Backend Developer",
          "roleSummary": "Own the API layer and the background jobs behind it, schema to deploy.",
          "responsibilities": ["Design the endpoints", "Keep the migrations honest"],
          "requirements": ["Three years with Java", "Has shipped a REST API"],
          "requiredSkills": ["Java", "Spring"],
          "quantity": 3
        }
        """;

    @Test
    @DisplayName("shouldReturn200AndTheUpdatedPosition_happyPath")
    void shouldUpdatePosition() throws Exception {
      when(projectService.updatePosition(
              org.mockito.ArgumentMatchers.eq(OWNER_ID),
              org.mockito.ArgumentMatchers.eq(30),
              any()))
          .thenReturn(positionEntity(30, PositionStatus.OPEN));

      mockMvc
          .perform(
              authed(put(URL + "/positions/30"))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(BODY))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.id").value(30));
    }

    @Test
    @DisplayName("shouldReturn409_whenQuantityWouldDropBelowFilledSeats")
    void shouldReturn409OnQuantityConflict() throws Exception {
      doThrow(new ConflictException("Quantity cannot be below the 2 seat(s) already filled"))
          .when(projectService)
          .updatePosition(
              org.mockito.ArgumentMatchers.eq(OWNER_ID),
              org.mockito.ArgumentMatchers.eq(30),
              any());

      mockMvc
          .perform(
              authed(put(URL + "/positions/30"))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(BODY.replace("\"quantity\": 3", "\"quantity\": 1")))
          .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("shouldRouteToThisLiteralPath_ratherThanTheProjectIdRoute")
    void shouldNotBeSwallowedByProjectIdRoute() throws Exception {
      // PUT /positions/{id} sits beside PUT /{projectId:\\d+}; "positions" is not digits so it
      // must reach this handler, not bind as a project id.
      when(projectService.updatePosition(
              org.mockito.ArgumentMatchers.eq(OWNER_ID),
              org.mockito.ArgumentMatchers.eq(30),
              any()))
          .thenReturn(positionEntity(30, PositionStatus.OPEN));

      mockMvc
          .perform(
              authed(put(URL + "/positions/30"))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(BODY))
          .andExpect(status().isOk());

      verify(projectService, never()).updateProject(any(), any(), any());
    }
  }

  @Nested
  @DisplayName("PATCH /v1/api/projects/positions/{positionId}/status")
  class UpdatePositionStatusTests {

    @Test
    @DisplayName("shouldReturn200AndSetTheStatus_happyPath")
    void shouldUpdate() throws Exception {
      when(projectService.updatePositionStatus(OWNER_ID, 30, PositionStatus.CLOSED))
          .thenReturn(positionEntity(30, PositionStatus.CLOSED));

      mockMvc
          .perform(
              authed(patch(URL + "/positions/30/status"))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{ \"status\": \"CLOSED\" }"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.status").value("CLOSED"));
    }

    @Test
    @DisplayName("shouldReturn400_whenFilledIsRequested")
    void shouldReturn400OnFilled() throws Exception {
      // FILLED is not a value a caller may set — the service throws the custom
      // com.socialapp.common.exception.ValidationException, which GlobalExceptionHandler maps to
      // 400 (the jakarta.validation.ValidationException that becomes 422 is a different type).
      doThrow(
              new com.socialapp.common.exception.ValidationException(
                  "FILLED is set by accepting applications, not directly"))
          .when(projectService)
          .updatePositionStatus(OWNER_ID, 30, PositionStatus.FILLED);

      mockMvc
          .perform(
              authed(patch(URL + "/positions/30/status"))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{ \"status\": \"FILLED\" }"))
          .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("shouldReturn409_whenReopeningAPositionAtCapacity")
    void shouldReturn409() throws Exception {
      doThrow(
              new ConflictException(
                  "Position is at capacity; raise its quantity before reopening it"))
          .when(projectService)
          .updatePositionStatus(OWNER_ID, 30, PositionStatus.OPEN);

      mockMvc
          .perform(
              authed(patch(URL + "/positions/30/status"))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{ \"status\": \"OPEN\" }"))
          .andExpect(status().isConflict());
    }
  }

  @Nested
  @DisplayName("DELETE /v1/api/projects/positions/{positionId}")
  class DeletePositionTests {

    @Test
    @DisplayName("shouldReturn204_happyPath")
    void shouldDelete() throws Exception {
      mockMvc.perform(authed(delete(URL + "/positions/30"))).andExpect(status().isNoContent());

      verify(projectService).deletePosition(OWNER_ID, 30);
    }

    @Test
    @DisplayName("shouldReturn409_whileAMemberIsAcceptedIntoThePosition")
    void shouldReturn409() throws Exception {
      doThrow(
              new ConflictException(
                  "Remove the accepted member(s) from this position before deleting it"))
          .when(projectService)
          .deletePosition(OWNER_ID, 30);

      mockMvc.perform(authed(delete(URL + "/positions/30"))).andExpect(status().isConflict());
    }

    @Test
    @DisplayName("shouldReturn403_whenTheCallerIsNotTheOwner")
    void shouldReturn403() throws Exception {
      doThrow(new ForbiddenException("Not authorized to manage this position"))
          .when(projectService)
          .deletePosition(OWNER_ID, 30);

      mockMvc.perform(authed(delete(URL + "/positions/30"))).andExpect(status().isForbidden());
    }
  }

  @Nested
  @DisplayName("DELETE /v1/api/projects/applications/{applicationId}")
  class WithdrawApplicationTests {

    @Test
    @DisplayName("shouldReturn204AndWithdraw_happyPath")
    void shouldWithdraw() throws Exception {
      mockMvc.perform(authed(delete(URL + "/applications/70"))).andExpect(status().isNoContent());

      verify(projectService).withdrawApplication(OWNER_ID, 70);
    }

    @Test
    @DisplayName("shouldReturn403_whenTheCallerIsNotTheApplicant")
    void shouldReturn403() throws Exception {
      doThrow(new ForbiddenException("Not authorized to withdraw this application"))
          .when(projectService)
          .withdrawApplication(OWNER_ID, 70);

      mockMvc.perform(authed(delete(URL + "/applications/70"))).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("shouldReturn409_onceTheOwnerHasDecidedIt")
    void shouldReturn409() throws Exception {
      doThrow(new ConflictException("Cannot withdraw an application that is already ACCEPTED"))
          .when(projectService)
          .withdrawApplication(OWNER_ID, 70);

      mockMvc.perform(authed(delete(URL + "/applications/70"))).andExpect(status().isConflict());
    }

    @Test
    @DisplayName("shouldReturn401_whenCalledWithNoAuthorizationHeader")
    void shouldReturn401ForGuest() throws Exception {
      mockMvc.perform(delete(URL + "/applications/70")).andExpect(status().isUnauthorized());

      verify(projectService, never()).withdrawApplication(any(), any());
    }
  }

  // =====================================================================
  // GET /v1/api/projects/positions/{positionId}/job-description
  // =====================================================================

  @Nested
  @DisplayName("GET /v1/api/projects/positions/{positionId}/job-description")
  class JobDescriptionTests {

    private static final Integer POSITION_ID = 30;

    @Test
    @DisplayName("shouldReturn200AndASignedUrl_happyPath")
    void shouldReturnSignedUrl() throws Exception {
      OffsetDateTime renderedAt = OffsetDateTime.parse("2026-09-01T10:15:30Z");
      when(jobDescriptionService.getOrRender(POSITION_ID))
          .thenReturn(
              new JobDescriptionUrlResponse("https://minio/job-descriptions/x.pdf", renderedAt));

      mockMvc
          .perform(authed(get(URL + "/positions/" + POSITION_ID + "/job-description")))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.url").value("https://minio/job-descriptions/x.pdf"))
          .andExpect(jsonPath("$.renderedAt").exists());
    }

    @Test
    @DisplayName("shouldReturn404_whenThePositionDoesNotExist")
    void shouldReturn404() throws Exception {
      when(jobDescriptionService.getOrRender(999))
          .thenThrow(new NotFoundException("Position not found with ID: 999"));

      mockMvc
          .perform(authed(get(URL + "/positions/999/job-description")))
          .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("shouldReturn200ForAnySignedInCaller_notOnlyTheOwner")
    void shouldBeReadableByAnyone() throws Exception {
      // A job posting only its author can open is not a posting. Unlike suggested-candidates, this
      // endpoint does not take the caller at all.
      when(jobDescriptionService.getOrRender(POSITION_ID))
          .thenReturn(new JobDescriptionUrlResponse("https://minio/jd.pdf", OffsetDateTime.now()));

      mockMvc
          .perform(authed(get(URL + "/positions/" + POSITION_ID + "/job-description")))
          .andExpect(status().isOk());
    }

    @Test
    @DisplayName("shouldReturn401_whenCalledWithNoAuthorizationHeader")
    void shouldReturn401ForGuest() throws Exception {
      mockMvc
          .perform(get(URL + "/positions/" + POSITION_ID + "/job-description"))
          .andExpect(status().isUnauthorized());

      verify(jobDescriptionService, never()).getOrRender(any());
    }
  }
}
