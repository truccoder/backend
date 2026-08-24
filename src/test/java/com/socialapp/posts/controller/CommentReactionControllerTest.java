package com.socialapp.posts.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import com.socialapp.posts.entity.enums.ReactionType;
import com.socialapp.posts.service.CommentReactionService;
import com.socialapp.security.config.CustomAccessDeniedHandler;
import com.socialapp.security.config.CustomAuthenticationEntryPoint;
import com.socialapp.security.config.JwtAuthenticationFilter;
import com.socialapp.security.config.JwtProvider;
import com.socialapp.security.config.SecurityConfig;
import com.socialapp.security.entity.UserEntity;
import com.socialapp.security.entity.UserRole;
import com.socialapp.security.repository.UserRepository;

/**
 * System/API integration tests for {@link CommentReactionController}, per ISTQB CTFL v4.0.1
 * Section 2.2.2, using {@code @WebMvcTest} + {@code MockMvc}. {@link CommentReactionService} is
 * mocked.
 *
 * <p>Two things this class exists to pin down beyond the happy path. The route is nested three
 * segments deep under {@code /v1/api/posts}, and the guest-readable entries in {@code
 * SecurityConfig} are all pinned to GET on a single segment — so an anonymous caller must get 401
 * here even though {@code GET /v1/api/posts/&#123;id&#125;} one level up is open. And the verb is
 * PUT, matching {@code PostReactionController}: POST is not mapped and must come back 405, which
 * is the difference a client that followed the original plan document would hit first.
 */
@WebMvcTest(CommentReactionController.class)
@Import({
  SecurityConfig.class,
  CustomAuthenticationEntryPoint.class,
  CustomAccessDeniedHandler.class,
  JwtAuthenticationFilter.class
})
class CommentReactionControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockBean private CommentReactionService commentReactionService;
  @MockBean private JwtProvider jwtProvider;
  @MockBean private BanDetailsService banDetailsService;
  @MockBean private UserRepository userRepository;

  private static final String VALID_TOKEN = "a-valid-jwt-token";
  private static final Integer POST_ID = 100;
  private static final Integer COMMENT_ID = 500;

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

  private static String reactionsUrl(Integer postId, Integer commentId) {
    return "/v1/api/posts/" + postId + "/comments/" + commentId + "/reactions";
  }

  private static String body(String reactionType) {
    return "{\"reactionType\":"
        + (reactionType == null ? "null" : "\"" + reactionType + "\"")
        + "}";
  }

  // =====================================================================
  // PUT /v1/api/posts/{postId}/comments/{commentId}/reactions
  // =====================================================================

  @Nested
  @DisplayName("PUT /v1/api/posts/{postId}/comments/{commentId}/reactions")
  class UpsertReactionTests {

    @Test
    @DisplayName("shouldReturn200AndPassTheCallerId_whenReactionTypeIsValid_happyPath")
    void shouldReturn200AndPassTheCallerId_whenReactionTypeIsValid_happyPath() throws Exception {
      // When / Then
      mockMvc
          .perform(
              authed(put(reactionsUrl(POST_ID, COMMENT_ID)))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body("INSIGHT")))
          .andExpect(status().isOk());

      // Then — the id comes from the security context, never from the path or the body
      verify(commentReactionService)
          .upsertReaction(eq(currentUser.getId()), eq(POST_ID), eq(COMMENT_ID), any());
    }

    @Test
    @DisplayName("shouldAcceptTheTwoNewReactionTypes_whenSentOnTheWire")
    void shouldAcceptTheTwoNewReactionTypes_whenSentOnTheWire() throws Exception {
      // Given — INSIGHT and CLAP were added to ReactionType for the knowledge-shaped reactions the
      // design calls for; a comment must accept them as a post does
      for (ReactionType type : new ReactionType[] {ReactionType.INSIGHT, ReactionType.CLAP}) {
        mockMvc
            .perform(
                authed(put(reactionsUrl(POST_ID, COMMENT_ID)))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body(type.name())))
            .andExpect(status().isOk());
      }
    }

    @Test
    @DisplayName("shouldReturn422_whenReactionTypeIsMissing")
    void shouldReturn422_whenReactionTypeIsMissing() throws Exception {
      // Given — @Valid on the body activates @NotNull on UpsertPostReactionRequestDto
      mockMvc
          .perform(
              authed(put(reactionsUrl(POST_ID, COMMENT_ID)))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body(null)))
          .andExpect(status().isUnprocessableEntity());

      verify(commentReactionService, never()).upsertReaction(anyInt(), anyInt(), anyInt(), any());
    }

    @Test
    @DisplayName("shouldReturn400_whenReactionTypeIsNotAKnownValue")
    void shouldReturn400_whenReactionTypeIsNotAKnownValue() throws Exception {
      // Given — an unparseable enum never reaches @Valid; Jackson fails first
      mockMvc
          .perform(
              authed(put(reactionsUrl(POST_ID, COMMENT_ID)))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body("APPLAUSE")))
          .andExpect(status().isBadRequest());

      verify(commentReactionService, never()).upsertReaction(anyInt(), anyInt(), anyInt(), any());
    }

    @Test
    @DisplayName("shouldReturn401_whenCallerIsAnonymous")
    void shouldReturn401_whenCallerIsAnonymous() throws Exception {
      // Given — nested under /v1/api/posts, but the guest-readable entries in SecurityConfig are
      // pinned to GET on one segment, so this route falls through to anyRequest().authenticated()
      mockMvc
          .perform(
              put(reactionsUrl(POST_ID, COMMENT_ID))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body("LIKE")))
          .andExpect(status().isUnauthorized());

      verify(commentReactionService, never()).upsertReaction(anyInt(), anyInt(), anyInt(), any());
    }

    @Test
    @DisplayName("shouldReturn405_whenPostIsUsedInsteadOfPut")
    void shouldReturn405_whenPostIsUsedInsteadOfPut() throws Exception {
      // Given — the original frontend plan proposed POST; the implementation uses PUT to match
      // PostReactionController, so this is the first thing a client written to that plan hits
      mockMvc
          .perform(
              authed(post(reactionsUrl(POST_ID, COMMENT_ID)))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body("LIKE")))
          .andExpect(status().isMethodNotAllowed());
    }

    @Test
    @DisplayName("shouldReturn404_whenCommentDoesNotExist")
    void shouldReturn404_whenCommentDoesNotExist() throws Exception {
      // Given
      doThrow(new NotFoundException("Comment not found with ID: 999"))
          .when(commentReactionService)
          .upsertReaction(anyInt(), anyInt(), eq(999), any());

      // When / Then
      mockMvc
          .perform(
              authed(put(reactionsUrl(POST_ID, 999)))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body("LIKE")))
          .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("shouldReturn403_whenCallerIsBanned")
    void shouldReturn403_whenCallerIsBanned() throws Exception {
      // Given
      doThrow(new UserBannedException(OffsetDateTime.now().plusDays(2)))
          .when(commentReactionService)
          .upsertReaction(anyInt(), anyInt(), anyInt(), any());

      // When / Then
      mockMvc
          .perform(
              authed(put(reactionsUrl(POST_ID, COMMENT_ID)))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body("LIKE")))
          .andExpect(status().isForbidden());
    }
  }

  // =====================================================================
  // DELETE /v1/api/posts/{postId}/comments/{commentId}/reactions
  // =====================================================================

  @Nested
  @DisplayName("DELETE /v1/api/posts/{postId}/comments/{commentId}/reactions")
  class RemoveReactionTests {

    @Test
    @DisplayName("shouldReturn200AndPassTheCallerId_whenReactionExists_happyPath")
    void shouldReturn200AndPassTheCallerId_whenReactionExists_happyPath() throws Exception {
      // When / Then
      mockMvc.perform(authed(delete(reactionsUrl(POST_ID, COMMENT_ID)))).andExpect(status().isOk());

      verify(commentReactionService).removeReaction(currentUser.getId(), POST_ID, COMMENT_ID);
    }

    @Test
    @DisplayName("shouldReturn404_whenNoReactionWasEverRecorded")
    void shouldReturn404_whenNoReactionWasEverRecorded() throws Exception {
      // Given
      doThrow(new NotFoundException("Reaction not found for this comment"))
          .when(commentReactionService)
          .removeReaction(anyInt(), anyInt(), anyInt());

      // When / Then
      mockMvc
          .perform(authed(delete(reactionsUrl(POST_ID, COMMENT_ID))))
          .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("shouldReturn401_whenCallerIsAnonymous")
    void shouldReturn401_whenCallerIsAnonymous() throws Exception {
      // When / Then
      mockMvc
          .perform(delete(reactionsUrl(POST_ID, COMMENT_ID)))
          .andExpect(status().isUnauthorized());

      verify(commentReactionService, never()).removeReaction(anyInt(), anyInt(), anyInt());
    }
  }
}
