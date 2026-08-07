package com.socialapp.posts.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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

import com.socialapp.common.exception.NotFoundException;
import com.socialapp.moderation.exception.UserBannedException;
import com.socialapp.moderation.service.BanDetailsService;
import com.socialapp.posts.dto.MyReactionResponseDto;
import com.socialapp.posts.dto.ReactorPageResponseDto;
import com.socialapp.posts.entity.enums.ReactionType;
import com.socialapp.posts.service.PostReactionService;
import com.socialapp.security.config.CustomAccessDeniedHandler;
import com.socialapp.security.config.CustomAuthenticationEntryPoint;
import com.socialapp.security.config.JwtAuthenticationFilter;
import com.socialapp.security.config.JwtProvider;
import com.socialapp.security.config.SecurityConfig;
import com.socialapp.security.dto.PublicUserResponse;
import com.socialapp.security.entity.UserEntity;
import com.socialapp.security.entity.UserRole;
import com.socialapp.security.repository.UserRepository;

/**
 * System/API integration tests for {@link PostReactionController}, per ISTQB CTFL v4.0.1 Section
 * 2.2.2, using {@code @WebMvcTest} + {@code MockMvc}. {@link PostReactionService} is mocked.
 *
 * <p>{@code upsertReaction}'s {@code @RequestBody} carries {@code @Valid}, which activates
 * {@link com.socialapp.posts.dto.UpsertPostReactionRequestDto#reactionType}'s {@code @NotNull}:
 * a missing {@code reactionType} is rejected with 422, the same {@code
 * MethodArgumentNotValidException} path used by {@code AuthController}/{@code
 * ProfileController}'s {@code @RequestBody @Valid} DTOs. (Previously the controller had no
 * {@code @Valid} at all, so this constraint was dead — fixed after this gap was found via
 * testing.)
 */
@WebMvcTest(PostReactionController.class)
@Import({
  SecurityConfig.class,
  CustomAuthenticationEntryPoint.class,
  CustomAccessDeniedHandler.class,
  JwtAuthenticationFilter.class
})
class PostReactionControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockBean private PostReactionService postReactionService;
  @MockBean private JwtProvider jwtProvider;

  @MockBean
  private BanDetailsService
      banDetailsService; // JwtAuthenticationFilter builds the banned-account 403 through it

  @MockBean private UserRepository userRepository;

  private static final String VALID_TOKEN = "a-valid-jwt-token";

  private UserEntity currentUser;

  @BeforeEach
  void setUpDefaultUser() {
    currentUser = new UserEntity();
    currentUser.setId(1);
    currentUser.setEmail("reactor@example.com");
    currentUser.setUsername("reactor");
    currentUser.setFullName("Reactor One");
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

  private static String reactionsUrl(Integer postId) {
    return "/v1/api/posts/" + postId + "/reactions";
  }

  // =====================================================================
  // GET /v1/api/posts/{postId}/reactions/me
  // =====================================================================

  @Nested
  @DisplayName("GET /v1/api/posts/{postId}/reactions/me")
  class GetMyReactionTests {

    @Test
    @DisplayName("shouldReturn200WithReactionType_whenUserHasReacted_happyPath")
    void shouldReturn200WithReactionType_whenUserHasReacted_happyPath() throws Exception {
      // Given
      when(postReactionService.getMyReaction(currentUser.getId(), 1))
          .thenReturn(new MyReactionResponseDto(ReactionType.LOVE));

      // When / Then
      mockMvc
          .perform(authed(get(reactionsUrl(1) + "/me")))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.reactionType").value("LOVE"));
    }

    @Test
    @DisplayName("shouldReturn200WithNullReactionType_whenUserHasNotReacted")
    void shouldReturn200WithNullReactionType_whenUserHasNotReacted() throws Exception {
      // Given
      when(postReactionService.getMyReaction(currentUser.getId(), 1))
          .thenReturn(new MyReactionResponseDto(null));

      // When / Then
      mockMvc
          .perform(authed(get(reactionsUrl(1) + "/me")))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.reactionType").value((String) null));
    }

    @Test
    @DisplayName("shouldReturn404_whenPostDoesNotExist")
    void shouldReturn404_whenPostDoesNotExist() throws Exception {
      // Given
      doThrow(new NotFoundException("Post not found with ID: 999"))
          .when(postReactionService)
          .getMyReaction(anyInt(), eq(999));

      // When / Then
      mockMvc
          .perform(authed(get(reactionsUrl(999) + "/me")))
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.message").value("Post not found with ID: 999"));
    }

    @Test
    @DisplayName("shouldReturn401_whenCalledWithNoAuthorizationHeader")
    void shouldReturn401_whenCalledWithNoAuthorizationHeader() throws Exception {
      // When / Then
      mockMvc.perform(get(reactionsUrl(1) + "/me")).andExpect(status().isUnauthorized());
    }
  }

  // =====================================================================
  // PUT /v1/api/posts/{postId}/reactions
  // =====================================================================

  @Nested
  @DisplayName("PUT /v1/api/posts/{postId}/reactions")
  class UpsertReactionTests {

    @Test
    @DisplayName("shouldReturn200_whenReactionTypeIsValid_happyPath")
    void shouldReturn200_whenReactionTypeIsValid_happyPath() throws Exception {
      // Given
      String requestJson =
          """
          { "reactionType": "LIKE" }
          """;

      // When / Then
      mockMvc
          .perform(
              authed(put(reactionsUrl(1)))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(requestJson))
          .andExpect(status().isOk());

      verify(postReactionService).upsertReaction(eq(currentUser.getId()), eq(1), any());
    }

    @Test
    @DisplayName("shouldReturn422_whenReactionTypeIsMissing")
    void shouldReturn422_whenReactionTypeIsMissing() throws Exception {
      // Given — @NotNull on reactionType is now enforced via @Valid on the controller
      String requestJson = "{}";

      // When / Then
      mockMvc
          .perform(
              authed(put(reactionsUrl(1)))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(requestJson))
          .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("shouldReturn404_whenPostDoesNotExist")
    void shouldReturn404_whenPostDoesNotExist() throws Exception {
      // Given
      doThrow(new NotFoundException("Post not found with ID: 999"))
          .when(postReactionService)
          .upsertReaction(anyInt(), eq(999), any());
      String requestJson =
          """
          { "reactionType": "LIKE" }
          """;

      // When / Then
      mockMvc
          .perform(
              authed(put(reactionsUrl(999)))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(requestJson))
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.message").value("Post not found with ID: 999"));
    }

    @Test
    @DisplayName("shouldReturn403_whenUserIsBannedFromReacting")
    void shouldReturn403_whenUserIsBannedFromReacting() throws Exception {
      // Given
      doThrow(new UserBannedException(OffsetDateTime.parse("2026-12-31T00:00:00Z")))
          .when(postReactionService)
          .upsertReaction(anyInt(), anyInt(), any());
      String requestJson =
          """
          { "reactionType": "LOVE" }
          """;

      // When / Then
      mockMvc
          .perform(
              authed(put(reactionsUrl(1)))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(requestJson))
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.error").value("Account Restricted"));
    }

    @Test
    @DisplayName("shouldReturn400_whenPostIdPathVariableIsNotANumber")
    void shouldReturn400_whenPostIdPathVariableIsNotANumber() throws Exception {
      // Given
      String requestJson =
          """
          { "reactionType": "LIKE" }
          """;

      // When / Then — EP: postId must be an Integer
      mockMvc
          .perform(
              authed(put("/v1/api/posts/not-a-number/reactions"))
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
          { "reactionType": "LIKE" }
          """;

      // When / Then
      mockMvc
          .perform(
              put(reactionsUrl(1)).contentType(MediaType.APPLICATION_JSON).content(requestJson))
          .andExpect(status().isUnauthorized());
    }
  }

  // =====================================================================
  // DELETE /v1/api/posts/{postId}/reactions
  // =====================================================================

  @Nested
  @DisplayName("DELETE /v1/api/posts/{postId}/reactions")
  class RemoveReactionTests {

    @Test
    @DisplayName("shouldReturn200_happyPath")
    void shouldReturn200_happyPath() throws Exception {
      // When / Then
      mockMvc.perform(authed(delete(reactionsUrl(1)))).andExpect(status().isOk());

      verify(postReactionService).removeReaction(currentUser.getId(), 1);
    }

    @Test
    @DisplayName("shouldReturn404_whenReactionDoesNotExist")
    void shouldReturn404_whenReactionDoesNotExist() throws Exception {
      // Given
      doThrow(new NotFoundException("Reaction not found for this post"))
          .when(postReactionService)
          .removeReaction(anyInt(), anyInt());

      // When / Then
      mockMvc
          .perform(authed(delete(reactionsUrl(1))))
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.message").value("Reaction not found for this post"));
    }

    @Test
    @DisplayName("shouldReturn401_whenCalledWithNoAuthorizationHeader")
    void shouldReturn401_whenCalledWithNoAuthorizationHeader() throws Exception {
      // When / Then
      mockMvc.perform(delete(reactionsUrl(1))).andExpect(status().isUnauthorized());
    }
  }

  // =====================================================================
  // GET /v1/api/posts/{postId}/reactions/summary  and  GET .../reactions
  // =====================================================================

  @Nested
  @DisplayName("GET /v1/api/posts/{postId}/reactions/summary")
  class GetReactionSummaryTests {

    @Test
    @DisplayName("shouldReturn200AndCountsPerType_happyPath")
    void shouldReturnCounts() throws Exception {
      // Given
      when(postReactionService.getReactionSummary(1, 1))
          .thenReturn(java.util.Map.of(ReactionType.LIKE, 4L, ReactionType.LOVE, 2L));

      // When / Then
      mockMvc
          .perform(authed(get(reactionsUrl(1) + "/summary")))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.LIKE").value(4))
          .andExpect(jsonPath("$.LOVE").value(2));
    }

    @Test
    @DisplayName("shouldReturn404_whenTheViewerMayNotSeeThePost")
    void shouldReturn404() throws Exception {
      // Given
      when(postReactionService.getReactionSummary(1, 999))
          .thenThrow(new NotFoundException("Post not found with ID: 999"));

      // When / Then
      mockMvc.perform(authed(get(reactionsUrl(999) + "/summary"))).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("shouldReturn401_whenCalledWithNoAuthorizationHeader")
    void shouldReturn401() throws Exception {
      // When / Then
      mockMvc.perform(get(reactionsUrl(1) + "/summary")).andExpect(status().isUnauthorized());
    }
  }

  @Nested
  @DisplayName("GET /v1/api/posts/{postId}/reactions")
  class GetReactorsTests {

    @Test
    @DisplayName("shouldReturn200AndTheReactorPage_happyPath")
    void shouldReturnReactors() throws Exception {
      // Given
      when(postReactionService.getReactors(1, 1, null, null, 20))
          .thenReturn(
              new ReactorPageResponseDto(
                  java.util.List.of(
                      new PublicUserResponse(
                          5,
                          "ada",
                          "Ada",
                          null,
                          0,
                          java.time.OffsetDateTime.parse("2026-01-01T00:00:00Z"))),
                  null,
                  false,
                  1));

      // When / Then
      mockMvc
          .perform(authed(get(reactionsUrl(1))))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.reactors[0].id").value(5))
          .andExpect(jsonPath("$.reactors[0].email").doesNotExist())
          .andExpect(jsonPath("$.totalCount").value(1));
    }

    @Test
    @DisplayName("shouldPassTypeAndCursorThrough_whenProvided")
    void shouldPassFiltersThrough() throws Exception {
      // Given
      when(postReactionService.getReactors(1, 1, ReactionType.LOVE, 4, 2))
          .thenReturn(new ReactorPageResponseDto(java.util.List.of(), null, false, 0));

      // When
      mockMvc
          .perform(
              authed(get(reactionsUrl(1)))
                  .param("type", "LOVE")
                  .param("cursor", "4")
                  .param("limit", "2"))
          .andExpect(status().isOk());

      // Then
      verify(postReactionService).getReactors(1, 1, ReactionType.LOVE, 4, 2);
    }

    @Test
    @DisplayName("shouldReturn400_whenTypeIsNotAKnownReaction")
    void shouldReturn400ForUnknownType() throws Exception {
      // When / Then
      mockMvc
          .perform(authed(get(reactionsUrl(1))).param("type", "SHRUG"))
          .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("shouldReturn401_whenCalledWithNoAuthorizationHeader")
    void shouldReturn401() throws Exception {
      // When / Then
      mockMvc.perform(get(reactionsUrl(1))).andExpect(status().isUnauthorized());
    }
  }
}
