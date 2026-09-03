package com.socialapp.posts.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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

import com.socialapp.common.exception.ForbiddenException;
import com.socialapp.common.exception.NotFoundException;
import com.socialapp.common.exception.ValidationException;
import com.socialapp.posts.dto.QuizResultResponseDto;
import com.socialapp.posts.dto.SubmitQuizRequestDto;
import com.socialapp.posts.entity.PostEntity;
import com.socialapp.posts.entity.QuizAnswerEntity;
import com.socialapp.posts.entity.QuizDetails;
import com.socialapp.posts.entity.QuizQuestion;
import com.socialapp.posts.entity.enums.PostVisibility;
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
  @Mock private PostVisibilityService postVisibilityService;

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
  // getQuizForAuthor
  // =====================================================================

  @Nested
  @DisplayName("getQuizForAuthor")
  class GetQuizForAuthorTests {

    @Test
    @DisplayName("should hand the author back the answers and explanations")
    void shouldReturnFullQuiz_whenCallerIsTheAuthor() {
      // Given — the feed and the permalink both serve PublicQuizDetailsDto, which omits
      // correctOptionIndex, so an author editing their own quiz had no way to read the answers
      // back — and validateQuizDetails rejects an update whose answers are missing
      PostEntity post = postWithQuiz();
      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(post));

      // When — called by the post's own author
      QuizDetails quiz = quizService.getQuizForAuthor(post.getAuthorId(), POST_ID);

      // Then — the entity itself, not a redacted view: this is exactly the type
      // UpdatePostRequestDto accepts, so the editor can send back what it read
      assertThat(quiz.getQuestions().get(0).getCorrectOptionIndex()).isEqualTo(1);
      assertThat(quiz.getQuestions().get(0).getExplanation()).isEqualTo("Two plus two is four");
      assertThat(quiz.getQuestions().get(1).getCorrectOptionIndex()).isZero();
    }

    @Test
    @DisplayName("should refuse anybody who is not the author")
    void shouldThrowForbidden_whenCallerIsNotTheAuthor() {
      // Given — a reader who has not answered yet must not reach the answer key by a different
      // route than the one submitQuiz guards
      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(postWithQuiz()));

      // When / Then — 403, not 404: the caller already knows the post exists, and if they are not
      // its author there is nothing left to conceal
      assertThatThrownBy(() -> quizService.getQuizForAuthor(4242, POST_ID))
          .isInstanceOf(ForbiddenException.class)
          .hasMessageContaining("Only the author");
    }

    @Test
    @DisplayName("should report a missing post as missing")
    void shouldThrowNotFound_whenPostDoesNotExist() {
      // Given
      when(postRepository.findById(POST_ID)).thenReturn(Optional.empty());

      // When / Then
      assertThatThrownBy(() -> quizService.getQuizForAuthor(2, POST_ID))
          .isInstanceOf(NotFoundException.class)
          .hasMessageContaining("Post not found");
    }

    @Test
    @DisplayName("should report a post that carries no quiz as missing")
    void shouldThrowNotFound_whenPostHasNoQuiz() {
      // Given — a post without a quiz. 404 here where submitQuiz raises 400 for the same
      // condition, because this is a GET of a sub-resource that is not there, whereas submitQuiz
      // is an action being attempted against it.
      PostEntity plainPost = new PostEntity();
      plainPost.setId(POST_ID);
      plainPost.setAuthorId(2);
      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(plainPost));

      // When / Then
      assertThatThrownBy(() -> quizService.getQuizForAuthor(2, POST_ID))
          .isInstanceOf(NotFoundException.class)
          .hasMessageContaining("does not contain a quiz");
    }

    @Test
    @DisplayName("should not consult the visibility service — authorship already decides")
    void shouldNotConsultVisibility_whenResolvingTheAuthor() {
      // Given — a PRIVATE post. submitQuiz asks PostVisibilityService because it serves readers;
      // this path serves only the author, who can always see their own post, so an extra check
      // would be a second rule that could disagree with the first.
      PostEntity post = postWithQuiz();
      post.setVisibility(PostVisibility.PRIVATE);
      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(post));

      // When
      quizService.getQuizForAuthor(post.getAuthorId(), POST_ID);

      // Then
      verifyNoInteractions(postVisibilityService);
    }
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
      when(postVisibilityService.isVisibleTo(any(), eq(USER_ID))).thenReturn(true);
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
      when(postVisibilityService.isVisibleTo(any(), eq(USER_ID))).thenReturn(true);
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
      when(postVisibilityService.isVisibleTo(any(), eq(USER_ID))).thenReturn(true);
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
      when(postVisibilityService.isVisibleTo(any(), eq(USER_ID))).thenReturn(true);

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
      when(postVisibilityService.isVisibleTo(any(), eq(USER_ID))).thenReturn(true);
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
      when(postVisibilityService.isVisibleTo(any(), eq(USER_ID))).thenReturn(true);
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
      when(postVisibilityService.isVisibleTo(any(), eq(USER_ID))).thenReturn(true);
      when(quizAnswerRepository.existsByPostIdAndUserId(POST_ID, USER_ID)).thenReturn(false);

      // When / Then
      assertThatThrownBy(() -> quizService.submitQuiz(USER_ID, POST_ID, new SubmitQuizRequestDto()))
          .isInstanceOf(ValidationException.class);
      verify(quizAnswerRepository, never()).save(any());
    }

    @Test
    @DisplayName("should reject a null answer element rather than crashing on it")
    void shouldThrowValidationException_whenAnAnswerElementIsNull() {
      // Given: SubmitQuizRequestDto carries no constraints at all, so {"selectedOptions":[null,1]}
      // satisfies @Valid and clears the size check — the element itself is never inspected.
      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(postWithQuiz()));
      when(postVisibilityService.isVisibleTo(any(), eq(USER_ID))).thenReturn(true);
      when(quizAnswerRepository.existsByPostIdAndUserId(POST_ID, USER_ID)).thenReturn(false);

      // When / Then: a caller-supplied value that the service cannot use is a 400-class fault, so
      // it must surface as ValidationException — not as the raw NullPointerException that
      // userAnswers.get(i).equals(...) throws today, which the catch-all handler reports as a 500.
      assertThatThrownBy(() -> quizService.submitQuiz(USER_ID, POST_ID, request(null, 1)))
          .isInstanceOf(ValidationException.class);
      verify(quizAnswerRepository, never()).save(any());
    }

    @Test
    @DisplayName("should refuse a quiz on a post the caller may not read")
    void shouldRefuse_whenThePostIsNotVisibleToTheCaller() {
      // Given: a PRIVATE post belonging to somebody else. Post ids are sequential, and this
      // response is the only place a quiz's correct answers and explanations are disclosed.
      PostEntity privatePost = postWithQuiz();
      privatePost.setAuthorId(4242);
      privatePost.setVisibility(PostVisibility.PRIVATE);
      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(privatePost));

      // When / Then: reaching a post by id must go through the same visibility rule every other
      // read path uses (PostVisibilityService.isVisibleTo). submitQuiz only checks existence, so
      // a stranger who guesses the id is graded and handed the answer key.
      assertThatThrownBy(() -> quizService.submitQuiz(USER_ID, POST_ID, request(1, 0)))
          .isInstanceOfAny(NotFoundException.class, ForbiddenException.class);
      verify(quizAnswerRepository, never()).save(any());
    }
  }
}
