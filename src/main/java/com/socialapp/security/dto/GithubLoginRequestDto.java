package com.socialapp.security.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class GithubLoginRequestDto {
  @NotBlank(message = "Code is required")
  private String code;
}
