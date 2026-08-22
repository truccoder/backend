package com.socialapp.moderation.controller;

import static org.mockito.ArgumentMatchers.any;
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
import org.springframework.data.domain.PageImpl;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.socialapp.common.exception.ValidationException;
import com.socialapp.moderation.dto.AppealDto;
import com.socialapp.moderation.dto.BannedUserDto;
import com.socialapp.moderation.dto.ModerationLogDto;
import com.socialapp.moderation.dto.PostModerationDetailDto;
import com.socialapp.moderation.dto.PostReportDto;
import com.socialapp.moderation.enums.AppealStatus;
import com.socialapp.moderation.enums.ModerationStatus;
import com.socialapp.moderation.enums.ReportReason;
import com.socialapp.moderation.enums.ViolationType;
import com.socialapp.moderation.service.AdminModerationService;
import com.socialapp.moderation.service.AppealService;
import com.socialapp.moderation.service.BanDetailsService;
import com.socialapp.moderation.service.PostReportService;
import com.socialapp.security.config.CustomAccessDeniedHandler;
import com.socialapp.security.config.CustomAuthenticationEntryPoint;
import com.socialapp.security.config.JwtAuthenticationFilter;
import com.socialapp.security.config.JwtProvider;
import com.socialapp.security.config.SecurityConfig;
import com.socialapp.security.entity.UserEntity;
import com.socialapp.security.entity.UserRole;
import com.socialapp.security.repository.UserRepository;

/**
 * System/API integration tests for {@link AdminModerationController}, per ISTQB CTFL v4.0.1
 * Section 2.2.2, using {@code @WebMvcTest} + {@code MockMvc}. {@link AdminModerationService} is
 * mocked.
 *
 * <p><b>First controller in this suite with a real role check.</b> {@link SecurityConfig} maps
 * {@code /v1/api/admin/**} to {@code .hasRole("ADMIN")}, so — unlike every other controller
 * tested so far, where the only security boundary was "authenticated or not" — there is a
 * genuine 403 case here for a caller who <i>is</i> authenticated but holds {@code ROLE_USER}
 * instead of {@code ROLE_ADMIN}. {@link JwtAuthenticationFilter} derives the granted authority
 * directly from {@code UserEntity.getRole()}, so the test's "wrong role" caller is a real
 * non-admin {@link UserEntity}, not a mocked authority.
 */
@WebMvcTest(AdminModerationController.class)
@Import({
  SecurityConfig.class,
  CustomAuthenticationEntryPoint.class,
  CustomAccessDeniedHandler.class,
  JwtAuthenticationFilter.class
})
class AdminModerationControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockBean private AdminModerationService adminModerationService;
  @MockBean private AppealService appealService;
  @MockBean private PostReportService postReportService;
  @MockBean private JwtProvider jwtProvider;

  @MockBean
  private BanDetailsService
      banDetailsService; // JwtAuthenticationFilter builds the banned-account 403 through it

  @MockBean private UserRepository userRepository;

  private static final String ADMIN_URL = "/v1/api/admin/moderation";
  private static final String ADMIN_TOKEN = "a-valid-admin-jwt-token";
  private static final String USER_TOKEN = "a-valid-user-jwt-token";

  private UserEntity adminUser;
  private UserEntity regularUser;

  @BeforeEach
  void setUpUsers() {
    adminUser = sampleUser(1, "admin@example.com", UserRole.ADMIN);
    regularUser = sampleUser(2, "user@example.com", UserRole.USER);

    when(jwtProvider.isTokenValid(ADMIN_TOKEN)).thenReturn(true);
    when(jwtProvider.extractEmail(ADMIN_TOKEN)).thenReturn(adminUser.getEmail());
    when(userRepository.findByEmailIgnoreCase(adminUser.getEmail()))
        .thenReturn(Optional.of(adminUser));

    when(jwtProvider.isTokenValid(USER_TOKEN)).thenReturn(true);
    when(jwtProvider.extractEmail(USER_TOKEN)).thenReturn(regularUser.getEmail());
    when(userRepository.findByEmailIgnoreCase(regularUser.getEmail()))
        .thenReturn(Optional.of(regularUser));
  }

  private static UserEntity sampleUser(Integer id, String email, UserRole role) {
    UserEntity user = new UserEntity();
    user.setId(id);
    user.setEmail(email);
    user.setUsername("user" + id);
    user.setFullName("User " + id);
    user.setRole(role);
    user.setEmailVerified(true);
    return user;
  }

  private static MockHttpServletRequestBuilder asAdmin(MockHttpServletRequestBuilder builder) {
    return builder.header("Authorization", "Bearer " + ADMIN_TOKEN);
  }

  private static MockHttpServletRequestBuilder asRegularUser(
      MockHttpServletRequestBuilder builder) {
    return builder.header("Authorization", "Bearer " + USER_TOKEN);
  }

  // =====================================================================
  // GET /v1/api/admin/moderation/posts
  // =====================================================================

  @Nested
  @DisplayName("GET /v1/api/admin/moderation/posts")
  class SearchPostsTests {

    @Test
    @DisplayName("shouldReturn200AndPage_whenCalledByAdmin_happyPath")
    void shouldReturn200AndPage_whenCalledByAdmin_happyPath() throws Exception {
      // Given
      PostModerationDetailDto detail =
          PostModerationDetailDto.builder()
              .postId(1)
              .authorId(2)
              .currentStatus(ModerationStatus.PENDING_REVIEW)
              .build();
      when(adminModerationService.searchPosts(any(), any(), any(), any()))
          .thenReturn(new PageImpl<>(List.of(detail)));

      // When / Then
      mockMvc
          .perform(asAdmin(get(ADMIN_URL + "/posts")))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.content[0].postId").value(1))
          .andExpect(jsonPath("$.content[0].currentStatus").value("PENDING_REVIEW"));
    }

    @Test
    @DisplayName("shouldReturn400_whenPageIsZero_boundary")
    void shouldReturn400_whenPageIsZero_boundary() throws Exception {
      // When / Then — BVA: @Positive requires > 0
      mockMvc
          .perform(asAdmin(get(ADMIN_URL + "/posts")).param("page", "0"))
          .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("shouldReturn401_whenCalledWithNoAuthorizationHeader")
    void shouldReturn401_whenCalledWithNoAuthorizationHeader() throws Exception {
      // When / Then
      mockMvc.perform(get(ADMIN_URL + "/posts")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("shouldReturn403_whenCallerIsNotAnAdmin")
    void shouldReturn403_whenCallerIsNotAnAdmin() throws Exception {
      // When / Then — real 403: authenticated as ROLE_USER, endpoint requires ROLE_ADMIN
      mockMvc.perform(asRegularUser(get(ADMIN_URL + "/posts"))).andExpect(status().isForbidden());
    }
  }

  // =====================================================================
  // GET /v1/api/admin/moderation/logs
  // =====================================================================

  @Nested
  @DisplayName("GET /v1/api/admin/moderation/logs")
  class SearchLogsTests {

    @Test
    @DisplayName("shouldReturn200AndPage_whenCalledByAdmin_happyPath")
    void shouldReturn200AndPage_whenCalledByAdmin_happyPath() throws Exception {
      // Given
      ModerationLogDto log =
          ModerationLogDto.builder().id(1L).postId(1).status(ModerationStatus.APPROVED).build();
      when(adminModerationService.searchLogs(any(), any(), any(), any()))
          .thenReturn(new PageImpl<>(List.of(log)));

      // When / Then
      mockMvc
          .perform(asAdmin(get(ADMIN_URL + "/logs")))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.content[0].id").value(1))
          .andExpect(jsonPath("$.content[0].status").value("APPROVED"));
    }

    @Test
    @DisplayName("shouldReturn401_whenCalledWithNoAuthorizationHeader")
    void shouldReturn401_whenCalledWithNoAuthorizationHeader() throws Exception {
      // When / Then
      mockMvc.perform(get(ADMIN_URL + "/logs")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("shouldReturn403_whenCallerIsNotAnAdmin")
    void shouldReturn403_whenCallerIsNotAnAdmin() throws Exception {
      // When / Then
      mockMvc.perform(asRegularUser(get(ADMIN_URL + "/logs"))).andExpect(status().isForbidden());
    }
  }

  // =====================================================================
  // GET /v1/api/admin/moderation/banned-users
  // =====================================================================

  @Nested
  @DisplayName("GET /v1/api/admin/moderation/banned-users")
  class GetBannedUsersTests {

    @Test
    @DisplayName("shouldReturn200AndPage_whenCalledByAdmin_happyPath")
    void shouldReturn200AndPage_whenCalledByAdmin_happyPath() throws Exception {
      // Given
      BannedUserDto banned =
          BannedUserDto.builder()
              .userId(5)
              .email("banned@example.com")
              .currentlyBanned(true)
              .build();
      when(adminModerationService.getBannedUsers(any()))
          .thenReturn(new PageImpl<>(List.of(banned)));

      // When / Then
      mockMvc
          .perform(asAdmin(get(ADMIN_URL + "/banned-users")))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.content[0].userId").value(5))
          .andExpect(jsonPath("$.content[0].currentlyBanned").value(true));
    }

    @Test
    @DisplayName("shouldReturn401_whenCalledWithNoAuthorizationHeader")
    void shouldReturn401_whenCalledWithNoAuthorizationHeader() throws Exception {
      // When / Then
      mockMvc.perform(get(ADMIN_URL + "/banned-users")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("shouldReturn403_whenCallerIsNotAnAdmin")
    void shouldReturn403_whenCallerIsNotAnAdmin() throws Exception {
      // When / Then
      mockMvc
          .perform(asRegularUser(get(ADMIN_URL + "/banned-users")))
          .andExpect(status().isForbidden());
    }
  }

  // =====================================================================
  // POST /v1/api/admin/moderation/posts/{postId}/review
  // =====================================================================

  @Nested
  @DisplayName("POST /v1/api/admin/moderation/posts/{postId}/review")
  class ReviewPostTests {

    @Test
    @DisplayName("shouldReturn200_whenDecisionIsValid_happyPath")
    void shouldReturn200_whenDecisionIsValid_happyPath() throws Exception {
      // Given
      String requestJson =
          """
          { "decision": "VERY_UNLIKELY", "feedback": "Looks fine" }
          """;

      // When / Then
      mockMvc
          .perform(
              asAdmin(post(ADMIN_URL + "/posts/1/review"))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(requestJson))
          .andExpect(status().isOk());
    }

    @Test
    @DisplayName("shouldReturn422_whenDecisionIsMissing")
    void shouldReturn422_whenDecisionIsMissing() throws Exception {
      // Given
      String requestJson =
          """
          { "feedback": "No decision given" }
          """;

      // When / Then
      mockMvc
          .perform(
              asAdmin(post(ADMIN_URL + "/posts/1/review"))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(requestJson))
          .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("shouldReturn409_whenPostIsNotInPendingReviewStatus")
    void shouldReturn409_whenPostIsNotInPendingReviewStatus() throws Exception {
      // Given
      org.mockito.Mockito.doThrow(new IllegalStateException("Post is not in PENDING_REVIEW status"))
          .when(adminModerationService)
          .reviewPost(org.mockito.ArgumentMatchers.anyInt(), any(), any(), any());
      String requestJson =
          """
          { "decision": "LIKELY" }
          """;

      // When / Then
      mockMvc
          .perform(
              asAdmin(post(ADMIN_URL + "/posts/1/review"))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(requestJson))
          .andExpect(status().isConflict())
          .andExpect(jsonPath("$.message").value("Post is not in PENDING_REVIEW status"));
    }

    @Test
    @DisplayName("shouldReturn400_whenPostIdPathVariableIsNotANumber")
    void shouldReturn400_whenPostIdPathVariableIsNotANumber() throws Exception {
      // Given
      String requestJson =
          """
          { "decision": "LIKELY" }
          """;

      // When / Then — EP: postId must be an Integer
      mockMvc
          .perform(
              asAdmin(post(ADMIN_URL + "/posts/not-a-number/review"))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(requestJson))
          .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("shouldReturn401_whenCalledWithNoAuthorizationHeader")
    void shouldReturn401_whenCalledWithNoAuthorizationHeader() throws Exception {
      // Given
      String requestJson =
          """
          { "decision": "LIKELY" }
          """;

      // When / Then
      mockMvc
          .perform(
              post(ADMIN_URL + "/posts/1/review")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(requestJson))
          .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("shouldReturn403_whenCallerIsNotAnAdmin")
    void shouldReturn403_whenCallerIsNotAnAdmin() throws Exception {
      // Given
      String requestJson =
          """
          { "decision": "LIKELY" }
          """;

      // When / Then
      mockMvc
          .perform(
              asRegularUser(post(ADMIN_URL + "/posts/1/review"))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(requestJson))
          .andExpect(status().isForbidden());
    }
  }

  // =====================================================================
  // User reports — admin side
  // =====================================================================

  @Nested
  @DisplayName("GET /v1/api/admin/moderation/reports")
  class ReportQueueTests {

    @Test
    @DisplayName("shouldReturn200AndTheReports_whenCalledByAdmin_happyPath")
    void shouldReturnReports() throws Exception {
      when(postReportService.getReports(any(), any()))
          .thenReturn(
              new PageImpl<>(
                  List.of(
                      PostReportDto.builder()
                          .id(1)
                          .postId(5001)
                          .reporterId(9002)
                          .reason(ReportReason.SPAM)
                          .details("buy now")
                          .build())));

      mockMvc
          .perform(asAdmin(get(ADMIN_URL + "/reports")))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.content[0].postId").value(5001))
          .andExpect(jsonPath("$.content[0].reason").value("SPAM"));
    }

    @Test
    @DisplayName("shouldNarrowToOnePost_whenPostIdIsGiven")
    void shouldNarrowToOnePost() throws Exception {
      when(postReportService.getReports(any(), any())).thenReturn(new PageImpl<>(List.of()));

      mockMvc
          .perform(asAdmin(get(ADMIN_URL + "/reports")).param("postId", "5001"))
          .andExpect(status().isOk());

      verify(postReportService).getReports(org.mockito.ArgumentMatchers.eq(5001), any());
    }

    @Test
    @DisplayName("shouldReturn403_whenCallerIsNotAnAdmin")
    void shouldReturn403ForNonAdmin() throws Exception {
      // The DTO carries the reporter's id — telling an author who reported them is how reporting
      // stops being something people are willing to do.
      mockMvc.perform(asRegularUser(get(ADMIN_URL + "/reports"))).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("shouldReturn401_whenCalledWithNoAuthorizationHeader")
    void shouldReturn401ForGuest() throws Exception {
      mockMvc.perform(get(ADMIN_URL + "/reports")).andExpect(status().isUnauthorized());
    }
  }

  // =====================================================================
  // Appeals (E3) — admin side
  // =====================================================================

  @Nested
  @DisplayName("GET /v1/api/admin/moderation/appeals")
  class AppealQueueTests {

    @Test
    @DisplayName("shouldReturn200AndTheQueue_whenCalledByAdmin_happyPath")
    void shouldReturnQueue() throws Exception {
      when(appealService.getAppeals(any(), any()))
          .thenReturn(
              new PageImpl<>(
                  List.of(
                      AppealDto.builder()
                          .id(1L)
                          .userId(9001)
                          .userFullName("User 9001")
                          .violationType(ViolationType.SPAM)
                          .status(AppealStatus.PENDING)
                          .build())));

      mockMvc
          .perform(asAdmin(get(ADMIN_URL + "/appeals")))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.content[0].userFullName").value("User 9001"))
          .andExpect(jsonPath("$.content[0].violationType").value("SPAM"));
    }

    @Test
    @DisplayName("shouldDefaultToPending_whenNoStatusIsGiven")
    void shouldDefaultToPending() throws Exception {
      when(appealService.getAppeals(any(), any())).thenReturn(new PageImpl<>(List.of()));

      mockMvc.perform(asAdmin(get(ADMIN_URL + "/appeals"))).andExpect(status().isOk());

      // PENDING is the only status with anything to do; the parameter exists so a decided appeal
      // can still be looked up.
      verify(appealService)
          .getAppeals(org.mockito.ArgumentMatchers.eq(AppealStatus.PENDING), any());
    }

    @Test
    @DisplayName("shouldReturn403_whenCallerIsNotAnAdmin")
    void shouldReturn403ForNonAdmin() throws Exception {
      mockMvc.perform(asRegularUser(get(ADMIN_URL + "/appeals"))).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("shouldReturn401_whenCalledWithNoAuthorizationHeader")
    void shouldReturn401ForGuest() throws Exception {
      mockMvc.perform(get(ADMIN_URL + "/appeals")).andExpect(status().isUnauthorized());
    }
  }

  @Nested
  @DisplayName("POST /v1/api/admin/moderation/appeals/{appealId}/approve|reject")
  class AppealDecisionTests {

    @Test
    @DisplayName("shouldReturn200_whenApprovingWithANote")
    void shouldApprove() throws Exception {
      when(appealService.approve(org.mockito.ArgumentMatchers.eq(1L), any(), any()))
          .thenReturn(AppealDto.builder().id(1L).status(AppealStatus.APPROVED).build());

      mockMvc
          .perform(
              asAdmin(post(ADMIN_URL + "/appeals/1/approve"))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{ \"reviewerNote\": \"You were right\" }"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.status").value("APPROVED"));
    }

    @Test
    @DisplayName("shouldAcceptNoBodyAtAll_becauseTheNoteIsOptional")
    void shouldAcceptMissingBody() throws Exception {
      when(appealService.reject(org.mockito.ArgumentMatchers.eq(1L), any(), any()))
          .thenReturn(AppealDto.builder().id(1L).status(AppealStatus.REJECTED).build());

      // An admin who just wants to reject should not have to POST "{}".
      mockMvc
          .perform(asAdmin(post(ADMIN_URL + "/appeals/1/reject")))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.status").value("REJECTED"));
    }

    @Test
    @DisplayName("shouldReturn400_whenTheAppealWasAlreadyDecided")
    void shouldReturn400OnAlreadyDecided() throws Exception {
      // Deciding twice would go looking for a violation the first approval deleted.
      when(appealService.approve(org.mockito.ArgumentMatchers.eq(1L), any(), any()))
          .thenThrow(new ValidationException("This appeal has already been approved"));

      mockMvc
          .perform(asAdmin(post(ADMIN_URL + "/appeals/1/approve")))
          .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("shouldReturn403_whenCallerIsNotAnAdmin")
    void shouldReturn403ForNonAdmin() throws Exception {
      // A banned admin is not exempted into this path either — the appeal exemption covers only
      // /v1/api/moderation/*, never /v1/api/admin/**.
      mockMvc
          .perform(asRegularUser(post(ADMIN_URL + "/appeals/1/approve")))
          .andExpect(status().isForbidden());
    }
  }
}
