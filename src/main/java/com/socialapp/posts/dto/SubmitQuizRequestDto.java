package com.socialapp.posts.dto;

import java.util.List;

import lombok.Data;

@Data
public class SubmitQuizRequestDto {
  private List<Integer> selectedOptions;
}
