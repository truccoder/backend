package com.socialapp.roadmap.dto;

import lombok.Data;

@Data
public class RoadmapNodeDto {
  private Integer id;
  private Integer roadmapId;
  private String name;
  private String description;
  private Integer parentNodeId;
  private Integer orderIndex;
}
