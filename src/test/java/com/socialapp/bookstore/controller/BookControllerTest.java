package com.socialapp.bookstore.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.socialapp.bookstore.dto.BookResponseDto;
import com.socialapp.bookstore.dto.BookReviewResponseDto;
import com.socialapp.bookstore.dto.RatingBreakdownDto;
import com.socialapp.bookstore.service.BookReviewService;
import com.socialapp.bookstore.service.BookService;
import com.socialapp.common.exception.ForbiddenException;
import com.socialapp.common.exception.NotFoundException;
import com.socialapp.security.config.CustomAccessDeniedHandler;
import com.socialapp.security.config.CustomAuthenticationEntryPoint;
import com.socialapp.security.config.JwtAuthenticationFilter;
import com.socialapp.security.config.JwtProvider;
import com.socialapp.security.config.SecurityConfig;
import com.socialapp.security.entity.UserEntity;
import com.socialapp.security.entity.UserRole;
import com.socialapp.security.repository.UserRepository;

/**
 * System/API integration tests for {@link BookController}, per ISTQB CTFL v4.0.1 Section 2.2.2,
 * using {@code @WebMvcTest} + {@code MockMvc}. {@link BookService} and {@link BookReviewService}
 * are mocked.
 *
 * <p>{@code /v1/api/books/**} requires authentication ({@code .anyRequest().authenticated()} in
 * {@link SecurityConfig}) even for endpoints whose controller method never calls {@code
 * SecurityUtils.getCurrentUserId()} (e.g. {@code previewBook}, {@code getReviews}) — the filter
 * chain still rejects an anonymous caller before the handler runs, so 401 tests apply uniformly.
 *
 * <p>Only {@code POST /{bookId}/reviews} carries {@code @Valid} ({@link
 * com.socialapp.bookstore.dto.CreateReviewRequestDto#rating} is {@code @NotNull @Min(1) @Max(5)}),
 * giving the one 422 Validation (BVA) section in this controller; every other endpoint has no
 * request body to validate.
 *
 * <p>Auth simulation follows the same real-{@link JwtAuthenticationFilter} pattern as the other
 * authenticated controllers in this suite.
 */
@WebMvcTest(BookController.class)
@Import({
  SecurityConfig.class,
  CustomAuthenticationEntryPoint.class,
  CustomAccessDeniedHandler.class,
  JwtAuthenticationFilter.class
})
class BookControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockBean private BookService bookService;
  @MockBean private BookReviewService reviewService;
  @MockBean private JwtProvider jwtProvider;
  @MockBean private UserRepository userRepository;

  private static final String BOOKS_URL = "/v1/api/books";
  private static final String VALID_TOKEN = "a-valid-jwt-token";

  private UserEntity currentUser;

  @BeforeEach
  void setUpDefaultUser() {
    currentUser = new UserEntity();
    currentUser.setId(1);
    currentUser.setEmail("reader@example.com");
    currentUser.setUsername("reader");
    currentUser.setFullName("Reader One");
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

  private static BookResponseDto sampleBook(Integer id) {
    return BookResponseDto.builder()
        .id(id)
        .authorId(2)
        .title("Effective Java")
        .isFree(true)
        .build();
  }

  // =====================================================================
  // GET /v1/api/books/{bookId}
  // =====================================================================

  @Nested
  @DisplayName("GET /v1/api/books/{bookId}")
  class GetBookTests {

    @Test
    @DisplayName("shouldReturn200AndBook_happyPath")
    void shouldReturn200AndBook_happyPath() throws Exception {
      // Given
      when(bookService.getBook(1, currentUser.getId())).thenReturn(sampleBook(1));

      // When / Then
      mockMvc
          .perform(authed(get(BOOKS_URL + "/1")))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.id").value(1))
          .andExpect(jsonPath("$.title").value("Effective Java"));
    }

    @Test
    @DisplayName("shouldReturn404_whenBookDoesNotExist")
    void shouldReturn404_whenBookDoesNotExist() throws Exception {
      // Given
      when(bookService.getBook(999, currentUser.getId()))
          .thenThrow(new NotFoundException("Book not found: 999"));

      // When / Then
      mockMvc
          .perform(authed(get(BOOKS_URL + "/999")))
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.message").value("Book not found: 999"));
    }

    @Test
    @DisplayName("shouldReturn400_whenBookIdPathVariableIsNotANumber")
    void shouldReturn400_whenBookIdPathVariableIsNotANumber() throws Exception {
      // When / Then — EP: bookId must be an Integer
      mockMvc.perform(authed(get(BOOKS_URL + "/not-a-number"))).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("shouldReturn401_whenCalledWithNoAuthorizationHeader")
    void shouldReturn401_whenCalledWithNoAuthorizationHeader() throws Exception {
      // When / Then
      mockMvc.perform(get(BOOKS_URL + "/1")).andExpect(status().isUnauthorized());
    }
  }

  // =====================================================================
  // GET /v1/api/books/author/{authorId}
  // =====================================================================

  @Nested
  @DisplayName("GET /v1/api/books/author/{authorId}")
  class GetBooksByAuthorTests {

    @Test
    @DisplayName("shouldReturn200AndBookList_happyPath")
    void shouldReturn200AndBookList_happyPath() throws Exception {
      // Given
      when(bookService.getBooksByAuthor(2, currentUser.getId())).thenReturn(List.of(sampleBook(1)));

      // When / Then
      mockMvc
          .perform(authed(get(BOOKS_URL + "/author/2")))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$[0].id").value(1));
    }

    @Test
    @DisplayName("shouldReturn200AndEmptyList_whenAuthorHasNoBooks")
    void shouldReturn200AndEmptyList_whenAuthorHasNoBooks() throws Exception {
      // Given
      when(bookService.getBooksByAuthor(2, currentUser.getId())).thenReturn(List.of());

      // When / Then
      mockMvc.perform(authed(get(BOOKS_URL + "/author/2"))).andExpect(status().isOk());
    }

    @Test
    @DisplayName("shouldReturn401_whenCalledWithNoAuthorizationHeader")
    void shouldReturn401_whenCalledWithNoAuthorizationHeader() throws Exception {
      // When / Then
      mockMvc.perform(get(BOOKS_URL + "/author/2")).andExpect(status().isUnauthorized());
    }
  }

  // =====================================================================
  // GET /v1/api/books/{bookId}/download
  // =====================================================================

  @Nested
  @DisplayName("GET /v1/api/books/{bookId}/download")
  class DownloadBookTests {

    @Test
    @DisplayName("shouldReturn200AndUrl_whenBookIsPurchasedOrFree_happyPath")
    void shouldReturn200AndUrl_whenBookIsPurchasedOrFree_happyPath() throws Exception {
      // Given
      when(bookService.getFullDownloadUrl(1, currentUser.getId()))
          .thenReturn("https://minio.example.com/books/1/full.pdf");

      // When / Then
      mockMvc
          .perform(authed(get(BOOKS_URL + "/1/download")))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.url").value("https://minio.example.com/books/1/full.pdf"));
    }

    @Test
    @DisplayName("shouldReturn403_whenBookHasNotBeenPurchased")
    void shouldReturn403_whenBookHasNotBeenPurchased() throws Exception {
      // Given
      when(bookService.getFullDownloadUrl(1, currentUser.getId()))
          .thenThrow(new ForbiddenException("You must purchase this book before downloading"));

      // When / Then
      mockMvc
          .perform(authed(get(BOOKS_URL + "/1/download")))
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.message").value("You must purchase this book before downloading"));
    }

    @Test
    @DisplayName("shouldReturn401_whenCalledWithNoAuthorizationHeader")
    void shouldReturn401_whenCalledWithNoAuthorizationHeader() throws Exception {
      // When / Then
      mockMvc.perform(get(BOOKS_URL + "/1/download")).andExpect(status().isUnauthorized());
    }
  }

  // =====================================================================
  // GET /v1/api/books/{bookId}/preview
  // =====================================================================

  @Nested
  @DisplayName("GET /v1/api/books/{bookId}/preview")
  class PreviewBookTests {

    @Test
    @DisplayName("shouldReturn200AndUrl_happyPath")
    void shouldReturn200AndUrl_happyPath() throws Exception {
      // Given
      when(bookService.getPreviewUrl(1))
          .thenReturn("https://minio.example.com/books/1/preview.pdf");

      // When / Then
      mockMvc
          .perform(authed(get(BOOKS_URL + "/1/preview")))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.url").value("https://minio.example.com/books/1/preview.pdf"));
    }

    @Test
    @DisplayName("shouldReturn401_whenCalledWithNoAuthorizationHeader")
    void shouldReturn401_whenCalledWithNoAuthorizationHeader() throws Exception {
      // Given — the controller method itself never reads the current user, but the endpoint
      // still sits behind SecurityConfig's anyRequest().authenticated()
      mockMvc.perform(get(BOOKS_URL + "/1/preview")).andExpect(status().isUnauthorized());
    }
  }

  // =====================================================================
  // DELETE /v1/api/books/{bookId}
  // =====================================================================

  @Nested
  @DisplayName("DELETE /v1/api/books/{bookId}")
  class DeleteBookTests {

    @Test
    @DisplayName("shouldReturn200_whenCallerIsTheAuthor_happyPath")
    void shouldReturn200_whenCallerIsTheAuthor_happyPath() throws Exception {
      // When / Then
      mockMvc.perform(authed(delete(BOOKS_URL + "/1"))).andExpect(status().isOk());

      verify(bookService).deleteBook(currentUser.getId(), 1);
    }

    @Test
    @DisplayName("shouldReturn403_whenCallerIsNotTheAuthor")
    void shouldReturn403_whenCallerIsNotTheAuthor() throws Exception {
      // Given
      doThrow(new ForbiddenException("Only the author can delete this book"))
          .when(bookService)
          .deleteBook(anyInt(), anyInt());

      // When / Then
      mockMvc
          .perform(authed(delete(BOOKS_URL + "/1")))
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.message").value("Only the author can delete this book"));
    }

    @Test
    @DisplayName("shouldReturn401_whenCalledWithNoAuthorizationHeader")
    void shouldReturn401_whenCalledWithNoAuthorizationHeader() throws Exception {
      // When / Then
      mockMvc.perform(delete(BOOKS_URL + "/1")).andExpect(status().isUnauthorized());
    }
  }

  // =====================================================================
  // POST /v1/api/books/{bookId}/reviews
  // =====================================================================

  @Nested
  @DisplayName("POST /v1/api/books/{bookId}/reviews")
  class CreateReviewTests {

    @Test
    @DisplayName("shouldReturn200AndReview_whenRatingIsValid_happyPath")
    void shouldReturn200AndReview_whenRatingIsValid_happyPath() throws Exception {
      // Given
      when(reviewService.createOrUpdateReview(eq(currentUser.getId()), eq(1), any()))
          .thenReturn(
              BookReviewResponseDto.builder()
                  .id(10)
                  .userId(currentUser.getId())
                  .rating(5)
                  .feedback("Great book")
                  .build());
      String requestJson =
          """
          { "rating": 5, "feedback": "Great book" }
          """;

      // When / Then
      mockMvc
          .perform(
              authed(post(BOOKS_URL + "/1/reviews"))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(requestJson))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.rating").value(5));
    }

    @Test
    @DisplayName("shouldReturn422_whenRatingIsMissing")
    void shouldReturn422_whenRatingIsMissing() throws Exception {
      // Given
      String requestJson =
          """
          { "feedback": "No rating given" }
          """;

      // When / Then
      mockMvc
          .perform(
              authed(post(BOOKS_URL + "/1/reviews"))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(requestJson))
          .andExpect(status().isUnprocessableEntity());
    }

    @ParameterizedTest
    @DisplayName("shouldReturn422_whenRatingIsOutsideOneToFive")
    @ValueSource(ints = {0, 6})
    void shouldReturn422_whenRatingIsOutsideOneToFive(int invalidRating) throws Exception {
      // Given — EP: valid partition is [1, 5]; 0 and 6 are just outside each boundary
      String requestJson = "{\"rating\": %d}".formatted(invalidRating);

      // When / Then
      mockMvc
          .perform(
              authed(post(BOOKS_URL + "/1/reviews"))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(requestJson))
          .andExpect(status().isUnprocessableEntity());
    }

    @ParameterizedTest
    @DisplayName("shouldReturn200_whenRatingIsAtTheBoundary")
    @ValueSource(ints = {1, 5})
    void shouldReturn200_whenRatingIsAtTheBoundary(int boundaryRating) throws Exception {
      // Given — BVA: 1 and 5 are the smallest/largest valid ratings
      when(reviewService.createOrUpdateReview(eq(currentUser.getId()), eq(1), any()))
          .thenReturn(BookReviewResponseDto.builder().id(10).rating(boundaryRating).build());
      String requestJson = "{\"rating\": %d}".formatted(boundaryRating);

      // When / Then
      mockMvc
          .perform(
              authed(post(BOOKS_URL + "/1/reviews"))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(requestJson))
          .andExpect(status().isOk());
    }

    @Test
    @DisplayName("shouldReturn401_whenCalledWithNoAuthorizationHeader")
    void shouldReturn401_whenCalledWithNoAuthorizationHeader() throws Exception {
      // Given
      String requestJson =
          """
          { "rating": 5 }
          """;

      // When / Then
      mockMvc
          .perform(
              post(BOOKS_URL + "/1/reviews")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(requestJson))
          .andExpect(status().isUnauthorized());
    }
  }

  // =====================================================================
  // GET /v1/api/books/{bookId}/reviews
  // =====================================================================

  @Nested
  @DisplayName("GET /v1/api/books/{bookId}/reviews")
  class GetReviewsTests {

    @Test
    @DisplayName("shouldReturn200AndReviews_happyPath")
    void shouldReturn200AndReviews_happyPath() throws Exception {
      // Given
      when(reviewService.getReviews(1))
          .thenReturn(List.of(BookReviewResponseDto.builder().id(10).rating(4).build()));

      // When / Then
      mockMvc
          .perform(authed(get(BOOKS_URL + "/1/reviews")))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$[0].rating").value(4));
    }

    @Test
    @DisplayName("shouldReturn401_whenCalledWithNoAuthorizationHeader")
    void shouldReturn401_whenCalledWithNoAuthorizationHeader() throws Exception {
      // When / Then
      mockMvc.perform(get(BOOKS_URL + "/1/reviews")).andExpect(status().isUnauthorized());
    }
  }

  // =====================================================================
  // GET /v1/api/books/{bookId}/reviews/breakdown
  // =====================================================================

  @Nested
  @DisplayName("GET /v1/api/books/{bookId}/reviews/breakdown")
  class GetRatingBreakdownTests {

    @Test
    @DisplayName("shouldReturn200AndBreakdown_happyPath")
    void shouldReturn200AndBreakdown_happyPath() throws Exception {
      // Given
      when(reviewService.getRatingBreakdown(1))
          .thenReturn(new RatingBreakdownDto(0, 0, 1, 2, 3, 6));

      // When / Then
      mockMvc
          .perform(authed(get(BOOKS_URL + "/1/reviews/breakdown")))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.fiveStarsCount").value(3))
          .andExpect(jsonPath("$.totalRatings").value(6));
    }

    @Test
    @DisplayName("shouldReturn401_whenCalledWithNoAuthorizationHeader")
    void shouldReturn401_whenCalledWithNoAuthorizationHeader() throws Exception {
      // When / Then
      mockMvc.perform(get(BOOKS_URL + "/1/reviews/breakdown")).andExpect(status().isUnauthorized());
    }
  }
}
