package com.socialapp.moderation.dto;

import com.socialapp.moderation.enums.Likelihood;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class AdminReviewRequestDto {
  @NotNull private Likelihood decision;

  private String feedback;
}
