package com.socialapp.matchmaking.dto;

import java.util.List;

import lombok.Data;

@Data
public class ProjectPositionRequestDTO {
  private String title;
  private String description;
  private List<String> requiredSkills;
  private Integer quantity;
}
