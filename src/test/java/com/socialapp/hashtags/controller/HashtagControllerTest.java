package com.socialapp.hashtags.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.socialapp.hashtags.dto.HashtagDto;
import com.socialapp.hashtags.service.HashtagQueryService;
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
 * System/API integration tests for {@link HashtagController}, per ISTQB CTFL v4.0.1 Section 2.2.2,
 * using {@code @WebMvcTest} + {@code MockMvc}. {@link HashtagQueryService} is mocked.
 *
 * <p>Both endpoints are in {@link SecurityConfig}'s {@code permitAll()} list (B31), so the guest
 * tests here are what stops a later tidy-up from closing them.
 */
@WebMvcTest(HashtagController.class)
@Import({
  SecurityConfig.class,
  CustomAuthenticationEntryPoint.class,
  CustomAccessDeniedHandler.class,
  JwtAuthenticationFilter.class
})
class HashtagControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockBean private HashtagQueryService hashtagQueryService;
  @MockBean private JwtProvider jwtProvider;
  @MockBean private BanDetailsService banDetailsService;
  @MockBean private UserRepository userRepository;

  private static final String HASHTAGS_URL = "/v1/api/hashtags";
  private static final String VALID_TOKEN = "a-valid-jwt-token";

  @BeforeEach
  void setUpDefaultUser() {
    UserEntity currentUser = new UserEntity();
    currentUser.setId(1);
    currentUser.setEmail("viewer@example.com");
    currentUser.setUsername("viewer");
    currentUser.setFullName("Viewer One");
    currentUser.setRole(UserRole.USER);
    currentUser.setEmailVerified(true);

    when(jwtProvider.isTokenValid(VALID_TOKEN)).thenReturn(true);
    when(jwtProvider.extractEmail(VALID_TOKEN)).thenReturn(currentUser.getEmail());
    when(userRepository.findByEmailIgnoreCase(currentUser.getEmail()))
        .thenReturn(Optional.of(currentUser));
  }

  private static MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder) {
    return builder.header("Authorization", "Bearer " + VALID_TOKEN);
  }

  @Nested
  @DisplayName("GET /v1/api/hashtags/suggest")
  class Suggest {

    @Test
    @DisplayName("shouldReturn200AndTheList_happyPath")
    void happyPath() throws Exception {
      when(hashtagQueryService.suggest("java", 8))
          .thenReturn(List.of(new HashtagDto("java", 42), new HashtagDto("javascript", 7)));

      mockMvc
          .perform(authed(get(HASHTAGS_URL + "/suggest")).param("q", "java"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$[0].tag").value("java"))
          .andExpect(jsonPath("$[0].postCount").value(42))
          .andExpect(jsonPath("$[1].tag").value("javascript"));
    }

    @Test
    @DisplayName("shouldPassLimitThrough_whenProvided")
    void passesLimit() throws Exception {
      when(hashtagQueryService.suggest("re", 3)).thenReturn(List.of());

      mockMvc
          .perform(authed(get(HASHTAGS_URL + "/suggest")).param("q", "re").param("limit", "3"))
          .andExpect(status().isOk());

      verify(hashtagQueryService).suggest("re", 3);
    }

    @Test
    @DisplayName("shouldReturn400_whenQIsMissing")
    void requiresQ() throws Exception {
      mockMvc.perform(authed(get(HASHTAGS_URL + "/suggest"))).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("shouldReturn400_whenQIsBlank")
    void rejectsBlankQ() throws Exception {
      mockMvc
          .perform(authed(get(HASHTAGS_URL + "/suggest")).param("q", "   "))
          .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("shouldReturn400_whenLimitExceeds20_boundary")
    void rejectsLimitOver20() throws Exception {
      mockMvc
          .perform(authed(get(HASHTAGS_URL + "/suggest")).param("q", "java").param("limit", "21"))
          .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("shouldReturn200_whenCalledByAGuest")
    void servesGuests() throws Exception {
      when(hashtagQueryService.suggest("java", 8)).thenReturn(List.of());

      mockMvc.perform(get(HASHTAGS_URL + "/suggest").param("q", "java")).andExpect(status().isOk());
    }
  }

  @Nested
  @DisplayName("GET /v1/api/hashtags/trending")
  class Trending {

    @Test
    @DisplayName("shouldReturn200_withDefaults_happyPath")
    void happyPathDefaults() throws Exception {
      when(hashtagQueryService.trending("week", 10))
          .thenReturn(List.of(new HashtagDto("systemdesign", 12)));

      mockMvc
          .perform(authed(get(HASHTAGS_URL + "/trending")))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$[0].tag").value("systemdesign"))
          .andExpect(jsonPath("$[0].postCount").value(12));

      verify(hashtagQueryService).trending("week", 10);
    }

    @Test
    @DisplayName("shouldPassWindowAndLimitThrough_whenProvided")
    void passesWindowAndLimit() throws Exception {
      when(hashtagQueryService.trending("today", 5)).thenReturn(List.of());

      mockMvc
          .perform(
              authed(get(HASHTAGS_URL + "/trending")).param("window", "today").param("limit", "5"))
          .andExpect(status().isOk());

      verify(hashtagQueryService).trending("today", 5);
    }

    @Test
    @DisplayName("shouldReturn400_whenLimitExceedsTheCap_boundary")
    void rejectsLimitOverCap() throws Exception {
      mockMvc
          .perform(authed(get(HASHTAGS_URL + "/trending")).param("limit", "51"))
          .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("shouldReturn200_whenCalledByAGuest")
    void servesGuests() throws Exception {
      when(hashtagQueryService.trending("week", 10)).thenReturn(List.of());

      mockMvc.perform(get(HASHTAGS_URL + "/trending")).andExpect(status().isOk());
    }
  }
}
