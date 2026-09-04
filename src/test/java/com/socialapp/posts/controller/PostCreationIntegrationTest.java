package com.socialapp.posts.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socialapp.AbstractIntegrationTest;
import com.socialapp.moderation.enums.ModerationStatus;
import com.socialapp.posts.dto.CreatePostRequestDto;
import com.socialapp.posts.entity.PostEntity;
import com.socialapp.posts.entity.enums.PostVisibility;
import com.socialapp.posts.repository.PostRepository;
import com.socialapp.security.config.JwtProvider;
import com.socialapp.security.entity.UserEntity;
import com.socialapp.security.repository.UserRepository;

/**
 * End-to-end system test for the "create post" backbone flow: a real HTTP request through {@link
 * MockMvc}, the real Spring Security filter chain (JWT parsing + user lookup), the real {@code
 * PostService}, and a real Postgres database via Testcontainers (see {@link
 * AbstractIntegrationTest}) — nothing on the request path is mocked, unlike the {@code
 * @WebMvcTest}/Mockito-based {@link PostControllerTest} and {@code PostServiceTest} elsewhere in
 * this suite, which verify the HTTP layer and business logic in isolation from each other and
 * from the database.
 *
 * <p>{@code @Transactional} so each test rolls back its own Postgres writes — same isolation
 * pattern as {@code PostRepositoryTest}, needed because {@link AbstractIntegrationTest}'s
 * containers are started once and reused (never reset) across the whole test JVM run. MockMvc
 * dispatches in-process on the test thread (no real socket, regardless of {@code
 * webEnvironment}), so the controller's own transaction joins the test's and rolls back with it.
 */
@AutoConfigureMockMvc
@Transactional
class PostCreationIntegrationTest extends AbstractIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private UserRepository userRepository;
  @Autowired private PostRepository postRepository;
  @Autowired private JwtProvider jwtProvider;

  @Test
  @DisplayName(
      "POST /v1/api/posts should authenticate via JWT, persist the post to Postgres, and return"
          + " 201 with the new post id")
  void shouldCreateAndPersistPost_whenAuthenticatedUserSubmitsValidRequest() throws Exception {
    // Given: a real, persisted user and a real JWT issued for them by the app's own JwtProvider
    // (the same one JwtAuthenticationFilter uses to validate the Authorization header).
    UserEntity author = persistUser("post-flow-" + System.nanoTime() + "@example.com");
    String token = jwtProvider.generateAccessToken(author.getEmail(), null);

    CreatePostRequestDto request = new CreatePostRequestDto();
    request.setContent("Hello from the integration test suite!");
    request.setVisibility(PostVisibility.PUBLIC);

    // When
    mockMvc
        .perform(
            post("/v1/api/posts")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.postId").isNumber())
        .andExpect(jsonPath("$.moderationStatus").value("APPROVED"));

    // Then: the post actually landed in the database — not just a 201 from a mocked service.
    List<PostEntity> savedPosts =
        postRepository.findByAuthorIdAndModerationStatus(author.getId(), ModerationStatus.APPROVED);

    assertThat(savedPosts).hasSize(1);
    assertThat(savedPosts.get(0).getContent()).isEqualTo("Hello from the integration test suite!");
    assertThat(savedPosts.get(0).getVisibility()).isEqualTo(PostVisibility.PUBLIC);
    assertThat(savedPosts.get(0).getAuthorId()).isEqualTo(author.getId());
  }

  @Test
  @DisplayName("POST /v1/api/posts should reject an unauthenticated request with 401")
  void shouldRejectPostCreation_whenNoAuthorizationHeaderIsSent() throws Exception {
    // Given
    CreatePostRequestDto request = new CreatePostRequestDto();
    request.setContent("Should never be saved");
    request.setVisibility(PostVisibility.PUBLIC);

    // When / Then: SecurityConfig requires auth for /v1/api/posts, and with no principal set,
    // SecurityUtils.getCurrentUserId() never even runs — the filter chain rejects the request
    // before it reaches the controller.
    mockMvc
        .perform(
            post("/v1/api/posts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isUnauthorized());
  }

  private UserEntity persistUser(String email) {
    UserEntity user = new UserEntity();
    user.setEmail(email);
    user.setPassword("{noop}not-used-by-this-test");
    user.setUsername(email);
    user.setFullName("Post Flow Test User");
    user.setEmailVerified(true);
    return userRepository.save(user);
  }
}
