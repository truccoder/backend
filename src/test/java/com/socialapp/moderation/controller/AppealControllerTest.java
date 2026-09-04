package com.socialapp.moderation.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

import com.socialapp.common.exception.ForbiddenException;
import com.socialapp.common.exception.ValidationException;
import com.socialapp.moderation.dto.AppealDto;
import com.socialapp.moderation.dto.UserViolationDto;
import com.socialapp.moderation.enums.AppealStatus;
import com.socialapp.moderation.enums.ViolationSeverity;
import com.socialapp.moderation.enums.ViolationType;
import com.socialapp.moderation.service.AppealService;
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
 * System/API integration tests for {@link AppealController}, per ISTQB CTFL v4.0.1 Section 2.2.2,
 * using {@code @WebMvcTest} + {@code MockMvc}. {@link AppealService} is mocked.
 *
 * <p><b>The banned-caller cases are the point of this class.</b> Every endpoint here has to stay
 * reachable while the caller is banned — {@link JwtAuthenticationFilter} refuses a banned account
 * on every other path, so without the exemption an appeal endpoint would exist and be unusable by
 * exactly the people it is for. That exemption is enforced by string comparison on the request URI,
 * which nothing else would catch if a path were renamed, so it is asserted here against the real
 * filter rather than a mock.
 */
@WebMvcTest(AppealController.class)
@Import({
  SecurityConfig.class,
  CustomAuthenticationEntryPoint.class,
  CustomAccessDeniedHandler.class,
  JwtAuthenticationFilter.class
})
class AppealControllerTest {

  private static final String URL = "/v1/api/moderation";
  private static final String TOKEN = "a-valid-jwt-token";
  private static final String BANNED_TOKEN = "a-valid-jwt-token-of-a-banned-user";
  private static final Integer USER_ID = 9001;

  @Autowired private MockMvc mockMvc;

  @MockBean private AppealService appealService;
  @MockBean private JwtProvider jwtProvider;
  @MockBean private BanDetailsService banDetailsService;
  @MockBean private UserRepository userRepository;

  @BeforeEach
  void setUpCallers() {
    UserEntity user = sampleUser("user@example.com", null);
    UserEntity banned = sampleUser("banned@example.com", OffsetDateTime.now().plusDays(7));

    when(jwtProvider.isTokenValid(TOKEN)).thenReturn(true);
    when(jwtProvider.extractEmail(TOKEN)).thenReturn(user.getEmail());
    when(userRepository.findByEmailIgnoreCase(user.getEmail())).thenReturn(Optional.of(user));

    when(jwtProvider.isTokenValid(BANNED_TOKEN)).thenReturn(true);
    when(jwtProvider.extractEmail(BANNED_TOKEN)).thenReturn(banned.getEmail());
    when(userRepository.findByEmailIgnoreCase(banned.getEmail())).thenReturn(Optional.of(banned));
  }

  private static UserEntity sampleUser(String email, OffsetDateTime bannedUntil) {
    UserEntity u = new UserEntity();
    u.setId(USER_ID);
    u.setEmail(email);
    u.setUsername("u9001");
    u.setFullName("User 9001");
    u.setRole(UserRole.USER);
    u.setEmailVerified(true);
    u.setBannedUntil(bannedUntil);
    return u;
  }

  private static MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder b) {
    return b.header("Authorization", "Bearer " + TOKEN);
  }

  private static MockHttpServletRequestBuilder asBanned(MockHttpServletRequestBuilder b) {
    return b.header("Authorization", "Bearer " + BANNED_TOKEN);
  }

  private static AppealDto appeal(AppealStatus status) {
    return AppealDto.builder()
        .id(1L)
        .userId(USER_ID)
        .violationId(3L)
        .violationType(ViolationType.SPAM)
        .postId(11)
        .postExcerpt("Check out my new project, link in bio!")
        .reason("It was a link to my own project")
        .status(status)
        .build();
  }

  // =====================================================================
  // GET /v1/api/moderation/my-violations
  // =====================================================================

  @Nested
  @DisplayName("GET /v1/api/moderation/my-violations")
  class MyViolationsTests {

    @Test
    @DisplayName("shouldReturn200AndTheCallersOwnViolations_happyPath")
    void shouldReturnOwnViolations() throws Exception {
      when(appealService.getMyViolations(USER_ID))
          .thenReturn(
              List.of(
                  UserViolationDto.builder()
                      .id(3L)
                      .violationType(ViolationType.SPAM)
                      .severity(ViolationSeverity.LOW)
                      .appealPending(false)
                      .build()));

      mockMvc
          .perform(authed(get(URL + "/my-violations")))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$[0].violationType").value("SPAM"))
          .andExpect(jsonPath("$[0].appealPending").value(false));
    }

    @Test
    @DisplayName("shouldStayReachable_whenTheCallerIsBanned")
    void shouldBeReachableWhileBanned() throws Exception {
      // A person cannot appeal what they cannot see. The ban filter runs before routing, so this
      // path is exempted by URI — see JwtAuthenticationFilter#isBannedUserAllowed.
      when(appealService.getMyViolations(USER_ID)).thenReturn(List.of());

      mockMvc.perform(asBanned(get(URL + "/my-violations"))).andExpect(status().isOk());
    }

    @Test
    @DisplayName("shouldReturn401_whenCalledWithNoAuthorizationHeader")
    void shouldReturn401ForGuest() throws Exception {
      mockMvc.perform(get(URL + "/my-violations")).andExpect(status().isUnauthorized());
    }
  }

  // =====================================================================
  // POST /v1/api/moderation/appeals
  // =====================================================================

  @Nested
  @DisplayName("POST /v1/api/moderation/appeals")
  class SubmitAppealTests {

    private static final String BODY =
        """
        { "violationId": 3, "reason": "It was a link to my own project" }
        """;

    @Test
    @DisplayName("shouldReturn201AndTheAppeal_happyPath")
    void shouldReturn201() throws Exception {
      when(appealService.submitAppeal(eq(USER_ID), any())).thenReturn(appeal(AppealStatus.PENDING));

      mockMvc
          .perform(
              authed(post(URL + "/appeals")).contentType(MediaType.APPLICATION_JSON).content(BODY))
          .andExpect(status().isCreated())
          .andExpect(jsonPath("$.status").value("PENDING"))
          .andExpect(jsonPath("$.violationType").value("SPAM"))
          .andExpect(jsonPath("$.postId").value(11))
          .andExpect(jsonPath("$.postExcerpt").value("Check out my new project, link in bio!"));
    }

    @Test
    @DisplayName("shouldStayReachable_whenTheCallerIsBanned")
    void shouldBeReachableWhileBanned() throws Exception {
      // The whole feature exists for banned users; an appeal endpoint they cannot call is
      // decoration.
      when(appealService.submitAppeal(eq(USER_ID), any())).thenReturn(appeal(AppealStatus.PENDING));

      mockMvc
          .perform(
              asBanned(post(URL + "/appeals"))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(BODY))
          .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("shouldReturn400_whenAnAppealForThatViolationIsAlreadyOpen")
    void shouldReturn400OnDuplicate() throws Exception {
      doThrow(new ValidationException("An appeal for this violation is already under review"))
          .when(appealService)
          .submitAppeal(eq(USER_ID), any());

      mockMvc
          .perform(
              authed(post(URL + "/appeals")).contentType(MediaType.APPLICATION_JSON).content(BODY))
          .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("shouldReturn403_whenAppealingSomebodyElsesViolation")
    void shouldReturn403ForOthersViolation() throws Exception {
      doThrow(new ForbiddenException("You can only appeal a violation recorded against you"))
          .when(appealService)
          .submitAppeal(eq(USER_ID), any());

      mockMvc
          .perform(
              authed(post(URL + "/appeals")).contentType(MediaType.APPLICATION_JSON).content(BODY))
          .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("shouldReturn422_whenReasonIsBlank")
    void shouldReturn422OnBlankReason() throws Exception {
      mockMvc
          .perform(
              authed(post(URL + "/appeals"))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{ \"violationId\": 3, \"reason\": \"\" }"))
          .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("shouldReturn422_whenViolationIdIsMissing")
    void shouldReturn422OnMissingViolationId() throws Exception {
      mockMvc
          .perform(
              authed(post(URL + "/appeals"))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{ \"reason\": \"no id\" }"))
          .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("shouldReturn401_whenCalledWithNoAuthorizationHeader")
    void shouldReturn401ForGuest() throws Exception {
      mockMvc
          .perform(post(URL + "/appeals").contentType(MediaType.APPLICATION_JSON).content(BODY))
          .andExpect(status().isUnauthorized());
    }
  }

  // =====================================================================
  // GET /v1/api/moderation/appeals
  // =====================================================================

  @Nested
  @DisplayName("GET /v1/api/moderation/appeals")
  class MyAppealsTests {

    @Test
    @DisplayName("shouldReturn200AndTheCallersOwnAppeals_happyPath")
    void shouldReturnOwnAppeals() throws Exception {
      when(appealService.getMyAppeals(USER_ID)).thenReturn(List.of(appeal(AppealStatus.APPROVED)));

      mockMvc
          .perform(authed(get(URL + "/appeals")))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$[0].status").value("APPROVED"));

      verify(appealService).getMyAppeals(USER_ID);
    }

    @Test
    @DisplayName("shouldStayReachable_whenTheCallerIsBanned")
    void shouldBeReachableWhileBanned() throws Exception {
      // Being told "you may appeal" and then not being allowed to see the answer is the same dead
      // end one step later.
      when(appealService.getMyAppeals(USER_ID)).thenReturn(List.of());

      mockMvc.perform(asBanned(get(URL + "/appeals"))).andExpect(status().isOk());
    }

    @Test
    @DisplayName("shouldReturn401_whenCalledWithNoAuthorizationHeader")
    void shouldReturn401ForGuest() throws Exception {
      mockMvc.perform(get(URL + "/appeals")).andExpect(status().isUnauthorized());
    }
  }
}
