package com.socialapp.posts.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialapp.common.exception.ForbiddenException;
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

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class QuizService {
  private final PostRepository postRepository;
  private final QuizAnswerRepository quizAnswerRepository;
  private final PostVisibilityService postVisibilityService;

  /**
   * The quiz WITH its answers, for the post's own author.
   *
   * <p>Exists because editing a quiz was otherwise impossible without destroying it. The feed and
   * the permalink both serve {@link com.socialapp.posts.dto.PublicQuizDetailsDto}, which omits
   * {@code correctOptionIndex} and {@code explanation} by design — so an author who opened their
   * own quiz to fix a typo had no way to read back the answers the update request requires, and
   * {@code PostService#validateQuizDetails} rejects a quiz whose answers are missing.
   *
   * <p>Deliberately NOT a field on {@code FeedPostDataDto}. That payload is cached in Redis as one
   * shared copy for every reader and {@code GET /v1/api/posts/&#123;postId&#125;} is open to
   * guests, so an "author only" field on it would hand the answer key to everybody who read the
   * post next.
   *
   * <p>403 rather than the 404 the other quiz paths use: those hide a post's existence from
   * someone who may not read it, whereas this one is only ever called by a caller who already
   * knows the post — and if they are not its author there is nothing to conceal.
   */
  @Transactional(readOnly = true)
  public QuizDetails getQuizForAuthor(Integer actorId, Integer postId) {
    PostEntity post =
        postRepository
            .findById(postId)
            .orElseThrow(() -> new NotFoundException("Post not found with ID: " + postId));

    if (!post.getAuthorId().equals(actorId)) {
      throw new ForbiddenException("Only the author can read this quiz's answers");
    }

    // 404, not the 400 submitQuiz raises for the same condition: this is a GET of a sub-resource
    // that does not exist, whereas submitQuiz is an action being attempted against it.
    if (post.getQuizDetails() == null) {
      throw new NotFoundException("Post " + postId + " does not contain a quiz");
    }

    return post.getQuizDetails();
  }

  @Transactional
  public QuizResultResponseDto submitQuiz(
      Integer userId, Integer postId, SubmitQuizRequestDto request) {
    PostEntity post =
        postRepository
            .findById(postId)
            .orElseThrow(() -> new NotFoundException("Post not found with ID: " + postId));

    // Reaching a post by id has to clear the same visibility rule every other read path uses.
    // Existence alone was not enough here: post ids are sequential, and this response is the one
    // place a quiz's correct answers and explanations are disclosed (see PublicQuizDetailsDto),
    // so a stranger who guessed an id was graded on a PRIVATE post and handed the answer key.
    // 404 rather than 403, matching PostReactionService#requireVisiblePost — a post you may not
    // read must not be distinguishable from one that does not exist.
    if (!postVisibilityService.isVisibleTo(post, userId)) {
      throw new NotFoundException("Post not found with ID: " + postId);
    }

    if (post.getQuizDetails() == null) {
      throw new ValidationException("This post does not contain a quiz");
    }

    if (quizAnswerRepository.existsByPostIdAndUserId(postId, userId)) {
      throw new ValidationException("You have already submitted answers for this quiz");
    }

    QuizDetails quiz = post.getQuizDetails();
    List<Integer> userAnswers = request.getSelectedOptions();

    if (userAnswers == null || userAnswers.size() != quiz.getQuestions().size()) {
      throw new ValidationException(
          "You must provide exactly " + quiz.getQuestions().size() + " answers");
    }

    // SubmitQuizRequestDto carries no constraints, so {"selectedOptions":[null,1]} satisfies
    // @Valid and clears the size check above. Without this, the null reached
    // userAnswers.get(i).equals(...) below and threw a raw NullPointerException, which the
    // catch-all handler reported as a 500 — a caller-supplied value the service cannot use is a
    // 400-class fault.
    if (userAnswers.contains(null)) {
      throw new ValidationException("Every answer must be an option index");
    }

    // Scoring has always happened here, against the row rather than against anything the client
    // sent — that part was never the problem. What changed is that the answers no longer travel
    // out with the question (see PublicQuizDetailsDto), so this response is the first and only
    // time the reader learns them, explanations included.
    int score = 0;
    List<Integer> correctAnswers = new ArrayList<>();
    List<String> explanations = new ArrayList<>();
    for (int i = 0; i < quiz.getQuestions().size(); i++) {
      QuizQuestion q = quiz.getQuestions().get(i);
      correctAnswers.add(q.getCorrectOptionIndex());
      explanations.add(q.getExplanation());
      if (userAnswers.get(i).equals(q.getCorrectOptionIndex())) {
        score++;
      }
    }

    QuizAnswerEntity answerEntity = new QuizAnswerEntity();
    answerEntity.setPostId(postId);
    answerEntity.setUserId(userId);
    answerEntity.setAnswers(userAnswers);
    answerEntity.setScore(score);
    quizAnswerRepository.save(answerEntity);

    return new QuizResultResponseDto(
        score, quiz.getQuestions().size(), correctAnswers, explanations);
  }
}
