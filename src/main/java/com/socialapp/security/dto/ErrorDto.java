package com.socialapp.security.dto;

import java.time.OffsetDateTime;

import org.springframework.http.HttpStatus;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.socialapp.moderation.dto.BanDetailsDto;

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

  /**
   * Set only on the 403 that refuses a banned account; absent from every other error.
   *
   * <p>{@code NON_NULL} so the field does not appear as {@code "banDetails": null} on the many
   * errors that have nothing to do with a ban — a client checking for its presence should not have
   * to distinguish "missing" from "present and null".
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  private BanDetailsDto banDetails;

  public ErrorDto(String message, String path, HttpStatus status) {
    this.message = message;
    this.path = path;
    this.status = status;
    this.timestamp = OffsetDateTime.now();
  }

  public ErrorDto(String message, String path, HttpStatus status, BanDetailsDto banDetails) {
    this(message, path, status);
    this.banDetails = banDetails;
  }
}
