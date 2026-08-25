package com.socialapp.posts.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.socialapp.common.exception.NotFoundException;
import com.socialapp.common.utils.Constants;
import com.socialapp.moderation.exception.UserBannedException;
import com.socialapp.moderation.service.BanDetailsService;
import com.socialapp.posts.dto.ReactorPageResponseDto;
import com.socialapp.posts.entity.enums.ReactionType;
import com.socialapp.posts.service.CommentReactionService;
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
  // GET /v1/api/posts/{postId}/comments/{commentId}/reactions
  // =====================================================================

  @Nested
  @DisplayName("GET /v1/api/posts/{postId}/comments/{commentId}/reactions")
  class GetReactorsTests {

    private ReactorPageResponseDto page() {
      PublicUserResponse reactor =
          new PublicUserResponse(7, "ada", "Ada Lovelace", null, 120, null);
      return new ReactorPageResponseDto(List.of(reactor), 7, true, 3L);
    }

    @Test
    @DisplayName("shouldReturn200AndTheReactorPage_happyPath")
    void shouldReturn200AndTheReactorPage() throws Exception {
      // Given — the read half a comment never had. A post could always be asked who reacted to
      // it; a comment could be reacted to and never queried.
      when(commentReactionService.getReactors(
              eq(currentUser.getId()), eq(POST_ID), eq(COMMENT_ID), isNull(), isNull(), eq(20)))
          .thenReturn(page());

      // When / Then
      mockMvc
          .perform(authed(get(reactionsUrl(POST_ID, COMMENT_ID))))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.reactors[0].username").value("ada"))
          .andExpect(jsonPath("$.totalCount").value(3))
          .andExpect(jsonPath("$.hasMore").value(true))
          .andExpect(jsonPath("$.nextCursor").value(7));
    }

    @Test
    @DisplayName("shouldNeverExposeAnEmailAddressInTheReactorList_security")
    void shouldNotExposeEmails() throws Exception {
      // Given — this list is readable by anyone who can read the post, so the shape matters:
      // PublicUserResponse has no email field, UserResponse does
      when(commentReactionService.getReactors(any(), any(), any(), any(), any(), anyInt()))
          .thenReturn(page());

      // When / Then
      mockMvc
          .perform(authed(get(reactionsUrl(POST_ID, COMMENT_ID))))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.reactors[0].email").doesNotExist());
    }

    @Test
    @DisplayName("shouldPassTheTypeFilterAndCursorThrough")
    void shouldPassFilterAndCursor() throws Exception {
      // Given
      when(commentReactionService.getReactors(
              eq(currentUser.getId()),
              eq(POST_ID),
              eq(COMMENT_ID),
              eq(ReactionType.CLAP),
              eq(12),
              eq(5)))
          .thenReturn(page());

      // When / Then
      mockMvc
          .perform(
              authed(get(reactionsUrl(POST_ID, COMMENT_ID)))
                  .param("type", "CLAP")
                  .param("cursor", "12")
                  .param("limit", "5"))
          .andExpect(status().isOk());

      verify(commentReactionService)
          .getReactors(currentUser.getId(), POST_ID, COMMENT_ID, ReactionType.CLAP, 12, 5);
    }

    @Test
    @DisplayName("shouldReturn400_whenTypeIsNotAReactionType")
    void shouldReturn400_whenTypeIsUnknown() throws Exception {
      // When / Then — EP: the filter is an enum, and an unknown name is the caller's mistake
      mockMvc
          .perform(authed(get(reactionsUrl(POST_ID, COMMENT_ID))).param("type", "SHRUG"))
          .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("shouldReturn400_whenLimitExceedsTheCap_boundary")
    void shouldReturn400_whenLimitTooLarge() throws Exception {
      // When / Then — BVA on @Max(Constants.MAX_PAGINATION_PAGE_SIZE); an uncapped limit is a
      // way to pull every reactor profile in one request
      mockMvc
          .perform(
              authed(get(reactionsUrl(POST_ID, COMMENT_ID)))
                  .param("limit", String.valueOf(Constants.MAX_PAGINATION_PAGE_SIZE + 1)))
          .andExpect(status().isBadRequest());

      verify(commentReactionService, never())
          .getReactors(any(), any(), any(), any(), any(), anyInt());
    }

    @Test
    @DisplayName("shouldReturn404_whenThePostIsNotVisibleToTheCaller_security")
    void shouldReturn404_whenPostNotVisible() throws Exception {
      // Given — without the visibility gate this endpoint would enumerate everyone who reacted
      // to a comment on a FRIENDS-only post, which is that post's audience list in all but name
      when(commentReactionService.getReactors(any(), any(), any(), any(), any(), anyInt()))
          .thenThrow(new NotFoundException("Post not found with ID: " + POST_ID));

      // When / Then
      mockMvc
          .perform(authed(get(reactionsUrl(POST_ID, COMMENT_ID))))
          .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("shouldReturn401_whenCallerIsAnonymous")
    void shouldReturn401_whenCallerIsAnonymous() throws Exception {
      // When / Then — the guest-readable entries in SecurityConfig are pinned to single-segment
      // GETs, so this nested route stays closed even though the post above it is open
      mockMvc.perform(get(reactionsUrl(POST_ID, COMMENT_ID))).andExpect(status().isUnauthorized());

      verify(commentReactionService, never())
          .getReactors(any(), any(), any(), any(), any(), anyInt());
    }
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
