package com.socialapp.posts.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.socialapp.common.exception.ForbiddenException;
import com.socialapp.common.exception.NotFoundException;
import com.socialapp.common.exception.ValidationException;
import com.socialapp.moderation.enums.ViolationType;
import com.socialapp.moderation.exception.ContentViolationException;
import com.socialapp.moderation.exception.UserBannedException;
import com.socialapp.newsfeed.dto.FeedPostDataDto;
import com.socialapp.posts.dto.PostPageResponseDto;
import com.socialapp.posts.entity.enums.PostType;
import com.socialapp.posts.service.PostQueryService;
import com.socialapp.posts.service.PostService;
import com.socialapp.security.config.CustomAccessDeniedHandler;
import com.socialapp.security.config.CustomAuthenticationEntryPoint;
import com.socialapp.security.config.JwtAuthenticationFilter;
import com.socialapp.security.config.JwtProvider;
import com.socialapp.security.config.SecurityConfig;
import com.socialapp.security.entity.UserEntity;
import com.socialapp.security.entity.UserRole;
import com.socialapp.security.repository.UserRepository;

/**
 * System/API integration tests for {@link PostController}, per ISTQB CTFL v4.0.1 Section 2.2.2,
 * using {@code @WebMvcTest} + {@code MockMvc}. {@link PostService} is mocked.
 *
 * <p><b>No Bean Validation exists on this controller.</b> Unlike {@code AuthController}/{@code
 * ProfileController}, neither {@code createPost}/{@code updatePost} carry {@code @Valid}, and
 * {@link com.socialapp.posts.dto.CreatePostRequestDto}/{@link
 * com.socialapp.posts.dto.UpdatePostRequestDto} have zero constraint annotations (no {@code
 * @NotBlank}, no {@code @Size}). There is therefore no 422 "Validation (EP/BVA)" section here —
 * confirmed by reading both DTOs, not assumed. All request-shape rules (event/book detail
 * completeness, tag limits, etc.) live in {@code PostService} instead, so they surface here only
 * as {@link ExceptionMappingTests} against the mocked service, not as Bean Validation failures.
 *
 * <p><b>Auth simulation:</b> same real-{@link JwtAuthenticationFilter} approach as {@code
 * ProfileControllerTest} — {@code SecurityUtils.getCurrentUserId()} requires a {@link UserEntity}
 * principal, which {@code @WithMockUser} does not provide, so authenticated tests stub {@link
 * JwtProvider}/{@link UserRepository} instead.
 */
@WebMvcTest(PostController.class)
@Import({
  SecurityConfig.class,
  CustomAuthenticationEntryPoint.class,
  CustomAccessDeniedHandler.class,
  JwtAuthenticationFilter.class
})
class PostControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockBean private PostService postService;
  @MockBean private PostQueryService postQueryService;
  @MockBean private JwtProvider jwtProvider;
  @MockBean private UserRepository userRepository;

  private static final String POSTS_URL = "/v1/api/posts";
  private static final String BOOK_POSTS_URL = "/v1/api/posts/books";
  private static final String VALID_TOKEN = "a-valid-jwt-token";

  private UserEntity currentUser;

  @BeforeEach
  void setUpDefaultUser() {
    currentUser = new UserEntity();
    currentUser.setId(1);
    currentUser.setEmail("author@example.com");
    currentUser.setUsername("author");
    currentUser.setFullName("Author");
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

  // =====================================================================
  // POST /v1/api/posts
  // =====================================================================

  @Nested
  @DisplayName("POST /v1/api/posts")
  class CreatePostTests {

    @Test
    @DisplayName("shouldReturn200_whenPayloadIsValid_happyPath")
    void shouldReturn200_whenPayloadIsValid_happyPath() throws Exception {
      // Given
      String requestJson =
          """
          { "content": "Hello world", "visibility": "PUBLIC" }
          """;

      // When / Then
      mockMvc
          .perform(
              authed(post(POSTS_URL)).contentType(MediaType.APPLICATION_JSON).content(requestJson))
          .andExpect(status().isOk());

      verify(postService).createPost(eq(currentUser.getId()), any());
    }

    @Test
    @DisplayName("shouldReturn401_whenCalledWithNoAuthorizationHeader")
    void shouldReturn401_whenCalledWithNoAuthorizationHeader() throws Exception {
      // Given
      String requestJson =
          """
          { "content": "Hello world" }
          """;

      // When / Then
      mockMvc
          .perform(post(POSTS_URL).contentType(MediaType.APPLICATION_JSON).content(requestJson))
          .andExpect(status().isUnauthorized());
    }
  }

  // =====================================================================
  // POST /v1/api/posts/books
  // =====================================================================

  @Nested
  @DisplayName("POST /v1/api/posts/books")
  class CreateBookPostTests {

    @Test
    @DisplayName("shouldReturn200_whenMetadataAndFileAreValid_happyPath")
    void shouldReturn200_whenMetadataAndFileAreValid_happyPath() throws Exception {
      // Given
      MockMultipartFile metadata =
          new MockMultipartFile(
              "metadata",
              "metadata",
              MediaType.APPLICATION_JSON_VALUE,
              "{\"content\": \"Check out my book\"}".getBytes());
      MockMultipartFile bookFile =
          new MockMultipartFile(
              "file", "book.pdf", MediaType.APPLICATION_PDF_VALUE, new byte[] {1, 2, 3});

      // When / Then
      mockMvc
          .perform(authed(multipart(BOOK_POSTS_URL).file(metadata).file(bookFile)))
          .andExpect(status().isOk());

      verify(postService).createBookPost(eq(currentUser.getId()), any(), any(), any());
    }

    @Test
    @DisplayName("shouldReturn401_whenCalledWithNoAuthorizationHeader")
    void shouldReturn401_whenCalledWithNoAuthorizationHeader() throws Exception {
      // Given
      MockMultipartFile metadata =
          new MockMultipartFile(
              "metadata",
              "metadata",
              MediaType.APPLICATION_JSON_VALUE,
              "{\"content\": \"x\"}".getBytes());
      MockMultipartFile bookFile =
          new MockMultipartFile(
              "file", "book.pdf", MediaType.APPLICATION_PDF_VALUE, new byte[] {1});

      // When / Then
      mockMvc
          .perform(multipart(BOOK_POSTS_URL).file(metadata).file(bookFile))
          .andExpect(status().isUnauthorized());
    }
  }

  // =====================================================================
  // PUT /v1/api/posts/{postId}
  // =====================================================================

  @Nested
  @DisplayName("PUT /v1/api/posts/{postId}")
  class UpdatePostTests {

    @Test
    @DisplayName("shouldReturn200_whenPayloadIsValid_happyPath")
    void shouldReturn200_whenPayloadIsValid_happyPath() throws Exception {
      // Given
      String requestJson =
          """
          { "content": "Updated content" }
          """;

      // When / Then
      mockMvc
          .perform(
              authed(put(POSTS_URL + "/1"))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(requestJson))
          .andExpect(status().isOk());

      verify(postService).updatePost(eq(currentUser.getId()), eq(1), any());
    }

    @Test
    @DisplayName("shouldReturn400_whenPostIdPathVariableIsNotANumber")
    void shouldReturn400_whenPostIdPathVariableIsNotANumber() throws Exception {
      // Given — EP: postId must be an Integer; "abc" is outside that partition
      String requestJson =
          """
          { "content": "Updated content" }
          """;

      // When / Then
      mockMvc
          .perform(
              authed(put(POSTS_URL + "/abc"))
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
          { "content": "Updated content" }
          """;

      // When / Then
      mockMvc
          .perform(
              put(POSTS_URL + "/1").contentType(MediaType.APPLICATION_JSON).content(requestJson))
          .andExpect(status().isUnauthorized());
    }
  }

  // =====================================================================
  // DELETE /v1/api/posts/{postId}
  // =====================================================================

  @Nested
  @DisplayName("DELETE /v1/api/posts/{postId}")
  class DeletePostTests {

    @Test
    @DisplayName("shouldReturn200_whenCallerIsTheAuthor_happyPath")
    void shouldReturn200_whenCallerIsTheAuthor_happyPath() throws Exception {
      // When / Then
      mockMvc.perform(authed(delete(POSTS_URL + "/1"))).andExpect(status().isOk());

      verify(postService).deletePost(eq(currentUser.getId()), eq(1));
    }

    @Test
    @DisplayName("shouldReturn403_whenCallerIsNotTheAuthor")
    void shouldReturn403_whenCallerIsNotTheAuthor() throws Exception {
      // Given
      doThrow(new ForbiddenException("Only the author can modify this post"))
          .when(postService)
          .deletePost(anyInt(), anyInt());

      // When / Then
      mockMvc
          .perform(authed(delete(POSTS_URL + "/1")))
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.message").value("Only the author can modify this post"));
    }

    @Test
    @DisplayName("shouldReturn401_whenCalledWithNoAuthorizationHeader")
    void shouldReturn401_whenCalledWithNoAuthorizationHeader() throws Exception {
      // When / Then
      mockMvc.perform(delete(POSTS_URL + "/1")).andExpect(status().isUnauthorized());
    }
  }

  // =====================================================================
  // POST /v1/api/posts/{postId}/qna/accept-answer/{commentId}
  // =====================================================================

  @Nested
  @DisplayName("POST /v1/api/posts/{postId}/qna/accept-answer/{commentId}")
  class AcceptAnswerTests {

    @Test
    @DisplayName("shouldReturn200_whenCallerIsTheAuthor_happyPath")
    void shouldReturn200_whenCallerIsTheAuthor_happyPath() throws Exception {
      // When / Then
      mockMvc
          .perform(authed(post(POSTS_URL + "/1/qna/accept-answer/5")))
          .andExpect(status().isOk());

      verify(postService).acceptAnswer(eq(currentUser.getId()), eq(1), eq(5));
    }

    @Test
    @DisplayName("shouldReturn403_whenCallerIsNotTheAuthor")
    void shouldReturn403_whenCallerIsNotTheAuthor() throws Exception {
      // Given
      doThrow(new ForbiddenException("Only the author can modify this post"))
          .when(postService)
          .acceptAnswer(anyInt(), anyInt(), anyInt());

      // When / Then
      mockMvc
          .perform(authed(post(POSTS_URL + "/1/qna/accept-answer/5")))
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.message").value("Only the author can modify this post"));
    }

    @Test
    @DisplayName("shouldReturn400_whenPostIsNotQna")
    void shouldReturn400_whenPostIsNotQna() throws Exception {
      // Given
      doThrow(new ValidationException("Only QNA posts can have an accepted answer"))
          .when(postService)
          .acceptAnswer(anyInt(), anyInt(), anyInt());

      // When / Then
      mockMvc
          .perform(authed(post(POSTS_URL + "/1/qna/accept-answer/5")))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.message").value("Only QNA posts can have an accepted answer"));
    }

    @Test
    @DisplayName("shouldReturn401_whenCalledWithNoAuthorizationHeader")
    void shouldReturn401_whenCalledWithNoAuthorizationHeader() throws Exception {
      // When / Then
      mockMvc
          .perform(post(POSTS_URL + "/1/qna/accept-answer/5"))
          .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("shouldReturn405_whenCalledWithPatch")
    void shouldReturn405_whenCalledWithPatch() throws Exception {
      // Given — this endpoint was PATCH until 39b5666 standardised the API layer onto POST.
      // Clients still on the old verb must get 405, not the 500 the generic handler used to
      // produce; this test pins that verb change so it cannot regress silently again.

      // When / Then
      mockMvc
          .perform(authed(patch(POSTS_URL + "/1/qna/accept-answer/5")))
          .andExpect(status().isMethodNotAllowed());
    }
  }

  // =====================================================================
  // DELETE /v1/api/posts/{postId}/qna/accept-answer
  // =====================================================================

  @Nested
  @DisplayName("DELETE /v1/api/posts/{postId}/qna/accept-answer")
  class UnacceptAnswerTests {

    @Test
    @DisplayName("shouldReturn200_whenCallerIsTheAuthor_happyPath")
    void shouldReturn200_whenCallerIsTheAuthor_happyPath() throws Exception {
      // When / Then — no comment id in the path: a post has at most one accepted answer
      mockMvc
          .perform(authed(delete(POSTS_URL + "/1/qna/accept-answer")))
          .andExpect(status().isOk());

      verify(postService).unacceptAnswer(eq(currentUser.getId()), eq(1));
    }

    @Test
    @DisplayName("shouldReturn403_whenCallerIsNotTheAuthor")
    void shouldReturn403_whenCallerIsNotTheAuthor() throws Exception {
      // Given
      doThrow(new ForbiddenException("Only the author can modify this post"))
          .when(postService)
          .unacceptAnswer(anyInt(), anyInt());

      // When / Then
      mockMvc
          .perform(authed(delete(POSTS_URL + "/1/qna/accept-answer")))
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.message").value("Only the author can modify this post"));
    }

    @Test
    @DisplayName("shouldReturn400_whenNoAnswerHasBeenAccepted")
    void shouldReturn400_whenNoAnswerHasBeenAccepted() throws Exception {
      // Given
      doThrow(new ValidationException("No answer has been accepted for this post"))
          .when(postService)
          .unacceptAnswer(anyInt(), anyInt());

      // When / Then
      mockMvc
          .perform(authed(delete(POSTS_URL + "/1/qna/accept-answer")))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.message").value("No answer has been accepted for this post"));
    }

    @Test
    @DisplayName("shouldReturn400_whenPostIdPathVariableIsNotANumber")
    void shouldReturn400_whenPostIdPathVariableIsNotANumber() throws Exception {
      // When / Then
      mockMvc
          .perform(authed(delete(POSTS_URL + "/not-a-number/qna/accept-answer")))
          .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("shouldReturn401_whenCalledWithNoAuthorizationHeader")
    void shouldReturn401_whenCalledWithNoAuthorizationHeader() throws Exception {
      // When / Then
      mockMvc
          .perform(delete(POSTS_URL + "/1/qna/accept-answer"))
          .andExpect(status().isUnauthorized());
    }
  }

  // =====================================================================
  // Exception mapping (Service -> GlobalExceptionHandler)
  // =====================================================================

  @Nested
  @DisplayName("Exception mapping (Service -> GlobalExceptionHandler)")
  class ExceptionMappingTests {

    @Test
    @DisplayName("shouldReturn400_whenCreatePostThrowsValidationException")
    void shouldReturn400_whenCreatePostThrowsValidationException() throws Exception {
      // Given — creating a BOOK-type post via the plain /posts endpoint is rejected
      doThrow(
              new ValidationException(
                  "Use POST /v1/api/posts/books to create a post with an attached book"))
          .when(postService)
          .createPost(anyInt(), any());
      String requestJson = "{ \"content\": \"x\", \"postType\": \"" + PostType.BOOK + "\" }";

      // When / Then
      mockMvc
          .perform(
              authed(post(POSTS_URL)).contentType(MediaType.APPLICATION_JSON).content(requestJson))
          .andExpect(status().isBadRequest())
          .andExpect(
              jsonPath("$.message")
                  .value("Use POST /v1/api/posts/books to create a post with an attached book"));
    }

    @Test
    @DisplayName("shouldReturn400_whenCreatePostThrowsContentViolationException")
    void shouldReturn400_whenCreatePostThrowsContentViolationException() throws Exception {
      // Given
      doThrow(new ContentViolationException(List.of(ViolationType.SPAM)))
          .when(postService)
          .createPost(anyInt(), any());
      String requestJson =
          """
          { "content": "buy now buy now buy now" }
          """;

      // When / Then
      mockMvc
          .perform(
              authed(post(POSTS_URL)).contentType(MediaType.APPLICATION_JSON).content(requestJson))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.error").value("Content Violation"));
    }

    @Test
    @DisplayName("shouldReturn403_whenCreatePostThrowsUserBannedException")
    void shouldReturn403_whenCreatePostThrowsUserBannedException() throws Exception {
      // Given — content-creation ban (moderation), distinct from the account-level ban
      // JwtAuthenticationFilter itself intercepts
      doThrow(new UserBannedException(OffsetDateTime.parse("2026-12-31T00:00:00Z")))
          .when(postService)
          .createPost(anyInt(), any());
      String requestJson =
          """
          { "content": "Hello" }
          """;

      // When / Then
      mockMvc
          .perform(
              authed(post(POSTS_URL)).contentType(MediaType.APPLICATION_JSON).content(requestJson))
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.error").value("Account Restricted"));
    }

    @Test
    @DisplayName("shouldReturn403_whenUpdatePostThrowsForbiddenExceptionForNonAuthor")
    void shouldReturn403_whenUpdatePostThrowsForbiddenExceptionForNonAuthor() throws Exception {
      // Given
      doThrow(new ForbiddenException("Only the author can modify this post"))
          .when(postService)
          .updatePost(anyInt(), anyInt(), any());
      String requestJson =
          """
          { "content": "trying to edit someone else's post" }
          """;

      // When / Then
      mockMvc
          .perform(
              authed(put(POSTS_URL + "/1"))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(requestJson))
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.message").value("Only the author can modify this post"));
    }
  }

  // =====================================================================
  // GET /v1/api/posts/{postId}  and  GET /v1/api/posts/public
  // =====================================================================

  @Nested
  @DisplayName("GET /v1/api/posts/{postId}")
  class GetPostTests {

    @Test
    @DisplayName("shouldReturn200AndTheFeedShapedPost_happyPath")
    void shouldReturnPost() throws Exception {
      // Given
      when(postQueryService.getPost(currentUser.getId(), 7))
          .thenReturn(FeedPostDataDto.builder().postId(7).authorId(2).content("hello").build());

      // When / Then
      mockMvc
          .perform(authed(get(POSTS_URL + "/7")))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.postId").value(7))
          .andExpect(jsonPath("$.content").value("hello"));
    }

    @Test
    @DisplayName("shouldReturn404_notForbidden_whenTheViewerMayNotSeeThePost")
    void shouldReturn404ForInvisiblePost() throws Exception {
      // Given: a 403 here would confirm the post exists, which is the fact being withheld
      when(postQueryService.getPost(currentUser.getId(), 7))
          .thenThrow(new NotFoundException("Post not found with ID: 7"));

      // When / Then
      mockMvc.perform(authed(get(POSTS_URL + "/7"))).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("shouldReturn200_whenCalledByAGuest_withTheStrangerVisibilityLevel")
    void shouldServeGuests() throws Exception {
      // Given: no Authorization header, so the viewer id handed to the service is null
      when(postQueryService.getPost(null, 7))
          .thenReturn(FeedPostDataDto.builder().postId(7).authorId(2).content("hello").build());

      // When / Then — a shared permalink has to open for someone who has never signed in
      mockMvc
          .perform(get(POSTS_URL + "/7"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.postId").value(7));
      verify(postQueryService).getPost(null, 7);
    }
  }

  @Nested
  @DisplayName("GET /v1/api/posts/public")
  class GetPublicFeedTests {

    @Test
    @DisplayName("shouldReturn200AndACursorPage_happyPath")
    void shouldReturnPage() throws Exception {
      // Given
      when(postQueryService.getPublicFeed(currentUser.getId(), null, 20))
          .thenReturn(
              new PostPageResponseDto(
                  java.util.List.of(FeedPostDataDto.builder().postId(9).authorId(3).build()),
                  9,
                  true));

      // When / Then
      mockMvc
          .perform(authed(get(POSTS_URL + "/public")))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.posts[0].postId").value(9))
          .andExpect(jsonPath("$.nextCursor").value(9))
          .andExpect(jsonPath("$.hasMore").value(true));
    }

    @Test
    @DisplayName("shouldRouteToTheDiscoveryFeed_notToAPostWhoseIdIsPublic")
    void shouldNotBeSwallowedByThePostIdRoute() throws Exception {
      // Given
      when(postQueryService.getPublicFeed(currentUser.getId(), null, 20))
          .thenReturn(new PostPageResponseDto(java.util.List.of(), null, false));

      // When
      mockMvc.perform(authed(get(POSTS_URL + "/public"))).andExpect(status().isOk());

      // Then — the literal segment must win over the {postId} template
      verify(postQueryService).getPublicFeed(currentUser.getId(), null, 20);
      verify(postQueryService, never()).getPost(any(), any());
    }

    @Test
    @DisplayName("shouldPassCursorAndLimitThrough_whenProvided")
    void shouldPassPagingThrough() throws Exception {
      // Given
      when(postQueryService.getPublicFeed(currentUser.getId(), 30, 5))
          .thenReturn(new PostPageResponseDto(java.util.List.of(), null, false));

      // When
      mockMvc
          .perform(authed(get(POSTS_URL + "/public")).param("cursor", "30").param("limit", "5"))
          .andExpect(status().isOk());

      // Then
      verify(postQueryService).getPublicFeed(currentUser.getId(), 30, 5);
    }

    @Test
    @DisplayName("shouldReturn200_whenCalledByAGuest_becauseThisIsAGuestsHomePage")
    void shouldServeGuests() throws Exception {
      // Given: no Authorization header
      when(postQueryService.getPublicFeed(null, null, 20))
          .thenReturn(new PostPageResponseDto(java.util.List.of(), null, false));

      // When / Then — /v1/api/feed is a per-user Redis fan-out and cannot be opened, so this is
      // the page an anonymous visitor lands on
      mockMvc.perform(get(POSTS_URL + "/public")).andExpect(status().isOk());
      verify(postQueryService).getPublicFeed(null, null, 20);
    }
  }
}
