package com.socialapp.common.exception;

import static java.lang.String.format;
import static java.util.stream.Collectors.toList;
import static org.springframework.http.HttpStatus.*;

import java.util.Objects;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.socialapp.moderation.exception.ContentViolationException;
import com.socialapp.moderation.exception.UserBannedException;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

  private static final String INVALID_CREDS_MESSAGE = "Invalid credentials";
  private static final String INVALIDATION_MESSAGE = "Invalid request parameters or payload";
  private static final String FIELD_VALIDATION_MSG_TEMPLATE = "Property %s: %s";
  private static final String PAYLOAD_VALIDATION_MSG_TEMPLATE = "Payload: %s";

  @ResponseStatus(FORBIDDEN)
  @ExceptionHandler(UserBannedException.class)
  public ErrorResponseDto handle(UserBannedException ex, HttpServletRequest request) {
    writeLog(ex, request);

    return ErrorResponseDto.builder()
        .code(FORBIDDEN.value())
        .error("Account Restricted")
        .message(ex.getMessage())
        .path(request.getRequestURI())
        .build();
  }

  @ResponseStatus(BAD_REQUEST)
  @ExceptionHandler(ContentViolationException.class)
  public ErrorResponseDto handle(ContentViolationException ex, HttpServletRequest request) {
    writeLog(ex, request);

    return ErrorResponseDto.builder()
        .code(BAD_REQUEST.value())
        .error("Content Violation")
        .message(ex.getMessage())
        .path(request.getRequestURI())
        .build();
  }

  @ResponseStatus(FORBIDDEN)
  @ExceptionHandler(ForbiddenException.class)
  public ErrorResponseDto handle(ForbiddenException ex, HttpServletRequest request) {
    writeLog(ex, request);

    return ErrorResponseDto.builder()
        .code(FORBIDDEN.value())
        .error(FORBIDDEN.getReasonPhrase())
        .message(ex.getMessage())
        .path(request.getRequestURI())
        .build();
  }

  @ResponseStatus(BAD_REQUEST)
  @ExceptionHandler(ValidationException.class)
  public ErrorResponseDto handle(ValidationException ex, HttpServletRequest request) {
    writeLog(ex, request);

    return ErrorResponseDto.builder()
        .code(BAD_REQUEST.value())
        .error(BAD_REQUEST.getReasonPhrase())
        .message(ex.getMessage())
        .path(request.getRequestURI())
        .build();
  }

  @ResponseStatus(UNPROCESSABLE_ENTITY)
  @ExceptionHandler(jakarta.validation.ValidationException.class)
  protected ErrorResponseDto handle(
      jakarta.validation.ValidationException ex, HttpServletRequest request) {
    writeLog(ex, request);

    return ErrorResponseDto.builder()
        .code(UNPROCESSABLE_ENTITY.value())
        .error(UNPROCESSABLE_ENTITY.getReasonPhrase())
        .message(ex.getMessage())
        .path(request.getRequestURI())
        .build();
  }

  @ResponseStatus(NOT_FOUND)
  @ExceptionHandler(NotFoundException.class)
  public ErrorResponseDto handle(NotFoundException ex, HttpServletRequest request) {
    writeLog(ex, request);

    return ErrorResponseDto.builder()
        .code(NOT_FOUND.value())
        .error(NOT_FOUND.getReasonPhrase())
        .message(ex.getMessage())
        .path(request.getRequestURI())
        .build();
  }

  @ResponseStatus(UNAUTHORIZED)
  @ExceptionHandler(AuthenticationException.class)
  public ErrorResponseDto handle(AuthenticationException ex, HttpServletRequest request) {
    writeLog(ex, request);

    return ErrorResponseDto.builder()
        .code(UNAUTHORIZED.value())
        .error(UNAUTHORIZED.getReasonPhrase())
        .message(INVALID_CREDS_MESSAGE)
        .path(request.getRequestURI())
        .build();
  }

  @ResponseStatus(UNPROCESSABLE_ENTITY)
  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ErrorResponseDto handle(MethodArgumentNotValidException ex, HttpServletRequest request) {
    writeLog(ex, request);

    ErrorResponseDto errorDto =
        ErrorResponseDto.builder()
            .code(UNPROCESSABLE_ENTITY.value())
            .error(UNPROCESSABLE_ENTITY.getReasonPhrase())
            .message(INVALIDATION_MESSAGE)
            .path(request.getRequestURI())
            .build();

    errorDto.setDetails(
        ex.getBindingResult().getFieldErrors().stream()
            .map(this::formatFieldError)
            .collect(toList()));

    return errorDto;
  }

  @ResponseStatus(FORBIDDEN)
  @ExceptionHandler(AccessDeniedException.class)
  public ErrorResponseDto handle(AccessDeniedException ex, HttpServletRequest request) {
    writeLog(ex, request);

    return ErrorResponseDto.builder()
        .code(FORBIDDEN.value())
        .error(FORBIDDEN.getReasonPhrase())
        .message(ex.getMessage())
        .path(request.getRequestURI())
        .build();
  }

  @ResponseStatus(INTERNAL_SERVER_ERROR)
  @ExceptionHandler(Exception.class)
  public ErrorResponseDto handle(Exception ex, HttpServletRequest request) {
    writeLog(ex, request);

    return ErrorResponseDto.builder()
        .code(INTERNAL_SERVER_ERROR.value())
        .error(INTERNAL_SERVER_ERROR.getReasonPhrase())
        .message(ex.getMessage())
        .path(request.getRequestURI())
        .build();
  }

  private String formatFieldError(FieldError fieldError) {
    if (Objects.nonNull(fieldError.getField())) {
      return format(
          FIELD_VALIDATION_MSG_TEMPLATE, fieldError.getField(), fieldError.getDefaultMessage());
    }

    return format(PAYLOAD_VALIDATION_MSG_TEMPLATE, fieldError.getDefaultMessage());
  }

  private void writeLog(Exception ex, HttpServletRequest request) {
    log.error(
        "Exception occurred while processing request [{} {}]",
        request.getMethod(),
        request.getRequestURI(),
        ex);
  }
}
