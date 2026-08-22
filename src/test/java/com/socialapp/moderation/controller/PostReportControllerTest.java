package com.socialapp.moderation.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

import com.socialapp.common.exception.NotFoundException;
import com.socialapp.common.exception.ValidationException;
import com.socialapp.moderation.dto.CreatePostReportRequestDto;
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
 * System/API integration tests for {@link PostReportController}, per ISTQB CTFL v4.0.1 Section
 * 2.2.2, using {@code @WebMvcTest} + {@code MockMvc}. {@link PostReportService} is mocked.
 *
 * <p><b>The guest case is asserted, not assumed.</b> This endpoint has to stay behind
 * authentication even though guests can read the posts most likely to need reporting: the
 * escalation threshold counts people, and an anonymous reporter is an IP address, three of which
 * cost nothing. A later widening of {@code SecurityConfig}'s guest surface would break this test
 * rather than quietly turning three proxies into a takedown.
 */
@WebMvcTest(PostReportController.class)
@Import({
  SecurityConfig.class,
  CustomAuthenticationEntryPoint.class,
  CustomAccessDeniedHandler.class,
  JwtAuthenticationFilter.class
})
class PostReportControllerTest {

  private static final String URL = "/v1/api/moderation/reports";
  private static final String TOKEN = "a-valid-jwt-token";
  private static final Integer USER_ID = 9002;

  @Autowired private MockMvc mockMvc;

  @MockBean private PostReportService postReportService;
  @MockBean private JwtProvider jwtProvider;
  @MockBean private BanDetailsService banDetailsService;
  @MockBean private UserRepository userRepository;

  @BeforeEach
  void setUpCaller() {
    UserEntity user = new UserEntity();
    user.setId(USER_ID);
    user.setEmail("reporter@example.com");
    user.setUsername("reporter");
    user.setFullName("Reporter");
    user.setRole(UserRole.USER);
    user.setEmailVerified(true);

    when(jwtProvider.isTokenValid(TOKEN)).thenReturn(true);
    when(jwtProvider.extractEmail(TOKEN)).thenReturn(user.getEmail());
    when(userRepository.findByEmailIgnoreCase(user.getEmail())).thenReturn(Optional.of(user));
  }

  private static MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder) {
    return builder.header("Authorization", "Bearer " + TOKEN);
  }

  private static String body(String reason) {
    return "{\"postId\":5001,\"reason\":\"" + reason + "\",\"details\":\"spam\"}";
  }

  @Nested
  @DisplayName("POST /v1/api/moderation/reports")
  class ReportTests {

    @Test
    @DisplayName("shouldReturn200AndReceipt_happyPath")
    void shouldReturn200AndReceipt() throws Exception {
      // When / Then — the receipt says the report is on file and nothing else: how close the
      // post is to escalation is the mechanism, and a client that can read it can probe for it
      mockMvc
          .perform(authed(post(URL)).contentType(MediaType.APPLICATION_JSON).content(body("SPAM")))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.reported").value(true))
          .andExpect(jsonPath("$.reportCount").doesNotExist());
    }

    @Test
    @DisplayName("shouldTakeTheReporterFromTheSecurityContext_notTheRequestBody")
    void shouldTakeReporterFromSecurityContext() throws Exception {
      // When
      mockMvc
          .perform(authed(post(URL)).contentType(MediaType.APPLICATION_JSON).content(body("SPAM")))
          .andExpect(status().isOk());

      // Then — a reporterId accepted from the body would let anyone file reports as somebody else
      verify(postReportService).report(eq(USER_ID), any(CreatePostReportRequestDto.class));
    }

    @Test
    @DisplayName("shouldReturn401_whenCalledByAGuestWithNoAuthorizationHeader")
    void shouldRefuseGuests() throws Exception {
      // When / Then
      mockMvc
          .perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body("SPAM")))
          .andExpect(status().isUnauthorized());

      verify(postReportService, never()).report(any(), any());
    }

    @Test
    @DisplayName("shouldReturn422_whenPostIdIsMissing")
    void shouldReturn422_whenPostIdMissing() throws Exception {
      // When / Then — EP: postId is @NotNull
      mockMvc
          .perform(
              authed(post(URL))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"reason\":\"SPAM\"}"))
          .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("shouldReturn422_whenReasonIsMissing")
    void shouldReturn422_whenReasonMissing() throws Exception {
      // When / Then — EP: reason is @NotNull
      mockMvc
          .perform(
              authed(post(URL))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"postId\":5001}"))
          .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("shouldReturn400_whenReasonIsNotAValidEnumValue")
    void shouldReturn400_whenReasonInvalid() throws Exception {
      // When / Then — EP: reason must be one of ReportReason's constants. ViolationType's
      // constants deliberately are NOT among them; a reporter does not get to pick the verdict.
      mockMvc
          .perform(
              authed(post(URL))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body("KEYWORD_BLACKLIST")))
          .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("shouldReturn422_whenDetailsExceedTheLimit_boundary")
    void shouldReturn422_whenDetailsTooLong() throws Exception {
      // When / Then — BVA: @Size(max = 1000), so 1001 characters must fail
      String details = "x".repeat(1001);
      mockMvc
          .perform(
              authed(post(URL))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"postId\":5001,\"reason\":\"SPAM\",\"details\":\"" + details + "\"}"))
          .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("shouldReturn404_whenThePostDoesNotExist")
    void shouldReturn404_whenPostMissing() throws Exception {
      // Given
      doThrow(new NotFoundException("Post not found: 5001"))
          .when(postReportService)
          .report(eq(USER_ID), any());

      // When / Then
      mockMvc
          .perform(authed(post(URL)).contentType(MediaType.APPLICATION_JSON).content(body("SPAM")))
          .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("shouldReturn400_whenReportingYourOwnPost")
    void shouldReturn400_whenSelfReporting() throws Exception {
      // Given
      doThrow(new ValidationException("You cannot report your own post"))
          .when(postReportService)
          .report(eq(USER_ID), any());

      // When / Then
      mockMvc
          .perform(authed(post(URL)).contentType(MediaType.APPLICATION_JSON).content(body("SPAM")))
          .andExpect(status().isBadRequest());
    }
  }
}
