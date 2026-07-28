package com.socialapp.posts.dto;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * The only place a quiz's answers are allowed to reach a reader.
 *
 * <p>{@code explanations} is served from here rather than from the post payload for the same
 * reason {@code correctAnswers} always was: an explanation names the answer. Shipping it beside
 * the question would have re-opened the leak that {@link PublicQuizDetailsDto} closes. One entry
 * per question, positionally aligned with {@code correctAnswers}; an entry is null where the
 * author wrote no explanation.
 */
@Data
@AllArgsConstructor
public class QuizResultResponseDto {
  private Integer score;
  private Integer totalQuestions;
  private List<Integer> correctAnswers;
  private List<String> explanations;
}
