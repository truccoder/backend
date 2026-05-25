package com.socialapp.security.dto;

import java.time.OffsetDateTime;

import org.springframework.http.HttpStatus;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class ErrorDto {
  private String message;
  private String path;
  private HttpStatus status;
  private OffsetDateTime timestamp = OffsetDateTime.now();

  public ErrorDto(String message, String path, HttpStatus status) {
    this.message = message;
    this.path = path;
    this.status = status;
    this.timestamp = OffsetDateTime.now();
  }
}
