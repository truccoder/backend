package com.socialapp.matchmaking.dto;

import java.util.List;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ProjectPositionRequestDTO {
  @NotBlank(message = "Position title is required")
  private String title;

  private String description;
  private List<String> requiredSkills;

  @Min(value = 1, message = "Quantity must be at least 1")
  private Integer quantity;
}
