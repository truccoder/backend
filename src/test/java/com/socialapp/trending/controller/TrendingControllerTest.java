package com.socialapp.trending.controller;

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

import com.socialapp.security.config.CustomAccessDeniedHandler;
import com.socialapp.security.config.CustomAuthenticationEntryPoint;
import com.socialapp.security.config.JwtAuthenticationFilter;
import com.socialapp.security.config.JwtProvider;
import com.socialapp.security.config.SecurityConfig;
import com.socialapp.security.entity.UserEntity;
import com.socialapp.security.entity.UserRole;
import com.socialapp.security.repository.UserRepository;
import com.socialapp.trending.dto.TrendingPageResponseDto;
import com.socialapp.trending.entity.enums.TrendingCategory;
import com.socialapp.trending.service.TrendingService;

/**
 * System/API integration tests for {@link TrendingController}, per ISTQB CTFL v4.0.1 Section
 * 2.2.2, using {@code @WebMvcTest} + {@code MockMvc}. {@link TrendingService} is mocked.
 *
 * <p>The controller method never calls {@code SecurityUtils.getCurrentUserId()} — {@code
 * category}/{@code timeRange} are the only inputs the service needs — but {@code
 * /v1/api/trending/**} still requires authentication under {@link SecurityConfig}'s {@code
 * anyRequest().authenticated()} default (it isn't in the {@code permitAll()} list), so 401 tests
 * still apply, same as {@code BookController}'s {@code previewBook}/{@code getReviews}.
 */
@WebMvcTest(TrendingController.class)
@Import({
  SecurityConfig.class,
  CustomAuthenticationEntryPoint.class,
  CustomAccessDeniedHandler.class,
  JwtAuthenticationFilter.class
})
class TrendingControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockBean private TrendingService trendingService;
  @MockBean private JwtProvider jwtProvider;
  @MockBean private UserRepository userRepository;

  private static final String TRENDING_URL = "/v1/api/trending";
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
  @DisplayName("GET /v1/api/trending")
  class GetTrendingTests {

    @Test
    @DisplayName("shouldReturn200AndPage_withDefaults_happyPath")
    void shouldReturn200AndPage_withDefaults_happyPath() throws Exception {
      // Given
      when(trendingService.getTrending(null, "week", 1, 10))
          .thenReturn(
              TrendingPageResponseDto.builder()
                  .items(List.of())
                  .page(1)
                  .size(10)
                  .totalElements(0)
                  .totalPages(0)
                  .hasNext(false)
                  .build());

      // When / Then
      mockMvc
          .perform(authed(get(TRENDING_URL)))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.page").value(1))
          .andExpect(jsonPath("$.hasNext").value(false));
    }

    @Test
    @DisplayName("shouldPassCategoryAndTimeRangeThrough_whenProvided")
    void shouldPassCategoryAndTimeRangeThrough_whenProvided() throws Exception {
      // Given
      when(trendingService.getTrending(TrendingCategory.TOOL, "month", 2, 5))
          .thenReturn(
              TrendingPageResponseDto.builder()
                  .items(List.of())
                  .page(2)
                  .size(5)
                  .totalElements(0)
                  .totalPages(0)
                  .hasNext(false)
                  .build());

      // When / Then
      mockMvc
          .perform(
              authed(get(TRENDING_URL))
                  .param("category", "TOOL")
                  .param("timeRange", "month")
                  .param("page", "2")
                  .param("size", "5"))
          .andExpect(status().isOk());

      verify(trendingService).getTrending(TrendingCategory.TOOL, "month", 2, 5);
    }

    @Test
    @DisplayName("shouldReturn400_whenCategoryIsNotAValidEnumValue")
    void shouldReturn400_whenCategoryIsNotAValidEnumValue() throws Exception {
      // When / Then — EP: category must be one of TrendingCategory's constants
      mockMvc
          .perform(authed(get(TRENDING_URL)).param("category", "NOT_A_REAL_CATEGORY"))
          .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("shouldReturn400_whenPageIsZero_boundary")
    void shouldReturn400_whenPageIsZero_boundary() throws Exception {
      // When / Then — BVA: @Positive requires > 0
      mockMvc
          .perform(authed(get(TRENDING_URL)).param("page", "0"))
          .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("shouldReturn401_whenCalledWithNoAuthorizationHeader")
    void shouldReturn401_whenCalledWithNoAuthorizationHeader() throws Exception {
      // When / Then — endpoint still requires authentication even though the controller method
      // never reads the current user
      mockMvc.perform(get(TRENDING_URL)).andExpect(status().isUnauthorized());
    }
  }
}
