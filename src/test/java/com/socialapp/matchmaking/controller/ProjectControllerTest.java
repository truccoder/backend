package com.socialapp.matchmaking.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
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

import com.socialapp.common.exception.ConflictException;
import com.socialapp.common.exception.ForbiddenException;
import com.socialapp.common.exception.NotFoundException;
import com.socialapp.matchmaking.dto.ProjectApplicationResponseDto;
import com.socialapp.matchmaking.dto.ProjectPageResponseDto;
import com.socialapp.matchmaking.dto.ProjectResponseDto;
import com.socialapp.matchmaking.dto.SuggestedCandidateDto;
import com.socialapp.matchmaking.dto.SuggestedProjectDto;
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

  /** {@code Constants.DEFAULT_PAGINATION_PAGE_SIZE} — what the controller binds when the
   * caller omits {@code limit}. Asserted rather than matched loosely, because "the default
   * actually reaches the service" is part of the contract. */
  private static final int DEFAULT_LIMIT = 10;

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
          List.of("API Design"));
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
}
