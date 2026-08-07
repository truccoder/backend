package com.socialapp.posts.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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

import com.socialapp.common.exception.ForbiddenException;
import com.socialapp.common.exception.NotFoundException;
import com.socialapp.moderation.exception.UserBannedException;
import com.socialapp.moderation.service.BanDetailsService;
import com.socialapp.posts.dto.CommentResponseDto;
import com.socialapp.posts.service.CommentService;
import com.socialapp.security.config.CustomAccessDeniedHandler;
import com.socialapp.security.config.CustomAuthenticationEntryPoint;
import com.socialapp.security.config.JwtAuthenticationFilter;
import com.socialapp.security.config.JwtProvider;
import com.socialapp.security.config.SecurityConfig;
import com.socialapp.security.entity.UserEntity;
import com.socialapp.security.entity.UserRole;
import com.socialapp.security.repository.UserRepository;

/**
 * System/API integration tests for {@link CommentController}, per ISTQB CTFL v4.0.1 Section
 * 2.2.2, using {@code @WebMvcTest} + {@code MockMvc}. {@link CommentService} is mocked.
 *
 * <p>Blank content is now caught by {@code @Valid} against the {@code @NotBlank} on both request
 * DTOs, so it 422s at the controller and never reaches the service. Ban status and ownership have
 * no DTO-level expression and are still enforced in {@code CommentService}, surfacing via {@link
 * ExceptionMappingTests} against the mocked service.
 */
@WebMvcTest(CommentController.class)
@Import({
  SecurityConfig.class,
  CustomAuthenticationEntryPoint.class,
  CustomAccessDeniedHandler.class,
  JwtAuthenticationFilter.class
})
class CommentControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockBean private CommentService commentService;
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
    currentUser.setEmail("commenter@example.com");
    currentUser.setUsername("commenter");
    currentUser.setFullName("Commenter One");
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

  private static String commentsUrl(Integer postId) {
    return "/v1/api/posts/" + postId + "/comments";
  }

  // =====================================================================
  // GET /v1/api/posts/{postId}/comments
  // =====================================================================

  @Nested
  @DisplayName("GET /v1/api/posts/{postId}/comments")
  class GetCommentsTests {

    @Test
    @DisplayName("shouldReturn200WithComments_whenPostExists_happyPath")
    void shouldReturn200WithComments_whenPostExists_happyPath() throws Exception {
      // Given
      CommentResponseDto comment =
          CommentResponseDto.builder()
              .id(10)
              .postId(1)
              .authorId(2)
              .authorFullName("Author Two")
              .authorProfilePictureUrl("http://cdn.example.com/avatar2.png")
              .content("First!")
              .parentId(null)
              .createdAt(OffsetDateTime.parse("2026-01-01T00:00:00Z"))
              .updatedAt(OffsetDateTime.parse("2026-01-01T00:00:00Z"))
              .build();
      // The authenticated user (id 1) is now passed through as the viewer, so that comments by
      // someone they have blocked can be filtered out.
      when(commentService.getComments(1, 1)).thenReturn(List.of(comment));

      // When / Then
      mockMvc
          .perform(authed(get(commentsUrl(1))))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$[0].id").value(10))
          .andExpect(jsonPath("$[0].authorId").value(2))
          .andExpect(jsonPath("$[0].authorFullName").value("Author Two"))
          .andExpect(jsonPath("$[0].content").value("First!"));
    }

    @Test
    @DisplayName("shouldReturn404_whenPostDoesNotExist")
    void shouldReturn404_whenPostDoesNotExist() throws Exception {
      // Given
      doThrow(new NotFoundException("Post not found with ID: 999"))
          .when(commentService)
          .getComments(1, 999);

      // When / Then
      mockMvc
          .perform(authed(get(commentsUrl(999))))
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.message").value("Post not found with ID: 999"));
    }

    @Test
    @DisplayName("shouldReturn401_whenCalledWithNoAuthorizationHeader")
    void shouldReturn401_whenCalledWithNoAuthorizationHeader() throws Exception {
      // When / Then
      mockMvc.perform(get(commentsUrl(1))).andExpect(status().isUnauthorized());
    }
  }

  // =====================================================================
  // POST /v1/api/posts/{postId}/comments
  // =====================================================================

  @Nested
  @DisplayName("POST /v1/api/posts/{postId}/comments")
  class CreateCommentTests {

    @Test
    @DisplayName("shouldReturn200_whenContentIsValid_happyPath")
    void shouldReturn200_whenContentIsValid_happyPath() throws Exception {
      // Given
      String requestJson =
          """
          { "content": "Nice post!" }
          """;

      // When / Then
      mockMvc
          .perform(
              authed(post(commentsUrl(1)))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(requestJson))
          .andExpect(status().isOk());

      verify(commentService).createComment(eq(currentUser.getId()), eq(1), any());
    }

    @Test
    @DisplayName("shouldReturn422_whenContentIsBlank")
    void shouldReturn422_whenContentIsBlank() throws Exception {
      // Given
      String requestJson =
          """
          { "content": "" }
          """;

      // When / Then — @Valid rejects it at the controller; the service is never reached.
      mockMvc
          .perform(
              authed(post(commentsUrl(1)))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(requestJson))
          .andExpect(status().isUnprocessableEntity());

      verifyNoInteractions(commentService);
    }

    @Test
    @DisplayName("shouldReturn404_whenPostDoesNotExist")
    void shouldReturn404_whenPostDoesNotExist() throws Exception {
      // Given
      doThrow(new NotFoundException("Post not found with ID: 999"))
          .when(commentService)
          .createComment(anyInt(), eq(999), any());
      String requestJson =
          """
          { "content": "Nice post!" }
          """;

      // When / Then
      mockMvc
          .perform(
              authed(post(commentsUrl(999)))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(requestJson))
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.message").value("Post not found with ID: 999"));
    }

    @Test
    @DisplayName("shouldReturn403_whenUserIsBannedFromCommenting")
    void shouldReturn403_whenUserIsBannedFromCommenting() throws Exception {
      // Given
      doThrow(new UserBannedException(OffsetDateTime.parse("2026-12-31T00:00:00Z")))
          .when(commentService)
          .createComment(anyInt(), anyInt(), any());
      String requestJson =
          """
          { "content": "Nice post!" }
          """;

      // When / Then
      mockMvc
          .perform(
              authed(post(commentsUrl(1)))
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
          { "content": "Nice post!" }
          """;

      // When / Then — EP: postId must be an Integer
      mockMvc
          .perform(
              authed(post("/v1/api/posts/not-a-number/comments"))
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
          { "content": "Nice post!" }
          """;

      // When / Then
      mockMvc
          .perform(
              post(commentsUrl(1)).contentType(MediaType.APPLICATION_JSON).content(requestJson))
          .andExpect(status().isUnauthorized());
    }
  }

  // =====================================================================
  // PUT /v1/api/posts/{postId}/comments/{commentId}
  // =====================================================================

  @Nested
  @DisplayName("PUT /v1/api/posts/{postId}/comments/{commentId}")
  class UpdateCommentTests {

    @Test
    @DisplayName("shouldReturn200_whenCallerIsTheAuthor_happyPath")
    void shouldReturn200_whenCallerIsTheAuthor_happyPath() throws Exception {
      // Given
      String requestJson =
          """
          { "content": "Edited comment" }
          """;

      // When / Then
      mockMvc
          .perform(
              authed(put(commentsUrl(1) + "/10"))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(requestJson))
          .andExpect(status().isOk());

      verify(commentService).updateComment(eq(currentUser.getId()), eq(1), eq(10), any());
    }

    @Test
    @DisplayName("shouldReturn403_whenCallerIsNotTheAuthor")
    void shouldReturn403_whenCallerIsNotTheAuthor() throws Exception {
      // Given
      doThrow(new ForbiddenException("Only the author can modify this comment"))
          .when(commentService)
          .updateComment(anyInt(), anyInt(), anyInt(), any());
      String requestJson =
          """
          { "content": "Trying to edit someone else's comment" }
          """;

      // When / Then
      mockMvc
          .perform(
              authed(put(commentsUrl(1) + "/10"))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(requestJson))
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.message").value("Only the author can modify this comment"));
    }

    @Test
    @DisplayName("shouldReturn404_whenCommentDoesNotExist")
    void shouldReturn404_whenCommentDoesNotExist() throws Exception {
      // Given
      doThrow(new NotFoundException("Comment not found with ID: 999"))
          .when(commentService)
          .updateComment(anyInt(), anyInt(), eq(999), any());
      String requestJson =
          """
          { "content": "Edited comment" }
          """;

      // When / Then
      mockMvc
          .perform(
              authed(put(commentsUrl(1) + "/999"))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(requestJson))
          .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("shouldReturn401_whenCalledWithNoAuthorizationHeader")
    void shouldReturn401_whenCalledWithNoAuthorizationHeader() throws Exception {
      // Given
      String requestJson =
          """
          { "content": "Edited comment" }
          """;

      // When / Then
      mockMvc
          .perform(
              put(commentsUrl(1) + "/10")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(requestJson))
          .andExpect(status().isUnauthorized());
    }
  }

  // =====================================================================
  // DELETE /v1/api/posts/{postId}/comments/{commentId}
  // =====================================================================

  @Nested
  @DisplayName("DELETE /v1/api/posts/{postId}/comments/{commentId}")
  class DeleteCommentTests {

    @Test
    @DisplayName("shouldReturn200_whenCallerIsTheAuthor_happyPath")
    void shouldReturn200_whenCallerIsTheAuthor_happyPath() throws Exception {
      // When / Then
      mockMvc.perform(authed(delete(commentsUrl(1) + "/10"))).andExpect(status().isOk());

      verify(commentService).deleteComment(currentUser.getId(), 1, 10);
    }

    @Test
    @DisplayName("shouldReturn403_whenCallerIsNotTheAuthor")
    void shouldReturn403_whenCallerIsNotTheAuthor() throws Exception {
      // Given
      doThrow(new ForbiddenException("Only the author can modify this comment"))
          .when(commentService)
          .deleteComment(anyInt(), anyInt(), anyInt());

      // When / Then
      mockMvc.perform(authed(delete(commentsUrl(1) + "/10"))).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("shouldReturn401_whenCalledWithNoAuthorizationHeader")
    void shouldReturn401_whenCalledWithNoAuthorizationHeader() throws Exception {
      // When / Then
      mockMvc.perform(delete(commentsUrl(1) + "/10")).andExpect(status().isUnauthorized());
    }
  }
}
