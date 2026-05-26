package com.socialapp.common.exception;

import java.time.OffsetDateTime;
import java.util.List;

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
}
