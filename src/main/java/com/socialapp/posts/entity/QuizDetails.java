package com.socialapp.posts.entity;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class QuizDetails {
  private String title;
  private List<QuizQuestion> questions;
}
