package com.socialapp.common.exception;

import java.time.OffsetDateTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.socialapp.moderation.dto.BanDetailsDto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ErrorResponseDto {
  private int code;
  private String error;
  private String message;
  private String path;
  @Builder.Default private OffsetDateTime timestamp = OffsetDateTime.now();
  private List<String> details;

  /**
   * Set only on the 403 that refuses a banned account (see {@code BanDetailsDto}); {@code NON_NULL}
   * so it stays out of every other error body.
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  private BanDetailsDto banDetails;
}
