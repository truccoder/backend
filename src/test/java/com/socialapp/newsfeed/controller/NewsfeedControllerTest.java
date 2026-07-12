package com.socialapp.newsfeed.controller;

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

import com.socialapp.newsfeed.dto.FeedPostDataDto;
import com.socialapp.newsfeed.dto.FeedResponseDto;
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
 * System/API integration tests for {@link NewsfeedController}, per ISTQB CTFL v4.0.1 Section
 * 2.2.2, using {@code @WebMvcTest} + {@code MockMvc}. {@link NewsfeedService} is mocked.
 *
 * <p>Single-endpoint controller with the same {@code @Positive} query-param pattern as {@code
 * FriendshipController}/{@code NotificationController}: constraint failures on {@code page}/{@code
 * size} are handled as <b>400 Bad Request</b> (see {@code GlobalExceptionHandler}'s class-level
 * Javadoc for why that differs from the 422 used for {@code @RequestBody @Valid} failures). {@code
 * NewsfeedService#getFeed} throws nothing the controller needs to map, so there is no Exception
 * Mapping section here.
 */
@WebMvcTest(NewsfeedController.class)
@Import({
  SecurityConfig.class,
  CustomAuthenticationEntryPoint.class,
  CustomAccessDeniedHandler.class,
  JwtAuthenticationFilter.class
})
class NewsfeedControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockBean private NewsfeedService newsfeedService;
  @MockBean private JwtProvider jwtProvider;
  @MockBean private UserRepository userRepository;

  private static final String FEED_URL = "/v1/api/feed";
  private static final String VALID_TOKEN = "a-valid-jwt-token";

  private UserEntity currentUser;

  @BeforeEach
  void setUpDefaultUser() {
    currentUser = new UserEntity();
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
  @DisplayName("GET /v1/api/feed")
  class GetFeedTests {

    @Test
    @DisplayName("shouldReturn200AndFeed_withDefaultPagination_happyPath")
    void shouldReturn200AndFeed_withDefaultPagination_happyPath() throws Exception {
      // Given
      FeedPostDataDto post =
          FeedPostDataDto.builder().postId(1).authorId(2).content("Hello").build();
      when(newsfeedService.getFeed(currentUser.getId(), 1, 10))
          .thenReturn(
              FeedResponseDto.builder()
                  .posts(List.of(post))
                  .page(1)
                  .size(10)
                  .hasMore(false)
                  .build());

      // When / Then
      mockMvc
          .perform(authed(get(FEED_URL)))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.posts[0].postId").value(1))
          .andExpect(jsonPath("$.page").value(1))
          .andExpect(jsonPath("$.hasMore").value(false));
    }

    @Test
    @DisplayName("shouldPassPageAndSizeThrough_whenProvided")
    void shouldPassPageAndSizeThrough_whenProvided() throws Exception {
      // Given
      when(newsfeedService.getFeed(currentUser.getId(), 3, 20))
          .thenReturn(
              FeedResponseDto.builder().posts(List.of()).page(3).size(20).hasMore(false).build());

      // When / Then
      mockMvc
          .perform(authed(get(FEED_URL)).param("page", "3").param("size", "20"))
          .andExpect(status().isOk());

      verify(newsfeedService).getFeed(currentUser.getId(), 3, 20);
    }

    @Test
    @DisplayName("shouldReturn400_whenPageIsZero_boundary")
    void shouldReturn400_whenPageIsZero_boundary() throws Exception {
      // When / Then — BVA: @Positive requires > 0
      mockMvc.perform(authed(get(FEED_URL)).param("page", "0")).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("shouldReturn400_whenSizeIsNegative")
    void shouldReturn400_whenSizeIsNegative() throws Exception {
      // When / Then — EP: negative numbers are outside the @Positive partition
      mockMvc.perform(authed(get(FEED_URL)).param("size", "-5")).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("shouldReturn401_whenCalledWithNoAuthorizationHeader")
    void shouldReturn401_whenCalledWithNoAuthorizationHeader() throws Exception {
      // When / Then
      mockMvc.perform(get(FEED_URL)).andExpect(status().isUnauthorized());
    }
  }
}
