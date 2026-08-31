package com.socialapp.newsfeed.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.socialapp.moderation.service.BanDetailsService;
import com.socialapp.newsfeed.dto.FeedPostDataDto;
import com.socialapp.newsfeed.dto.FeedResponseDto;
import com.socialapp.newsfeed.dto.FeedScope;
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
 * <p>The {@code GET} follows the same {@code @Positive} query-param pattern as {@code
 * FriendshipController}/{@code NotificationController}: constraint failures on {@code page}/{@code
 * size} are handled as <b>400 Bad Request</b>. The {@code POST} added for seen-post reporting takes a
 * {@code @RequestBody @Valid} instead, and therefore answers a failed constraint with <b>422</b>,
 * malformed JSON with <b>400</b> and a missing content type with <b>415</b> — three statuses on one
 * controller, which is why they are asserted individually below. See {@code
 * GlobalExceptionHandler}'s class-level Javadoc for why the first two differ.
 *
 * <p>Neither service method throws anything the controller maps itself, so there is no Exception
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

  @MockBean
  private BanDetailsService
      banDetailsService; // JwtAuthenticationFilter builds the banned-account 403 through it

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
      when(newsfeedService.getFeed(currentUser.getId(), 1, 10, FeedScope.ALL))
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
      when(newsfeedService.getFeed(currentUser.getId(), 3, 20, FeedScope.ALL))
          .thenReturn(
              FeedResponseDto.builder().posts(List.of()).page(3).size(20).hasMore(false).build());

      // When / Then
      mockMvc
          .perform(authed(get(FEED_URL)).param("page", "3").param("size", "20"))
          .andExpect(status().isOk());

      verify(newsfeedService).getFeed(currentUser.getId(), 3, 20, FeedScope.ALL);
    }

    @Test
    @DisplayName("shouldPassTheSkillScopeThrough_whenProvided")
    void shouldPassSkillScopeThrough() throws Exception {
      // Given — the third tab the frontend designed and could not build
      when(newsfeedService.getFeed(currentUser.getId(), 1, 10, FeedScope.SKILLS))
          .thenReturn(
              FeedResponseDto.builder().posts(List.of()).page(1).size(10).hasMore(false).build());

      // When / Then
      mockMvc.perform(authed(get(FEED_URL)).param("scope", "SKILLS")).andExpect(status().isOk());

      verify(newsfeedService).getFeed(currentUser.getId(), 1, 10, FeedScope.SKILLS);
    }

    @Test
    @DisplayName("shouldDefaultToTheAllScope_whenNoneIsGiven")
    void shouldDefaultToAllScope() throws Exception {
      // Given
      when(newsfeedService.getFeed(currentUser.getId(), 1, 10, FeedScope.ALL))
          .thenReturn(
              FeedResponseDto.builder().posts(List.of()).page(1).size(10).hasMore(false).build());

      // When / Then — the existing tab must keep behaving exactly as it did before the parameter
      mockMvc.perform(authed(get(FEED_URL))).andExpect(status().isOk());

      verify(newsfeedService).getFeed(currentUser.getId(), 1, 10, FeedScope.ALL);
    }

    @Test
    @DisplayName("shouldReturn400_whenScopeIsNotAValidEnumValue")
    void shouldReturn400_whenScopeInvalid() throws Exception {
      // When / Then — EP: scope must be one of FeedScope's constants
      mockMvc
          .perform(authed(get(FEED_URL)).param("scope", "FRIENDS"))
          .andExpect(status().isBadRequest());
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

  @Nested
  @DisplayName("POST /v1/api/feed/seen")
  class MarkSeenTests {

    private static final String SEEN_URL = FEED_URL + "/seen";

    private static String bodyWithIds(String ids) {
      return "{\"postIds\":[" + ids + "]}";
    }

    private static String bodyWith(int count) {
      return bodyWithIds(
          java.util.stream.IntStream.rangeClosed(1, count)
              .mapToObj(Integer::toString)
              .collect(java.util.stream.Collectors.joining(",")));
    }

    @Test
    @DisplayName("shouldReturn204AndDelegate_happyPath")
    void shouldReturn204AndDelegate_happyPath() throws Exception {
      // When / Then
      mockMvc
          .perform(
              authed(post(SEEN_URL))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(bodyWithIds("10,11,12")))
          .andExpect(status().isNoContent());

      verify(newsfeedService).markSeen(currentUser.getId(), List.of(10, 11, 12));
    }

    @Test
    @DisplayName("shouldKeyOnTheTokensUser_notAnythingInTheBody")
    void shouldKeyOnTheTokensUser() throws Exception {
      // Given — a body that tries to name somebody else
      String body = "{\"userId\":999,\"postIds\":[10]}";

      // When / Then — the extra property is ignored and the caller's own id is used, which is why
      // the post ids need no ownership check of their own
      mockMvc
          .perform(authed(post(SEEN_URL)).contentType(MediaType.APPLICATION_JSON).content(body))
          .andExpect(status().isNoContent());

      verify(newsfeedService).markSeen(currentUser.getId(), List.of(10));
    }

    @Test
    @DisplayName("shouldReturn422_whenPostIdsIsEmpty")
    void shouldReturn422_whenPostIdsIsEmpty() throws Exception {
      // When / Then — EP: @NotEmpty on a @RequestBody field, which this codebase answers with 422
      mockMvc
          .perform(
              authed(post(SEEN_URL))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(bodyWithIds("")))
          .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("shouldReturn422_whenPostIdsIsMissing")
    void shouldReturn422_whenPostIdsIsMissing() throws Exception {
      // When / Then
      mockMvc
          .perform(authed(post(SEEN_URL)).contentType(MediaType.APPLICATION_JSON).content("{}"))
          .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("shouldAccept200Ids_atTheBoundary")
    void shouldAccept200Ids_atTheBoundary() throws Exception {
      // When / Then — BVA: 200 is the last accepted size
      mockMvc
          .perform(
              authed(post(SEEN_URL)).contentType(MediaType.APPLICATION_JSON).content(bodyWith(200)))
          .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("shouldReturn422_when201IdsAreSent_boundary")
    void shouldReturn422_when201IdsAreSent() throws Exception {
      // When / Then — BVA: one past the cap that keeps a reader's seen set bounded
      mockMvc
          .perform(
              authed(post(SEEN_URL)).contentType(MediaType.APPLICATION_JSON).content(bodyWith(201)))
          .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("shouldReturn422_whenAnIdIsNotPositive")
    void shouldReturn422_whenAnIdIsNotPositive() throws Exception {
      // When / Then — EP: zero and negatives are outside the @Positive partition
      mockMvc
          .perform(
              authed(post(SEEN_URL))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(bodyWithIds("10,-1")))
          .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("shouldReturn400_whenTheBodyIsNotValidJson")
    void shouldReturn400_whenBodyIsNotValidJson() throws Exception {
      // When / Then — malformed JSON is 400, unlike the 422 above: the request is unreadable
      // rather than semantically wrong. See GlobalExceptionHandler's class Javadoc.
      mockMvc
          .perform(
              authed(post(SEEN_URL)).contentType(MediaType.APPLICATION_JSON).content("not json"))
          .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("shouldReturn415_whenNoContentTypeIsSent")
    void shouldReturn415_whenNoContentTypeIsSent() throws Exception {
      // When / Then
      mockMvc
          .perform(authed(post(SEEN_URL)).content(bodyWithIds("10")))
          .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    @DisplayName("shouldReturn401_whenCalledWithNoAuthorizationHeader")
    void shouldReturn401_whenCalledWithNoAuthorizationHeader() throws Exception {
      // When / Then
      mockMvc
          .perform(
              post(SEEN_URL).contentType(MediaType.APPLICATION_JSON).content(bodyWithIds("10")))
          .andExpect(status().isUnauthorized());
    }
  }
}
