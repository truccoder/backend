package com.socialapp.matchmaking.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
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

  /**
   * The company half of a job description: who the team is, and how they work. Written once here
   * rather than repeated in every role — see {@code ProjectPositionRequestDTO} for the split.
   *
   * <p>Optional. Plenty of projects on this board are two students and a repository, and a form
   * that demands a company overview from them gets an invented one.
   */
  @Size(max = 4000, message = "Company overview must be at most {max} characters")
  private String companyOverview;

  @Size(max = 4000, message = "Company culture must be at most {max} characters")
  private String companyCulture;

  @Valid private List<ProjectPositionRequestDTO> positions;
}
