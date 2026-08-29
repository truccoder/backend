package com.socialapp.github.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
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
import com.socialapp.common.exception.ExternalApiException;
import com.socialapp.common.exception.NotFoundException;
import com.socialapp.github.dto.GithubOAuthUrlResponse;
import com.socialapp.github.dto.GithubStatsResponse;
import com.socialapp.github.service.GithubService;
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
 * System/API integration tests for {@link GithubController}, per ISTQB CTFL v4.0.1 Section 2.2.2,
 * using {@code @WebMvcTest} + {@code MockMvc}. {@link GithubService} is mocked.
 *
 * <p>This controller had no test file at all — 8.3% line coverage, the lowest of all 32
 * controllers. Its five endpoints split into two authorization groups that are easy to conflate:
 * {@code GET /stats/*} is {@code permitAll} in {@code SecurityConfig} (a profile page shows a
 * stranger's GitHub card), while the other four resolve the caller through {@code
 * SecurityUtils.requireCurrentUser} and must reject a guest. Both halves are asserted here so a
 * future widening of the matcher fails a test.
 */
@WebMvcTest(GithubController.class)
@Import({
  SecurityConfig.class,
  CustomAuthenticationEntryPoint.class,
  CustomAccessDeniedHandler.class,
  JwtAuthenticationFilter.class
})
class GithubControllerTest {

  private static final String URL = "/v1/api/github";
  private static final String TOKEN = "a-valid-jwt-token";
  private static final Integer CALLER_ID = 9001;

  @Autowired private MockMvc mockMvc;

  @MockBean private GithubService githubService;
  @MockBean private JwtProvider jwtProvider;
  @MockBean private BanDetailsService banDetailsService;
  @MockBean private UserRepository userRepository;

  @BeforeEach
  void setUpCaller() {
    UserEntity caller = new UserEntity();
    caller.setId(CALLER_ID);
    caller.setEmail("caller@example.com");
    caller.setUsername("caller");
    caller.setRole(UserRole.USER);
    caller.setEmailVerified(true);

    when(jwtProvider.isTokenValid(TOKEN)).thenReturn(true);
    when(jwtProvider.extractEmail(TOKEN)).thenReturn(caller.getEmail());
    when(userRepository.findByEmailIgnoreCase(caller.getEmail())).thenReturn(Optional.of(caller));
  }

  private static MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder b) {
    return b.header("Authorization", "Bearer " + TOKEN);
  }

  // =====================================================================
  // GET /v1/api/github/oauth/url
  // =====================================================================

  @Nested
  @DisplayName("GET /v1/api/github/oauth/url")
  class OAuthUrlTests {

    @Test
    @DisplayName("shouldReturn200AndTheAuthorizeUrl_happyPath")
    void shouldReturnUrl() throws Exception {
      // Given
      when(githubService.getOAuthUrl())
          .thenReturn(
              GithubOAuthUrlResponse.builder()
                  .oauthUrl("https://github.com/login/oauth/authorize?client_id=abc")
                  .build());

      // When / Then
      mockMvc
          .perform(authed(get(URL + "/oauth/url")))
          .andExpect(status().isOk())
          .andExpect(
              jsonPath("$.oauthUrl")
                  .value("https://github.com/login/oauth/authorize?client_id=abc"));
    }

    @Test
    @DisplayName("shouldReturn401_whenCalledWithNoAuthorizationHeader")
    void shouldReturn401ForGuest() throws Exception {
      // Given: no matcher makes this endpoint public, so it falls to anyRequest().authenticated()

      // When / Then
      mockMvc.perform(get(URL + "/oauth/url")).andExpect(status().isUnauthorized());
      verify(githubService, never()).getOAuthUrl();
    }
  }

  // =====================================================================
  // POST /v1/api/github/oauth/callback
  // =====================================================================

  @Nested
  @DisplayName("POST /v1/api/github/oauth/callback")
  class OAuthCallbackTests {

    @Test
    @DisplayName("shouldReturn200AndLinkTheAccount_happyPath")
    void shouldLinkAccount() throws Exception {
      // Given / When
      mockMvc
          .perform(
              authed(post(URL + "/oauth/callback"))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"code\":\"gho_exchange_code\"}"))
          .andExpect(status().isOk());

      // Then
      verify(githubService).linkAccountWithCode(any(UserEntity.class), eq("gho_exchange_code"));
    }

    @Test
    @DisplayName("shouldReturn422_whenCodeIsBlank_becauseTheBodyIsValidated")
    void shouldReturn422OnBlankCode() throws Exception {
      // Given: GithubLinkRequest.code is @NotBlank, and a @Valid body failure is a
      // MethodArgumentNotValidException, which GlobalExceptionHandler maps to 422 (not 400).

      // When / Then
      mockMvc
          .perform(
              authed(post(URL + "/oauth/callback"))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"code\":\"   \"}"))
          .andExpect(status().isUnprocessableEntity());

      verify(githubService, never()).linkAccountWithCode(any(), any());
    }

    @Test
    @DisplayName("shouldReturn422_whenCodeIsMissingEntirely")
    void shouldReturn422OnMissingCode() throws Exception {
      // When / Then
      mockMvc
          .perform(
              authed(post(URL + "/oauth/callback"))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{}"))
          .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("shouldReturn400_whenTheBodyIsMalformedJson")
    void shouldReturn400OnMalformedJson() throws Exception {
      // When / Then
      mockMvc
          .perform(
              authed(post(URL + "/oauth/callback"))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"code\":"))
          .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("shouldReturn415_whenContentTypeIsMissing")
    void shouldReturn415WithoutContentType() throws Exception {
      // When / Then
      mockMvc
          .perform(authed(post(URL + "/oauth/callback")).content("{\"code\":\"x\"}"))
          .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    @DisplayName("shouldReturn503_whenGithubItselfRejectsTheCodeExchange")
    void shouldReturn503OnExternalFailure() throws Exception {
      // Given: an ExternalApiException is a downstream failure, not a caller fault -> 503
      doThrow(new ExternalApiException("Failed to get GitHub access token"))
          .when(githubService)
          .linkAccountWithCode(any(UserEntity.class), eq("expired-code"));

      // When / Then
      mockMvc
          .perform(
              authed(post(URL + "/oauth/callback"))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"code\":\"expired-code\"}"))
          .andExpect(status().isServiceUnavailable());
    }

    @Test
    @DisplayName("shouldReturn401_whenCalledWithNoAuthorizationHeader")
    void shouldReturn401ForGuest() throws Exception {
      // When / Then
      mockMvc
          .perform(
              post(URL + "/oauth/callback")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"code\":\"gho_exchange_code\"}"))
          .andExpect(status().isUnauthorized());

      verify(githubService, never()).linkAccountWithCode(any(), any());
    }
  }

  // =====================================================================
  // DELETE /v1/api/github/unlink
  // =====================================================================

  @Nested
  @DisplayName("DELETE /v1/api/github/unlink")
  class UnlinkTests {

    @Test
    @DisplayName("shouldReturn200AndUnlinkTheCallersOwnAccount_happyPath")
    void shouldUnlink() throws Exception {
      // When
      mockMvc.perform(authed(delete(URL + "/unlink"))).andExpect(status().isOk());

      // Then: the service takes the caller entity, never a client-supplied id — there is no way
      // to unlink somebody else's account through this endpoint.
      verify(githubService).unlinkAccount(any(UserEntity.class));
    }

    @Test
    @DisplayName("shouldReturn200_whenNothingWasLinked_becauseUnlinkIsIdempotent")
    void shouldBeIdempotent() throws Exception {
      // Given: unlinkAccount uses ifPresent, so an absent row is a no-op rather than a 404

      // When / Then
      mockMvc.perform(authed(delete(URL + "/unlink"))).andExpect(status().isOk());
    }

    @Test
    @DisplayName("shouldReturn401_whenCalledWithNoAuthorizationHeader")
    void shouldReturn401ForGuest() throws Exception {
      // When / Then
      mockMvc.perform(delete(URL + "/unlink")).andExpect(status().isUnauthorized());
      verify(githubService, never()).unlinkAccount(any());
    }
  }

  // =====================================================================
  // GET /v1/api/github/stats/{userId}
  // =====================================================================

  @Nested
  @DisplayName("GET /v1/api/github/stats/{userId}")
  class StatsTests {

    @Test
    @DisplayName("shouldReturn200AndTheStats_happyPath")
    void shouldReturnStats() throws Exception {
      // Given
      when(githubService.getGithubStats(42))
          .thenReturn(
              GithubStatsResponse.builder()
                  .githubUsername("octocat")
                  .publicReposCount(8)
                  .followersCount(120)
                  .lastSyncedAt(OffsetDateTime.parse("2026-08-23T01:00:00Z"))
                  .build());

      // When / Then
      mockMvc
          .perform(authed(get(URL + "/stats/42")))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.githubUsername").value("octocat"))
          .andExpect(jsonPath("$.publicReposCount").value(8))
          .andExpect(jsonPath("$.followersCount").value(120));
    }

    @Test
    @DisplayName("shouldReturn200ForAGuest_becauseTheStatsCardIsOnAPublicProfile")
    void shouldAllowGuest() throws Exception {
      // Given: SecurityConfig permits GET /v1/api/github/stats/* anonymously. This asserts the
      // matcher itself — a stranger viewing a public profile must see the GitHub card.
      when(githubService.getGithubStats(42))
          .thenReturn(GithubStatsResponse.builder().githubUsername("octocat").build());

      // When / Then
      mockMvc
          .perform(get(URL + "/stats/42"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.githubUsername").value("octocat"));
    }

    @Test
    @DisplayName("shouldReturn404_whenTheUserHasNoLinkedGithubAccount")
    void shouldReturn404() throws Exception {
      // Given
      when(githubService.getGithubStats(777))
          .thenThrow(new NotFoundException("GitHub account not linked"));

      // When / Then
      mockMvc
          .perform(authed(get(URL + "/stats/777")))
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.message").value("GitHub account not linked"));
    }

    @Test
    @DisplayName("shouldReturn400_whenTheUserIdIsNotNumeric")
    void shouldReturn400OnNonNumericId() throws Exception {
      // Given: Spring's own @PathVariable conversion fails with
      // MethodArgumentTypeMismatchException, which the handler maps to 400.

      // When / Then
      mockMvc.perform(authed(get(URL + "/stats/abc"))).andExpect(status().isBadRequest());
    }
  }

  // =====================================================================
  // POST /v1/api/github/sync
  // =====================================================================

  @Nested
  @DisplayName("POST /v1/api/github/sync")
  class SyncTests {

    @Test
    @DisplayName("shouldReturn200AndSyncTheCallersOwnAccount_happyPath")
    void shouldSync() throws Exception {
      // When
      mockMvc.perform(authed(post(URL + "/sync"))).andExpect(status().isOk());

      // Then: the caller id comes from the token, never from the request
      verify(githubService).syncNow(CALLER_ID);
    }

    @Test
    @DisplayName("shouldReturn404_whenNoGithubAccountIsLinked")
    void shouldReturn404() throws Exception {
      // Given
      doThrow(new NotFoundException("GitHub account not linked"))
          .when(githubService)
          .syncNow(CALLER_ID);

      // When / Then
      mockMvc.perform(authed(post(URL + "/sync"))).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("shouldReturn409_whenSyncedWithinTheLastHour_becauseOfTheManualRateLimit")
    void shouldReturn409WhenRateLimited() throws Exception {
      // Given: syncNow throws ConflictException for the 1-hour cooldown, and the handler maps
      // that to 409 CONFLICT — the established convention for a wrong-state request here.
      doThrow(new ConflictException("Please wait at least 1 hour before syncing again"))
          .when(githubService)
          .syncNow(CALLER_ID);

      // When / Then
      mockMvc
          .perform(authed(post(URL + "/sync")))
          .andExpect(status().isConflict())
          .andExpect(
              jsonPath("$.message").value("Please wait at least 1 hour before syncing again"));
    }

    @Test
    @DisplayName("shouldReturn503_whenGithubsApiIsUnreachable")
    void shouldReturn503() throws Exception {
      // Given
      doThrow(new ExternalApiException("GitHub API call failed"))
          .when(githubService)
          .syncNow(CALLER_ID);

      // When / Then
      mockMvc.perform(authed(post(URL + "/sync"))).andExpect(status().isServiceUnavailable());
    }

    @Test
    @DisplayName("shouldReturn401_whenCalledWithNoAuthorizationHeader")
    void shouldReturn401ForGuest() throws Exception {
      // When / Then
      mockMvc.perform(post(URL + "/sync")).andExpect(status().isUnauthorized());
      verify(githubService, never()).syncNow(any());
    }
  }
}
