package com.socialapp.linkpreview.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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

import com.socialapp.common.exception.ExternalApiException;
import com.socialapp.common.exception.ValidationException;
import com.socialapp.linkpreview.dto.LinkPreviewResponseDto;
import com.socialapp.linkpreview.service.LinkPreviewService;
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
 * System/API integration tests for {@link LinkPreviewController}, per ISTQB CTFL v4.0.1 Section
 * 2.2.2, using {@code @WebMvcTest} + {@code MockMvc}. {@link LinkPreviewService} is mocked.
 *
 * <p><b>The guest case is asserted, not assumed.</b> This endpoint makes the server fetch a URL the
 * caller chose, and the rate limit that bounds how often is keyed on a user id. An anonymous caller
 * has none, so opening this to guests would silently turn that limit into a per-IP one — which
 * costs an attacker nothing to defeat.
 */
@WebMvcTest(LinkPreviewController.class)
@Import({
  SecurityConfig.class,
  CustomAuthenticationEntryPoint.class,
  CustomAccessDeniedHandler.class,
  JwtAuthenticationFilter.class
})
class LinkPreviewControllerTest {

  private static final String URL = "/v1/api/link-preview";
  private static final String TOKEN = "a-valid-jwt-token";
  private static final Integer USER_ID = 9001;

  @Autowired private MockMvc mockMvc;

  @MockBean private LinkPreviewService linkPreviewService;
  @MockBean private JwtProvider jwtProvider;
  @MockBean private BanDetailsService banDetailsService;
  @MockBean private UserRepository userRepository;

  @BeforeEach
  void setUpCaller() {
    UserEntity user = new UserEntity();
    user.setId(USER_ID);
    user.setEmail("composer@example.com");
    user.setUsername("composer");
    user.setFullName("Composer");
    user.setRole(UserRole.USER);
    user.setEmailVerified(true);

    when(jwtProvider.isTokenValid(TOKEN)).thenReturn(true);
    when(jwtProvider.extractEmail(TOKEN)).thenReturn(user.getEmail());
    when(userRepository.findByEmailIgnoreCase(user.getEmail())).thenReturn(Optional.of(user));
  }

  private static MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder) {
    return builder.header("Authorization", "Bearer " + TOKEN);
  }

  private static String body(String url) {
    return "{\"url\":\"" + url + "\"}";
  }

  @Nested
  @DisplayName("POST /v1/api/link-preview")
  class PreviewTests {

    @Test
    @DisplayName("shouldReturn200AndTheFourLinkDetailsFields_happyPath")
    void shouldReturnPreview() throws Exception {
      // Given
      when(linkPreviewService.preview(eq(USER_ID), anyString()))
          .thenReturn(
              new LinkPreviewResponseDto(
                  "A title", "A description", "https://cdn.example.com/c.png", "Example"));

      // When / Then — the four fields LinkDetails carries, in the order it carries them, so the
      // composer fills its form from this response one-to-one
      mockMvc
          .perform(
              authed(post(URL))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body("https://example.com/post")))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.title").value("A title"))
          .andExpect(jsonPath("$.description").value("A description"))
          .andExpect(jsonPath("$.thumbnailUrl").value("https://cdn.example.com/c.png"))
          .andExpect(jsonPath("$.siteName").value("Example"));
    }

    @Test
    @DisplayName("shouldReturn200WithNulls_whenThePageSaysNothingAboutItself")
    void shouldReturnEmptyPreview() throws Exception {
      // Given — a page describing itself is a courtesy, not a requirement
      when(linkPreviewService.preview(eq(USER_ID), anyString()))
          .thenReturn(new LinkPreviewResponseDto(null, null, null, "example.com"));

      // When / Then — 200, not 404: the composer prefills what it can and lets the author type
      // the rest, which is exactly what it did before this endpoint existed
      mockMvc
          .perform(
              authed(post(URL))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body("https://example.com/bare")))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.title").doesNotExist());
    }

    @Test
    @DisplayName("shouldTakeTheCallerFromTheSecurityContext_notTheRequestBody")
    void shouldTakeCallerFromSecurityContext() throws Exception {
      // Given
      when(linkPreviewService.preview(eq(USER_ID), anyString()))
          .thenReturn(new LinkPreviewResponseDto(null, null, null, "example.com"));

      // When
      mockMvc
          .perform(
              authed(post(URL))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body("https://example.com/x")))
          .andExpect(status().isOk());

      // Then — the id is the rate-limit key, so one accepted from the body would let a caller
      // spend somebody else's budget and keep their own
      verify(linkPreviewService).preview(eq(USER_ID), eq("https://example.com/x"));
    }

    @Test
    @DisplayName("shouldReturn401_whenCalledByAGuestWithNoAuthorizationHeader")
    void shouldRefuseGuests() throws Exception {
      // When / Then
      mockMvc
          .perform(
              post(URL)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body("https://example.com/x")))
          .andExpect(status().isUnauthorized());

      verify(linkPreviewService, never()).preview(any(), anyString());
    }

    @Test
    @DisplayName("shouldReturn422_whenUrlIsMissing")
    void shouldReturn422_whenUrlMissing() throws Exception {
      // When / Then — EP: url is @NotBlank
      mockMvc
          .perform(authed(post(URL)).contentType(MediaType.APPLICATION_JSON).content("{}"))
          .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("shouldReturn422_whenUrlIsBlank")
    void shouldReturn422_whenUrlBlank() throws Exception {
      // When / Then — EP: whitespace is not a URL
      mockMvc
          .perform(authed(post(URL)).contentType(MediaType.APPLICATION_JSON).content(body("   ")))
          .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("shouldReturn422_whenUrlExceedsTheLimit_boundary")
    void shouldReturn422_whenUrlTooLong() throws Exception {
      // When / Then — BVA: @Size(max = 2048), so 2049 characters must fail
      String longUrl = "https://example.com/" + "a".repeat(2049 - "https://example.com/".length());

      mockMvc
          .perform(authed(post(URL)).contentType(MediaType.APPLICATION_JSON).content(body(longUrl)))
          .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("shouldReturn400_whenTheLinkPointsSomewhereTheServerWillNotGo_security")
    void shouldReturn400_whenLinkRefused() throws Exception {
      // Given
      when(linkPreviewService.preview(eq(USER_ID), anyString()))
          .thenThrow(new ValidationException("That link points inside a private network"));

      // When / Then — 400 and a message that names no internal range
      mockMvc
          .perform(
              authed(post(URL))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body("http://169.254.169.254/")))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.message").value("That link points inside a private network"));
    }

    @Test
    @DisplayName("shouldReturn503_whenTheTargetSiteCannotBeReached")
    void shouldReturn503_whenFetchFails() throws Exception {
      // Given
      when(linkPreviewService.preview(eq(USER_ID), anyString()))
          .thenThrow(new ExternalApiException("Could not reach that link"));

      // When / Then — somebody else's site being down is not this server's fault, and it is
      // worth retrying
      mockMvc
          .perform(
              authed(post(URL))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body("https://example.com/gone")))
          .andExpect(status().isServiceUnavailable());
    }
  }
}
