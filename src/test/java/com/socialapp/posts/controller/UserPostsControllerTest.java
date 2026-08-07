package com.socialapp.posts.controller;

import static org.mockito.ArgumentMatchers.eq;
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

import com.socialapp.common.exception.NotFoundException;
import com.socialapp.moderation.service.BanDetailsService;
import com.socialapp.newsfeed.dto.FeedPostDataDto;
import com.socialapp.posts.dto.PostPageResponseDto;
import com.socialapp.posts.service.PostQueryService;
import com.socialapp.security.config.CustomAccessDeniedHandler;
import com.socialapp.security.config.CustomAuthenticationEntryPoint;
import com.socialapp.security.config.JwtAuthenticationFilter;
import com.socialapp.security.config.JwtProvider;
import com.socialapp.security.config.SecurityConfig;
import com.socialapp.security.entity.UserEntity;
import com.socialapp.security.entity.UserRole;
import com.socialapp.security.repository.UserRepository;

/**
 * System/API integration tests for {@link UserPostsController}, per ISTQB CTFL v4.0.1 Section
 * 2.2.2, using {@code @WebMvcTest} + {@code MockMvc}. {@link PostQueryService} is mocked; the
 * relationship rules it applies are covered in {@code PostQueryServiceTest} and {@code
 * PostVisibilityServiceTest}.
 */
@WebMvcTest(UserPostsController.class)
@Import({
  SecurityConfig.class,
  CustomAuthenticationEntryPoint.class,
  CustomAccessDeniedHandler.class,
  JwtAuthenticationFilter.class
})
class UserPostsControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockBean private PostQueryService postQueryService;
  @MockBean private JwtProvider jwtProvider;

  @MockBean
  private BanDetailsService
      banDetailsService; // JwtAuthenticationFilter builds the banned-account 403 through it

  @MockBean private UserRepository userRepository;

  private static final String VALID_TOKEN = "a-valid-jwt-token";
  private static final Integer AUTHOR_ID = 42;

  private UserEntity currentUser;

  @BeforeEach
  void setUpDefaultUser() {
    currentUser = new UserEntity();
    currentUser.setId(1);
    currentUser.setEmail("viewer@example.com");
    currentUser.setUsername("viewer");
    currentUser.setRole(UserRole.USER);
    currentUser.setEmailVerified(true);
    when(jwtProvider.isTokenValid(VALID_TOKEN)).thenReturn(true);
    when(jwtProvider.extractEmail(VALID_TOKEN)).thenReturn(currentUser.getEmail());
    when(userRepository.findByEmailIgnoreCase(currentUser.getEmail()))
        .thenReturn(Optional.of(currentUser));
  }

  private static MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder request) {
    return request.header("Authorization", "Bearer " + VALID_TOKEN);
  }

  private static String url(Integer userId) {
    return "/v1/api/users/" + userId + "/posts";
  }

  @Nested
  @DisplayName("GET /v1/api/users/{userId}/posts")
  class GetUserPostsTests {

    @Test
    @DisplayName("shouldReturn200AndACursorPage_happyPath")
    void shouldReturnPage() throws Exception {
      // Given
      when(postQueryService.getPostsByAuthor(currentUser.getId(), AUTHOR_ID, null, 20))
          .thenReturn(
              new PostPageResponseDto(
                  List.of(FeedPostDataDto.builder().postId(7).authorId(AUTHOR_ID).build()),
                  7,
                  true));

      // When / Then
      mockMvc
          .perform(authed(get(url(AUTHOR_ID))))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.posts[0].postId").value(7))
          .andExpect(jsonPath("$.nextCursor").value(7))
          .andExpect(jsonPath("$.hasMore").value(true));
    }

    @Test
    @DisplayName("shouldPassTheAuthenticatedUserAsTheViewer_soVisibilityIsRelationshipBased")
    void shouldPassViewerThrough() throws Exception {
      // Given
      when(postQueryService.getPostsByAuthor(eq(currentUser.getId()), eq(AUTHOR_ID), eq(5), eq(3)))
          .thenReturn(new PostPageResponseDto(List.of(), null, false));

      // When
      mockMvc
          .perform(authed(get(url(AUTHOR_ID))).param("cursor", "5").param("limit", "3"))
          .andExpect(status().isOk());

      // Then
      verify(postQueryService).getPostsByAuthor(currentUser.getId(), AUTHOR_ID, 5, 3);
    }

    @Test
    @DisplayName("shouldReturn404_whenTheAuthorDoesNotExist")
    void shouldReturn404() throws Exception {
      // Given
      when(postQueryService.getPostsByAuthor(currentUser.getId(), AUTHOR_ID, null, 20))
          .thenThrow(new NotFoundException("User not found with ID: " + AUTHOR_ID));

      // When / Then
      mockMvc.perform(authed(get(url(AUTHOR_ID)))).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("shouldReturn200_whenCalledByAGuest_seeingOnlyPublicPosts")
    void shouldServeGuests() throws Exception {
      // Given: no Authorization header, so the viewer id is null — the "stranger" level
      when(postQueryService.getPostsByAuthor(null, AUTHOR_ID, null, 20))
          .thenReturn(new PostPageResponseDto(List.of(), null, false));

      // When / Then
      mockMvc.perform(get(url(AUTHOR_ID))).andExpect(status().isOk());
      verify(postQueryService).getPostsByAuthor(null, AUTHOR_ID, null, 20);
    }
  }
}
