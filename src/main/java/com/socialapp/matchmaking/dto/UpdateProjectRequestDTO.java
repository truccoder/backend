package com.socialapp.matchmaking.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
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
}
