package com.socialapp.roadmap.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class RoadmapNodeDto {
  private Integer id;
  private Integer roadmapId;

  @NotBlank(message = "Name is required")
  private String name;

  private String description;
  private Integer parentNodeId;
  private Integer orderIndex;
}
