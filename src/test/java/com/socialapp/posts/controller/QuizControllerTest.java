package com.socialapp.posts.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
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

import com.socialapp.common.exception.ForbiddenException;
import com.socialapp.common.exception.NotFoundException;
import com.socialapp.common.exception.ValidationException;
import com.socialapp.moderation.service.BanDetailsService;
import com.socialapp.posts.dto.QuizResultResponseDto;
import com.socialapp.posts.dto.SubmitQuizRequestDto;
import com.socialapp.posts.entity.QuizDetails;
import com.socialapp.posts.entity.QuizQuestion;
import com.socialapp.posts.service.QuizService;
import com.socialapp.security.config.CustomAccessDeniedHandler;
import com.socialapp.security.config.CustomAuthenticationEntryPoint;
import com.socialapp.security.config.JwtAuthenticationFilter;
import com.socialapp.security.config.JwtProvider;
import com.socialapp.security.config.SecurityConfig;
import com.socialapp.security.entity.UserEntity;
import com.socialapp.security.entity.UserRole;
import com.socialapp.security.repository.UserRepository;

/**
 * System/API integration tests for {@link QuizController}, per ISTQB CTFL v4.0.1 Section 2.2.2,
 * using {@code @WebMvcTest} + {@code MockMvc}. {@link QuizService} is mocked.
 *
 * <p>This controller had no test file at all. Its single endpoint is the one place a quiz's
 * correct answers and explanations are allowed to leave the server — {@code PublicQuizDetailsDto}
 * strips them from the post payload precisely so that this response is the only channel — which
 * makes its authorization and its already-submitted guard the interesting cases, not the scoring.
 */
@WebMvcTest(QuizController.class)
@Import({
  SecurityConfig.class,
  CustomAuthenticationEntryPoint.class,
  CustomAccessDeniedHandler.class,
  JwtAuthenticationFilter.class
})
class QuizControllerTest {

  private static final String URL = "/v1/api/posts/55/quiz/submit";
  private static final String ANSWERS_URL = "/v1/api/posts/55/quiz/answers";
  private static final String TOKEN = "a-valid-jwt-token";
  private static final Integer CALLER_ID = 9001;
  private static final Integer POST_ID = 55;

  @Autowired private MockMvc mockMvc;

  @MockBean private QuizService quizService;
  @MockBean private JwtProvider jwtProvider;
  @MockBean private BanDetailsService banDetailsService;
  @MockBean private UserRepository userRepository;

  @BeforeEach
  void setUpCaller() {
    UserEntity caller = new UserEntity();
    caller.setId(CALLER_ID);
    caller.setEmail("caller@example.com");
    caller.setUsername("caller");
    caller.setRole(UserRole.USER);
    caller.setEmailVerified(true);

    when(jwtProvider.isTokenValid(TOKEN)).thenReturn(true);
    when(jwtProvider.extractEmail(TOKEN)).thenReturn(caller.getEmail());
    when(userRepository.findByEmailIgnoreCase(caller.getEmail())).thenReturn(Optional.of(caller));
  }

  private static MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder b) {
    return b.header("Authorization", "Bearer " + TOKEN);
  }

  private static QuizDetails fullQuiz() {
    QuizQuestion question = new QuizQuestion();
    question.setQuestion("2 + 2?");
    question.setOptions(List.of("3", "4"));
    question.setCorrectOptionIndex(1);
    question.setExplanation("Two plus two is four");

    QuizDetails quiz = new QuizDetails();
    quiz.setTitle("Mixed quiz");
    quiz.setQuestions(List.of(question));
    return quiz;
  }

  @Nested
  @DisplayName("GET /v1/api/posts/{postId}/quiz/answers")
  class GetQuizForAuthorTests {

    @Test
    @DisplayName("shouldReturn200WithCorrectOptionIndexAndExplanation_happyPath")
    void shouldReturnAnswersToTheAuthor() throws Exception {
      // Given — the one route that puts correctOptionIndex on the wire for somebody who has not
      // answered. It exists so an author can edit a quiz without re-marking every question.
      when(quizService.getQuizForAuthor(CALLER_ID, POST_ID)).thenReturn(fullQuiz());

      // When / Then — the shape must match UpdatePostRequestDto.quizDetails, so what the editor
      // reads is what it can send back
      mockMvc
          .perform(authed(get(ANSWERS_URL)))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.title").value("Mixed quiz"))
          .andExpect(jsonPath("$.questions[0].correctOptionIndex").value(1))
          .andExpect(jsonPath("$.questions[0].explanation").value("Two plus two is four"))
          .andExpect(jsonPath("$.questions[0].options.length()").value(2));
    }

    @Test
    @DisplayName("shouldReturn403_whenCallerIsNotTheAuthor")
    void shouldReturn403ForNonAuthor() throws Exception {
      // Given
      when(quizService.getQuizForAuthor(any(), any()))
          .thenThrow(new ForbiddenException("Only the author can read this quiz's answers"));

      // When / Then
      mockMvc.perform(authed(get(ANSWERS_URL))).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("shouldReturn404_whenThePostCarriesNoQuiz")
    void shouldReturn404WhenNoQuiz() throws Exception {
      // Given
      when(quizService.getQuizForAuthor(any(), any()))
          .thenThrow(new NotFoundException("Post 55 does not contain a quiz"));

      // When / Then
      mockMvc.perform(authed(get(ANSWERS_URL))).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("shouldReturn401_whenCallerIsAnonymous")
    void shouldReturn401ForGuest() throws Exception {
      // Given — GET /v1/api/posts/{id} is open to guests, but that matcher is pinned to a single
      // path segment. This route is three segments deeper and must stay behind authentication,
      // or the answer key would be readable by anybody who could guess a post id.
      mockMvc.perform(get(ANSWERS_URL)).andExpect(status().isUnauthorized());

      verify(quizService, never()).getQuizForAuthor(any(), any());
    }
  }

  @Nested
  @DisplayName("POST /v1/api/posts/{postId}/quiz/submit")
  class SubmitQuizTests {

    @Test
    @DisplayName("shouldReturn200WithScoreAnswersAndExplanations_happyPath")
    void shouldSubmit() throws Exception {
      // Given
      when(quizService.submitQuiz(eq(CALLER_ID), eq(POST_ID), any(SubmitQuizRequestDto.class)))
          .thenReturn(new QuizResultResponseDto(2, 3, List.of(0, 1, 2), List.of("a", "b", "c")));

      // When / Then
      mockMvc
          .perform(
              authed(post(URL))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"selectedOptions\":[0,1,0]}"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.score").value(2))
          .andExpect(jsonPath("$.totalQuestions").value(3))
          .andExpect(jsonPath("$.correctAnswers.length()").value(3))
          .andExpect(jsonPath("$.explanations.length()").value(3));
    }

    @Test
    @DisplayName("shouldPassTheCallerIdFromTheTokenNotTheRequest")
    void shouldUseTokenIdentity() throws Exception {
      // Given
      when(quizService.submitQuiz(any(), any(), any()))
          .thenReturn(new QuizResultResponseDto(1, 1, List.of(0), List.of("only")));

      // When
      mockMvc
          .perform(
              authed(post(URL))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"selectedOptions\":[0]}"))
          .andExpect(status().isOk());

      // Then: the user id is resolved from the JWT, so a client cannot submit as somebody else
      verify(quizService).submitQuiz(eq(CALLER_ID), eq(POST_ID), any(SubmitQuizRequestDto.class));
    }

    @Test
    @DisplayName("shouldReturn404_whenThePostDoesNotExist")
    void shouldReturn404() throws Exception {
      // Given
      when(quizService.submitQuiz(eq(CALLER_ID), eq(POST_ID), any()))
          .thenThrow(new NotFoundException("Post not found with ID: 55"));

      // When / Then
      mockMvc
          .perform(
              authed(post(URL))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"selectedOptions\":[0]}"))
          .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("shouldReturn400_whenThePostCarriesNoQuiz")
    void shouldReturn400WhenNoQuiz() throws Exception {
      // Given: the app's own ValidationException maps to 400 (jakarta's maps to 422 — different
      // type, different status, easy to conflate).
      when(quizService.submitQuiz(eq(CALLER_ID), eq(POST_ID), any()))
          .thenThrow(new ValidationException("This post does not contain a quiz"));

      // When / Then
      mockMvc
          .perform(
              authed(post(URL))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"selectedOptions\":[0]}"))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.message").value("This post does not contain a quiz"));
    }

    @Test
    @DisplayName("shouldReturn400_whenTheQuizWasAlreadySubmitted")
    void shouldReturn400OnResubmit() throws Exception {
      // Given: a quiz may be answered once — the second attempt must not re-reveal the answers
      when(quizService.submitQuiz(eq(CALLER_ID), eq(POST_ID), any()))
          .thenThrow(new ValidationException("You have already submitted answers for this quiz"));

      // When / Then
      mockMvc
          .perform(
              authed(post(URL))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"selectedOptions\":[0]}"))
          .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("shouldReturn400_whenTheAnswerCountDoesNotMatchTheQuestionCount")
    void shouldReturn400OnWrongAnswerCount() throws Exception {
      // Given
      when(quizService.submitQuiz(eq(CALLER_ID), eq(POST_ID), any()))
          .thenThrow(new ValidationException("You must provide exactly 3 answers"));

      // When / Then
      mockMvc
          .perform(
              authed(post(URL))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"selectedOptions\":[0]}"))
          .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("shouldReturn400_whenTheBodyIsMalformedJson")
    void shouldReturn400OnMalformedJson() throws Exception {
      // When / Then
      mockMvc
          .perform(
              authed(post(URL))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"selectedOptions\":"))
          .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("shouldReturn415_whenContentTypeIsMissing")
    void shouldReturn415() throws Exception {
      // When / Then
      mockMvc
          .perform(authed(post(URL)).content("{\"selectedOptions\":[0]}"))
          .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    @DisplayName("shouldReturn400_whenThePostIdIsNotNumeric")
    void shouldReturn400OnNonNumericPostId() throws Exception {
      // When / Then
      mockMvc
          .perform(
              authed(post("/v1/api/posts/abc/quiz/submit"))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"selectedOptions\":[0]}"))
          .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("shouldReturn401_whenCalledWithNoAuthorizationHeader")
    void shouldReturn401ForGuest() throws Exception {
      // Given: quiz answers must never reach an anonymous caller

      // When / Then
      mockMvc
          .perform(
              post(URL)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"selectedOptions\":[0]}"))
          .andExpect(status().isUnauthorized());

      verify(quizService, never()).submitQuiz(any(), any(), any());
    }
  }
}
