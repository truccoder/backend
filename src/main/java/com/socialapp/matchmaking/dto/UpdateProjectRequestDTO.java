package com.socialapp.matchmaking.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * The editable metadata of a project: everything on {@link ProjectRequestDTO} except its
 * positions. Positions are not edited through here — a bulk replace would have to decide what
 * happens to the applications attached to a position that vanished from the list, so each
 * position is added, edited and removed through its own endpoint instead.
 */
@Data
public class UpdateProjectRequestDTO {
  @NotBlank(message = "Title is required")
  private String title;

  @NotBlank(message = "Description is required")
  private String description;

  private String bannerUrl;

  /** Nullable — clearing every tag is a valid edit. See {@link ProjectRequestDTO#getTags()}. */
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
}
