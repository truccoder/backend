package com.socialapp.search.controller;

import static org.mockito.ArgumentMatchers.any;
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

import com.socialapp.friendships.service.FriendshipService;
import com.socialapp.moderation.service.BanDetailsService;
import com.socialapp.search.dto.BookDto;
import com.socialapp.search.dto.SearchResult;
import com.socialapp.search.dto.SuggestionDto;
import com.socialapp.search.dto.SuggestionType;
import com.socialapp.search.dto.UserDto;
import com.socialapp.search.service.SearchService;
import com.socialapp.search.service.SuggestService;
import com.socialapp.security.config.CustomAccessDeniedHandler;
import com.socialapp.security.config.CustomAuthenticationEntryPoint;
import com.socialapp.security.config.JwtAuthenticationFilter;
import com.socialapp.security.config.JwtProvider;
import com.socialapp.security.config.SecurityConfig;
import com.socialapp.security.entity.UserEntity;
import com.socialapp.security.entity.UserRole;
import com.socialapp.security.repository.UserRepository;

/**
 * System/API integration tests for {@link SearchController}, per ISTQB CTFL v4.0.1 Section
 * 2.2.2, using {@code @WebMvcTest} + {@code MockMvc}. {@link SearchService} and {@link
 * FriendshipQueryService} are mocked.
 *
 * <p>{@code q} is a required {@code @RequestParam @NotBlank String} with no default value —
 * {@link MissingRequestParamTests} covers the boundary the other {@code @Positive}-only
 * controllers didn't have: omitting {@code q} entirely raises {@code
 * MissingServletRequestParameterException}, which has its own {@code @ExceptionHandler} in
 * {@code GlobalExceptionHandler} returning <b>400 Bad Request</b> (previously fell through to
 * the generic {@code Exception} handler as 500, same category of gap as the malformed-JSON/no-
 * Content-Type cases already fixed for AuthController).
 *
 * <p>Passing {@code q=""} (present but blank) or an invalid {@code size} takes a different path
 * and lands on <b>422</b>, not the 400 seen on {@code FriendshipController}/{@code
 * NotificationController}'s {@code @Positive} params: this controller class carries {@code
 * @Validated}, which routes constraint failures through Spring's older AOP method validation
 * ({@code jakarta.validation.ConstraintViolationException}, a subtype of {@code
 * jakarta.validation.ValidationException}) instead of the newer {@code
 * HandlerMethodValidationException} path used when a controller has no class-level {@code
 * @Validated}. Confirmed empirically — see {@code SearchTests.shouldReturn422_whenQueryIsBlank}.
 */
@WebMvcTest(SearchController.class)
@Import({
  SecurityConfig.class,
  CustomAuthenticationEntryPoint.class,
  CustomAccessDeniedHandler.class,
  JwtAuthenticationFilter.class
})
class SearchControllerTest {

  private static final String SUGGEST_URL = "/v1/api/search/suggest";

  @Autowired private MockMvc mockMvc;

  @MockBean private SearchService searchService;

  // /search/suggest now goes through its own service — see SuggestService for why the type-ahead
  // path is kept off the results-page code.
  @MockBean private SuggestService suggestService;
  @MockBean private FriendshipService friendshipService;
  @MockBean private JwtProvider jwtProvider;

  @MockBean
  private BanDetailsService
      banDetailsService; // JwtAuthenticationFilter builds the banned-account 403 through it

  @MockBean private UserRepository userRepository;

  private static final String SEARCH_URL = "/v1/api/search";
  private static final String VALID_TOKEN = "a-valid-jwt-token";

  private UserEntity currentUser;

  @BeforeEach
  void setUpDefaultUser() {
    currentUser = new UserEntity();
    currentUser.setId(1);
    currentUser.setEmail("searcher@example.com");
    currentUser.setUsername("searcher");
    currentUser.setFullName("Searcher One");
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
  @DisplayName("GET /v1/api/search")
  class SearchTests {

    @Test
    @DisplayName("shouldReturn200AndResults_happyPath")
    void shouldReturn200AndResults_happyPath() throws Exception {
      // Given
      when(friendshipService.getFriendIds(currentUser.getId())).thenReturn(List.of());
      when(searchService.searchUsers(eq("reader"), eq(1), eq(10), eq(currentUser.getId()), any()))
          .thenReturn(
              SearchResult.<UserDto>builder()
                  .items(List.of(UserDto.builder().id(2).username("reader1").build()))
                  .totalHits(1)
                  .page(1)
                  .size(10)
                  .build());
      when(searchService.searchPostsWithBookInfo(
              eq("reader"), eq(10), eq(currentUser.getId()), any()))
          .thenReturn(List.of());
      when(searchService.searchBooks(eq("reader"), eq(10), eq(currentUser.getId()), any()))
          .thenReturn(List.of(BookDto.builder().id(7).title("Reader Monad").build()));

      // When / Then — three lists, one per thing the product claims is searchable. The books
      // branch is the one the frontend could not draw a tab for while it did not exist.
      mockMvc
          .perform(authed(get(SEARCH_URL)).param("q", "reader"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.users[0].id").value(2))
          .andExpect(jsonPath("$.users[0].username").value("reader1"))
          .andExpect(jsonPath("$.posts").isArray())
          .andExpect(jsonPath("$.books[0].id").value(7))
          .andExpect(jsonPath("$.books[0].title").value("Reader Monad"));
    }

    @Test
    @DisplayName("shouldPassSizeThrough_whenProvided")
    void shouldPassSizeThrough_whenProvided() throws Exception {
      // Given
      when(friendshipService.getFriendIds(currentUser.getId())).thenReturn(List.of());
      when(searchService.searchUsers(eq("java"), eq(1), eq(5), eq(currentUser.getId()), any()))
          .thenReturn(
              SearchResult.<UserDto>builder()
                  .items(List.of())
                  .totalHits(0)
                  .page(1)
                  .size(5)
                  .build());
      when(searchService.searchPostsWithBookInfo(eq("java"), eq(5), eq(currentUser.getId()), any()))
          .thenReturn(List.of());
      when(searchService.searchBooks(eq("java"), eq(5), eq(currentUser.getId()), any()))
          .thenReturn(List.of());

      // When / Then
      mockMvc
          .perform(authed(get(SEARCH_URL)).param("q", "java").param("size", "5"))
          .andExpect(status().isOk());
    }

    @Test
    @DisplayName("shouldReturn422_whenQueryIsBlank")
    void shouldReturn422_whenQueryIsBlank() throws Exception {
      // When / Then — q is present but blank, fails @NotBlank. Unlike FriendshipController's
      // @Positive params (400 via HandlerMethodValidationException), SearchController carries a
      // class-level @Validated, which routes constraint failures through Spring AOP method
      // validation instead: it throws jakarta.validation.ConstraintViolationException, a subtype
      // of jakarta.validation.ValidationException, which GlobalExceptionHandler maps to 422 — a
      // different status than the "same kind" of param validation gets on non-@Validated
      // controllers. Confirmed empirically, documented rather than assumed.
      mockMvc
          .perform(authed(get(SEARCH_URL)).param("q", ""))
          .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("shouldReturn422_whenSizeIsZero_boundary")
    void shouldReturn422_whenSizeIsZero_boundary() throws Exception {
      // When / Then — BVA: @Positive requires > 0; see shouldReturn422_whenQueryIsBlank for why
      // this is 422 here rather than the 400 seen on FriendshipController/NotificationController.
      mockMvc
          .perform(authed(get(SEARCH_URL)).param("q", "java").param("size", "0"))
          .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("shouldReturn401_whenCalledWithNoAuthorizationHeader")
    void shouldReturn401_whenCalledWithNoAuthorizationHeader() throws Exception {
      // When / Then
      mockMvc.perform(get(SEARCH_URL).param("q", "java")).andExpect(status().isUnauthorized());
    }
  }

  @Nested
  @DisplayName("Missing required query parameter")
  class MissingRequestParamTests {

    @Test
    @DisplayName("shouldReturn400_whenQueryParamIsOmittedEntirely")
    void shouldReturn400_whenQueryParamIsOmittedEntirely() throws Exception {
      // Given — no "q" param at all (distinct from q="", which is present-but-blank)

      // When / Then — MissingServletRequestParameterException now has its own
      // @ExceptionHandler in GlobalExceptionHandler (previously fell through to the generic
      // Exception handler and was misreported as 500).
      mockMvc
          .perform(authed(get(SEARCH_URL)))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.message").value("Missing required parameter 'q'"));
    }
  }

  // =====================================================================
  // GET /v1/api/search/suggest   (C2 — the type-ahead dropdown)
  // =====================================================================

  @Nested
  @DisplayName("GET /v1/api/search/suggest")
  class SuggestTests {

    @Test
    @DisplayName("shouldReturn200AndAFlatList_happyPath")
    void shouldReturnFlatList() throws Exception {
      // Given
      when(suggestService.suggest(any(), org.mockito.ArgumentMatchers.anyInt(), any()))
          .thenReturn(
              List.of(
                  new SuggestionDto(SuggestionType.USER, 1, "Nguyen Truc", "@nguyentruc", null),
                  new SuggestionDto(SuggestionType.BOOK, 9, "Lap trinh Java", null, null)));

      // When / Then: one flat list, because the dropdown renders one list
      mockMvc
          .perform(authed(get(SUGGEST_URL)).param("q", "ngu"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$[0].type").value("USER"))
          .andExpect(jsonPath("$[0].sublabel").value("@nguyentruc"))
          .andExpect(jsonPath("$[1].type").value("BOOK"));
    }

    @Test
    @DisplayName("shouldUseTheDefaultLimit_whenNoneIsGiven")
    void shouldUseDefaultLimit() throws Exception {
      // Given
      when(suggestService.suggest(any(), org.mockito.ArgumentMatchers.anyInt(), any()))
          .thenReturn(List.of());

      // When
      mockMvc.perform(authed(get(SUGGEST_URL)).param("q", "a")).andExpect(status().isOk());

      // Then — Constants.DEFAULT_PAGINATION_SUGGEST_LIMIT, smaller than a results page on purpose
      verify(suggestService).suggest(eq("a"), eq(8), any());
    }

    @Test
    @DisplayName("shouldReturn422_whenLimitExceedsTheCap_boundary")
    void shouldRejectLimitAboveCap() throws Exception {
      // BVA: @Max(20). Uncapped, this is /search with no pager and no total — a cheaper way to
      // page through the user table than the paged endpoint.
      mockMvc
          .perform(authed(get(SUGGEST_URL)).param("q", "a").param("limit", "21"))
          .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("shouldReturn422_whenQueryIsBlank")
    void shouldRejectBlankQuery() throws Exception {
      mockMvc
          .perform(authed(get(SUGGEST_URL)).param("q", "  "))
          .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("shouldReturn400_whenQueryParamIsOmittedEntirely")
    void shouldRejectMissingQuery() throws Exception {
      mockMvc.perform(authed(get(SUGGEST_URL))).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("shouldReturn401_whenCalledByAGuest")
    void shouldReturn401ForGuest() throws Exception {
      // Deliberately NOT part of the guest-readable surface: an unauthenticated endpoint that
      // returns people by partial name is a user-directory dump.
      mockMvc.perform(get(SUGGEST_URL).param("q", "a")).andExpect(status().isUnauthorized());
    }
  }
}
