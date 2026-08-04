package com.socialapp.reputation.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.socialapp.common.exception.NotFoundException;
import com.socialapp.reputation.dto.ReputationResponseDto;
import com.socialapp.reputation.service.ReputationService;
import com.socialapp.security.config.CustomAccessDeniedHandler;
import com.socialapp.security.config.CustomAuthenticationEntryPoint;
import com.socialapp.security.config.JwtAuthenticationFilter;
import com.socialapp.security.config.JwtProvider;
import com.socialapp.security.config.SecurityConfig;
import com.socialapp.security.entity.UserEntity;
import com.socialapp.security.entity.UserRole;
import com.socialapp.security.repository.UserRepository;

/**
 * System/API integration tests for {@link ReputationController}, per ISTQB CTFL v4.0.1 Section
 * 2.2.2, using {@code @WebMvcTest} + {@code MockMvc}. {@link ReputationService} is mocked.
 *
 * <p>Same real-{@link JwtAuthenticationFilter} auth simulation as {@code PostControllerTest} —
 * {@code SecurityUtils.getCurrentUserId()} elsewhere in the app requires a real {@link
 * UserEntity} principal, so authenticated tests stub {@link JwtProvider}/{@link UserRepository}
 * rather than using {@code @WithMockUser}. This endpoint reads a path {@code userId}, not the
 * caller's own id, but still sits behind the default {@code anyRequest().authenticated()} rule
 * in {@code SecurityConfig} (it is not in the {@code permitAll} allowlist), so the 401 case still
 * applies.
 */
@WebMvcTest(ReputationController.class)
@Import({
  SecurityConfig.class,
  CustomAuthenticationEntryPoint.class,
  CustomAccessDeniedHandler.class,
  JwtAuthenticationFilter.class
})
class ReputationControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockBean private ReputationService reputationService;
  @MockBean private JwtProvider jwtProvider;
  @MockBean private UserRepository userRepository;

  private static final String VALID_TOKEN = "a-valid-jwt-token";
  private static final Integer TARGET_USER_ID = 42;

  @BeforeEach
  void setUpDefaultCaller() {
    UserEntity caller = new UserEntity();
    caller.setId(1);
    caller.setEmail("caller@example.com");
    caller.setUsername("caller");
    caller.setFullName("Caller");
    caller.setRole(UserRole.USER);
    caller.setEmailVerified(true);

    when(jwtProvider.isTokenValid(VALID_TOKEN)).thenReturn(true);
    when(jwtProvider.extractEmail(VALID_TOKEN)).thenReturn(caller.getEmail());
    when(userRepository.findByEmailIgnoreCase(caller.getEmail())).thenReturn(Optional.of(caller));
  }

  private static MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder) {
    return builder.header("Authorization", "Bearer " + VALID_TOKEN);
  }

  // =====================================================================
  // GET /v1/api/users/{userId}/reputation
  // =====================================================================

  @Nested
  @DisplayName("GET /v1/api/users/{userId}/reputation")
  class GetReputationTests {

    @Test
    @DisplayName("shouldReturn200_whenUserExists_happyPath")
    void shouldReturn200_whenUserExists_happyPath() throws Exception {
      // Given
      ReputationResponseDto dto =
          ReputationResponseDto.builder()
              .eliteScore(120)
              .level(2)
              .levelName("Contributor")
              .currentLevelMin(100)
              .nextLevelMin(1000)
              .verifiedExpert(true)
              .build();
      when(reputationService.getReputation(TARGET_USER_ID)).thenReturn(dto);

      // When / Then
      mockMvc
          .perform(authed(get("/v1/api/users/" + TARGET_USER_ID + "/reputation")))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.eliteScore").value(120))
          .andExpect(jsonPath("$.level").value(2))
          .andExpect(jsonPath("$.levelName").value("Contributor"))
          .andExpect(jsonPath("$.currentLevelMin").value(100))
          .andExpect(jsonPath("$.nextLevelMin").value(1000))
          .andExpect(jsonPath("$.verifiedExpert").value(true));

      verify(reputationService).getReputation(eq(TARGET_USER_ID));
    }

    @Test
    @DisplayName("shouldReturn404_whenUserDoesNotExist")
    void shouldReturn404_whenUserDoesNotExist() throws Exception {
      // Given
      doThrow(new NotFoundException("User not found with ID: " + TARGET_USER_ID))
          .when(reputationService)
          .getReputation(TARGET_USER_ID);

      // When / Then
      mockMvc
          .perform(authed(get("/v1/api/users/" + TARGET_USER_ID + "/reputation")))
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.message").value("User not found with ID: " + TARGET_USER_ID));
    }

    @Test
    @DisplayName("shouldReturn400_whenUserIdPathVariableIsNotANumber")
    void shouldReturn400_whenUserIdPathVariableIsNotANumber() throws Exception {
      // Given — EP: userId must be an Integer; "abc" is outside that partition

      // When / Then
      mockMvc
          .perform(authed(get("/v1/api/users/abc/reputation")))
          .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("shouldReturn200_whenCalledByAGuestWithNoAuthorizationHeader")
    void shouldServeGuests() throws Exception {
      // Given: no Authorization header at all
      // When / Then — opened to guests when the product moved from closed to open;
      // this assertion is what stops a later SecurityConfig tidy-up closing it again.
      // Elite Score is part of the public profile a guest can open from a shared link.
      mockMvc
          .perform(get("/v1/api/users/" + TARGET_USER_ID + "/reputation"))
          .andExpect(status().isOk());
    }
  }
}
