package com.socialapp.matchmaking.dto;

import com.socialapp.matchmaking.entity.enums.ProjectStatus;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * Target state for {@code PATCH /v1/api/projects/{projectId}/status}. Any of the three values is
 * accepted at the DTO layer; which transitions are legal from the project's current state is
 * {@code ProjectService.updateStatus}'s call, not bean validation's.
 */
@Data
public class UpdateProjectStatusRequestDTO {
  @NotNull(message = "Status is required")
  private ProjectStatus status;
}
