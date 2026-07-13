package com.socialapp.posts.dto;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class QuizResultResponseDto {
  private Integer score;
  private Integer totalQuestions;
  private List<Integer> correctAnswers;
}
