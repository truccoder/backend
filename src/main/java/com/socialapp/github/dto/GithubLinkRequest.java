package com.socialapp.github.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class GithubLinkRequest {
  @NotBlank(message = "Code is required")
  private String code;
}
