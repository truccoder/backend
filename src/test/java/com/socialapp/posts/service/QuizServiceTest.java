package com.socialapp.posts.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.socialapp.common.exception.NotFoundException;
import com.socialapp.common.exception.ValidationException;
import com.socialapp.posts.dto.QuizResultResponseDto;
import com.socialapp.posts.dto.SubmitQuizRequestDto;
import com.socialapp.posts.entity.PostEntity;
import com.socialapp.posts.entity.QuizAnswerEntity;
import com.socialapp.posts.entity.QuizDetails;
import com.socialapp.posts.entity.QuizQuestion;
import com.socialapp.posts.repository.PostRepository;
import com.socialapp.posts.repository.QuizAnswerRepository;

/**
 * Component (unit) tests for {@link QuizService}, per ISTQB CTFL v4.0.1 Section 2.2.1 — see
 * {@code PostServiceTest} for the full rationale on the mocking approach.
 *
 * <p>Scoring is the security boundary of the quiz feature. Since the answers no longer travel out
 * with the question (see {@code PublicQuizDetailsDto}), this service is the single point that
 * both grades an attempt and discloses the answers, and it may only do the second after the
 * first.
 */
@ExtendWith(MockitoExtension.class)
class QuizServiceTest {

  private static final Integer USER_ID = 1;
  private static final Integer POST_ID = 100;

  @Mock private PostRepository postRepository;
  @Mock private QuizAnswerRepository quizAnswerRepository;

  @InjectMocks private QuizService quizService;

  private static PostEntity postWithQuiz() {
    PostEntity post = new PostEntity();
    post.setId(POST_ID);
    post.setAuthorId(2);

    QuizQuestion first = new QuizQuestion();
    first.setQuestion("2 + 2?");
    first.setOptions(List.of("3", "4"));
    first.setCorrectOptionIndex(1);
    first.setExplanation("Two plus two is four");

    // Second question deliberately has no explanation: the author is not obliged to write one,
    // and the response must keep the positional alignment with correctAnswers regardless.
    QuizQuestion second = new QuizQuestion();
    second.setQuestion("Capital of Vietnam?");
    second.setOptions(List.of("Ha Noi", "Hue"));
    second.setCorrectOptionIndex(0);

    QuizDetails quiz = new QuizDetails();
    quiz.setTitle("Mixed quiz");
    quiz.setQuestions(List.of(first, second));
    post.setQuizDetails(quiz);
    return post;
  }

  private static SubmitQuizRequestDto request(Integer... selectedOptions) {
    SubmitQuizRequestDto request = new SubmitQuizRequestDto();
    request.setSelectedOptions(Arrays.asList(selectedOptions));
    return request;
  }

  // =====================================================================
  // submitQuiz
  // =====================================================================

  @Nested
  @DisplayName("submitQuiz")
  class SubmitQuizTests {

    @Test
    @DisplayName("should score the attempt against the stored answers")
    void shouldScoreAttempt() {
      // Given — one right, one wrong
      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(postWithQuiz()));
      when(quizAnswerRepository.existsByPostIdAndUserId(POST_ID, USER_ID)).thenReturn(false);

      // When
      QuizResultResponseDto result = quizService.submitQuiz(USER_ID, POST_ID, request(1, 1));

      // Then
      assertThat(result.getScore()).isEqualTo(1);
      assertThat(result.getTotalQuestions()).isEqualTo(2);
    }

    @Test
    @DisplayName("should disclose the answers and explanations only in the result")
    void shouldDiscloseAnswersAndExplanations() {
      // Given
      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(postWithQuiz()));
      when(quizAnswerRepository.existsByPostIdAndUserId(POST_ID, USER_ID)).thenReturn(false);

      // When
      QuizResultResponseDto result = quizService.submitQuiz(USER_ID, POST_ID, request(1, 0));

      // Then — this response is now the ONLY route to either, so both have to be here, and
      // explanations must stay positionally aligned with correctAnswers even where absent
      assertThat(result.getCorrectAnswers()).containsExactly(1, 0);
      assertThat(result.getExplanations()).containsExactly("Two plus two is four", null);
    }

    @Test
    @DisplayName("should record the attempt with the submitted answers and score")
    void shouldRecordAttempt() {
      // Given
      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(postWithQuiz()));
      when(quizAnswerRepository.existsByPostIdAndUserId(POST_ID, USER_ID)).thenReturn(false);

      // When
      quizService.submitQuiz(USER_ID, POST_ID, request(1, 0));

      // Then
      ArgumentCaptor<QuizAnswerEntity> saved = ArgumentCaptor.forClass(QuizAnswerEntity.class);
      verify(quizAnswerRepository).save(saved.capture());
      assertThat(saved.getValue().getPostId()).isEqualTo(POST_ID);
      assertThat(saved.getValue().getUserId()).isEqualTo(USER_ID);
      assertThat(saved.getValue().getAnswers()).containsExactly(1, 0);
      assertThat(saved.getValue().getScore()).isEqualTo(2);
    }

    @Test
    @DisplayName("should reject when the post does not exist")
    void shouldThrowNotFoundException_whenPostDoesNotExist() {
      // Given
      when(postRepository.findById(POST_ID)).thenReturn(Optional.empty());

      // When / Then
      assertThatThrownBy(() -> quizService.submitQuiz(USER_ID, POST_ID, request(1, 0)))
          .isInstanceOf(NotFoundException.class)
          .hasMessageContaining("Post not found");
      verify(quizAnswerRepository, never()).save(any());
    }

    @Test
    @DisplayName("should reject when the post carries no quiz")
    void shouldThrowValidationException_whenPostHasNoQuiz() {
      // Given
      PostEntity post = new PostEntity();
      post.setId(POST_ID);
      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(post));

      // When / Then
      assertThatThrownBy(() -> quizService.submitQuiz(USER_ID, POST_ID, request(1)))
          .isInstanceOf(ValidationException.class)
          .hasMessageContaining("does not contain a quiz");
      verify(quizAnswerRepository, never()).save(any());
    }

    @Test
    @DisplayName("should reject a second attempt by the same user")
    void shouldThrowValidationException_whenAlreadySubmitted() {
      // Given — one attempt per user is what makes disclosing the answers on submit safe:
      // a reader cannot submit a throwaway attempt to read the answers, then answer properly
      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(postWithQuiz()));
      when(quizAnswerRepository.existsByPostIdAndUserId(POST_ID, USER_ID)).thenReturn(true);

      // When / Then
      assertThatThrownBy(() -> quizService.submitQuiz(USER_ID, POST_ID, request(1, 0)))
          .isInstanceOf(ValidationException.class)
          .hasMessageContaining("already submitted");
      verify(quizAnswerRepository, never()).save(any());
    }

    @Test
    @DisplayName("should reject when the answer count does not match the question count")
    void shouldThrowValidationException_whenAnswerCountIsWrong() {
      // Given — BVA: two questions, one answer
      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(postWithQuiz()));
      when(quizAnswerRepository.existsByPostIdAndUserId(POST_ID, USER_ID)).thenReturn(false);

      // When / Then
      assertThatThrownBy(() -> quizService.submitQuiz(USER_ID, POST_ID, request(1)))
          .isInstanceOf(ValidationException.class)
          .hasMessageContaining("exactly 2 answers");
      verify(quizAnswerRepository, never()).save(any());
    }

    @Test
    @DisplayName("should reject when no answers are supplied at all")
    void shouldThrowValidationException_whenAnswersAreNull() {
      // Given
      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(postWithQuiz()));
      when(quizAnswerRepository.existsByPostIdAndUserId(POST_ID, USER_ID)).thenReturn(false);

      // When / Then
      assertThatThrownBy(() -> quizService.submitQuiz(USER_ID, POST_ID, new SubmitQuizRequestDto()))
          .isInstanceOf(ValidationException.class);
      verify(quizAnswerRepository, never()).save(any());
    }
  }
}
