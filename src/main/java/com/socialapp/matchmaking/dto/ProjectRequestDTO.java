package com.socialapp.matchmaking.dto;

import java.util.List;

import lombok.Data;

@Data
public class ProjectRequestDTO {
  private String title;
  private String description;
  private String bannerUrl;
  private List<ProjectPositionRequestDTO> positions;
}
