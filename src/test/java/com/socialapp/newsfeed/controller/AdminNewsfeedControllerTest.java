package com.socialapp.newsfeed.controller;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.socialapp.moderation.service.BanDetailsService;
import com.socialapp.newsfeed.dto.FeedRebuildResultDto;
import com.socialapp.newsfeed.service.NewsfeedService;
import com.socialapp.security.config.CustomAccessDeniedHandler;
import com.socialapp.security.config.CustomAuthenticationEntryPoint;
import com.socialapp.security.config.JwtAuthenticationFilter;
import com.socialapp.security.config.JwtProvider;
import com.socialapp.security.config.SecurityConfig;
import com.socialapp.security.entity.UserEntity;
import com.socialapp.security.entity.UserRole;
import com.socialapp.security.repository.UserRepository;

/**
 * System/API integration tests for {@link AdminNewsfeedController}, per ISTQB CTFL v4.0.1 Section
 * 2.2.2, using {@code @WebMvcTest} + {@code MockMvc}. {@link NewsfeedService} is mocked.
 *
 * <p>The authorization case is the point of this class. The controller carries no {@code
 * @PreAuthorize} of its own — it relies entirely on {@code SecurityConfig} mapping {@code
 * /v1/api/admin/**} to {@code hasRole("ADMIN")}, which means the guard lives in a file the
 * controller never mentions. An endpoint that rewrites every user's feed is not one to leave that
 * assumption unverified, so the 403 for an authenticated non-admin is asserted here against a
 * real {@code UserEntity} with {@code ROLE_USER}, exactly as {@code AdminModerationControllerTest}
 * does one package over.
 */
@WebMvcTest(AdminNewsfeedController.class)
@Import({
  SecurityConfig.class,
  CustomAuthenticationEntryPoint.class,
  CustomAccessDeniedHandler.class,
  JwtAuthenticationFilter.class
})
class AdminNewsfeedControllerTest {

  private static final String URL = "/v1/api/admin/newsfeed/rebuild";
  private static final String ADMIN_TOKEN = "a-valid-admin-jwt-token";
  private static final String USER_TOKEN = "a-valid-user-jwt-token";

  @Autowired private MockMvc mockMvc;

  @MockBean private NewsfeedService newsfeedService;
  @MockBean private JwtProvider jwtProvider;
  @MockBean private BanDetailsService banDetailsService;
  @MockBean private UserRepository userRepository;

  @BeforeEach
  void setUpUsers() {
    UserEntity admin = sampleUser(1, "admin@example.com", UserRole.ADMIN);
    UserEntity regular = sampleUser(2, "user@example.com", UserRole.USER);

    when(jwtProvider.isTokenValid(ADMIN_TOKEN)).thenReturn(true);
    when(jwtProvider.extractEmail(ADMIN_TOKEN)).thenReturn(admin.getEmail());
    when(userRepository.findByEmailIgnoreCase(admin.getEmail())).thenReturn(Optional.of(admin));

    when(jwtProvider.isTokenValid(USER_TOKEN)).thenReturn(true);
    when(jwtProvider.extractEmail(USER_TOKEN)).thenReturn(regular.getEmail());
    when(userRepository.findByEmailIgnoreCase(regular.getEmail())).thenReturn(Optional.of(regular));
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

  private static MockHttpServletRequestBuilder with(
      MockHttpServletRequestBuilder builder, String token) {
    return builder.header("Authorization", "Bearer " + token);
  }

  @Test
  @DisplayName("shouldReturn200WithProcessedAndSkippedCounts_whenCallerIsAdmin_happyPath")
  void shouldRebuildForAdmin() throws Exception {
    // Given — the counts are the whole observable result: the effect itself lands in Redis, so
    // without them a rebuild that matched nothing looks the same as one that fanned out 200 posts
    when(newsfeedService.rebuildAll()).thenReturn(new FeedRebuildResultDto(169, 2));

    // When / Then
    mockMvc
        .perform(with(post(URL), ADMIN_TOKEN))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.processed").value(169))
        .andExpect(jsonPath("$.skipped").value(2));
  }

  @Test
  @DisplayName("shouldReturn403_whenCallerIsAuthenticatedButNotAdmin")
  void shouldRefuseNonAdmin() throws Exception {
    // Given — an ordinary signed-in user. Rebuilding every feed in the product is not something a
    // reader may set off.
    mockMvc.perform(with(post(URL), USER_TOKEN)).andExpect(status().isForbidden());

    // Then
    verify(newsfeedService, never()).rebuildAll();
  }

  @Test
  @DisplayName("shouldReturn401_whenCallerIsAnonymous")
  void shouldRefuseGuest() throws Exception {
    // When / Then
    mockMvc.perform(post(URL)).andExpect(status().isUnauthorized());

    verify(newsfeedService, never()).rebuildAll();
  }
}
