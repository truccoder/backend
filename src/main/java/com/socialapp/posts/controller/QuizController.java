package com.socialapp.posts.controller;

import org.springframework.web.bind.annotation.*;

import com.socialapp.posts.dto.QuizResultResponseDto;
import com.socialapp.posts.dto.SubmitQuizRequestDto;
import com.socialapp.posts.entity.QuizDetails;
import com.socialapp.posts.service.QuizService;
import com.socialapp.security.util.SecurityUtils;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/v1/api/posts/{postId}/quiz")
@RequiredArgsConstructor
public class QuizController {
  private final QuizService quizService;

  /**
   * The answer key, for the author of the post only — see {@code QuizService#getQuizForAuthor}.
   *
   * <p>Returns the {@code QuizDetails} entity rather than a DTO on purpose: it is exactly the type
   * {@code UpdatePostRequestDto#quizDetails} accepts, so the editor can send back what it read.
   */
  @GetMapping("/answers")
  public QuizDetails getQuizForAuthor(@PathVariable Integer postId) {
    return quizService.getQuizForAuthor(SecurityUtils.getCurrentUserId(), postId);
  }

  @PostMapping("/submit")
  public QuizResultResponseDto submitQuiz(
      @PathVariable Integer postId, @Valid @RequestBody SubmitQuizRequestDto request) {
    Integer userId = SecurityUtils.getCurrentUserId();
    return quizService.submitQuiz(userId, postId, request);
  }
}
