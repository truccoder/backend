package com.socialapp.moderation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** What a user submits to challenge one violation recorded against them. */
@Data
public class AppealRequestDto {

  /** Which violation is being appealed — from {@code GET /v1/api/moderation/my-violations}. */
  @NotNull private Long violationId;

  /**
   * Bounded to match the {@code VARCHAR(2000)} column. Validating here as well as in the schema
   * turns "too long" into a 422 naming the field, rather than a driver-level truncation error.
   */
  @NotBlank
  @Size(max = 2000)
  private String reason;
}
