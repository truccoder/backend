package com.socialapp.posts.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

  @Transactional
  public QuizResultResponseDto submitQuiz(
      Integer userId, Integer postId, SubmitQuizRequestDto request) {
    PostEntity post =
        postRepository
            .findById(postId)
            .orElseThrow(() -> new NotFoundException("Post not found with ID: " + postId));

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
