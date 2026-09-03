package com.socialapp.matchmaking.dto;

import com.socialapp.matchmaking.entity.enums.PositionStatus;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * Target state for {@code PATCH /v1/api/projects/positions/{positionId}/status}. Only {@code
 * OPEN} and {@code CLOSED} are meaningful for an owner to set by hand — {@code FILLED} is a
 * consequence of accepting the last seat, never a request. {@code ProjectService.updatePositionStatus}
 * rejects {@code FILLED} here and refuses to open a position that is already at capacity.
 */
@Data
public class UpdatePositionStatusRequestDTO {
  @NotNull(message = "Status is required")
  private PositionStatus status;
}
