package com.socialapp.matchmaking.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ProjectRequestDTO {
  @NotBlank(message = "Title is required")
  private String title;

  @NotBlank(message = "Description is required")
  private String description;

  private String bannerUrl;

  /**
   * Optional. A project without tags is still valid — it simply scores 0 on the domain half of
   * {@code GET /v1/api/projects/suggested} and is carried there by skill overlap alone.
   */
  private List<String> tags;

  @Valid private List<ProjectPositionRequestDTO> positions;
}
