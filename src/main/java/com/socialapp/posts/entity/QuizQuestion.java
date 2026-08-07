package com.socialapp.posts.entity;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class QuizQuestion {
  private String question;
  private List<String> options;
  private Integer correctOptionIndex;
  private String explanation;
}
