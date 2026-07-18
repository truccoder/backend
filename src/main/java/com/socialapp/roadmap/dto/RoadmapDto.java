package com.socialapp.roadmap.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class RoadmapDto {
  private Integer id;

  @NotBlank(message = "Name is required")
  private String name;

  private String description;
}
