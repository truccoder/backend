package com.socialapp.posts.controller;

import org.springframework.web.bind.annotation.*;

import com.socialapp.posts.dto.QuizResultResponseDto;
import com.socialapp.posts.dto.SubmitQuizRequestDto;
import com.socialapp.posts.service.QuizService;
import com.socialapp.security.util.SecurityUtils;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/v1/api/posts/{postId}/quiz")
@RequiredArgsConstructor
public class QuizController {
  private final QuizService quizService;

  @PostMapping("/submit")
  public QuizResultResponseDto submitQuiz(
      @PathVariable Integer postId, @Valid @RequestBody SubmitQuizRequestDto request) {
    Integer userId = SecurityUtils.getCurrentUserId();
    return quizService.submitQuiz(userId, postId, request);
  }
}
