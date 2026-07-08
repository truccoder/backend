package com.socialapp.common.exception;

import static java.lang.String.format;
import static java.util.stream.Collectors.toList;
import static org.springframework.http.HttpStatus.*;

import java.util.Objects;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import com.socialapp.moderation.exception.ContentViolationException;
import com.socialapp.moderation.exception.UserBannedException;
import com.socialapp.security.exception.AccountBannedException;

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
  @ExceptionHandler(AccountBannedException.class)
  public ErrorResponseDto handle(AccountBannedException ex, HttpServletRequest request) {
    writeLog(ex, request);

    return ErrorResponseDto.builder()
        .code(FORBIDDEN.value())
        .error("Account Banned")
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

  // Covers @RequestParam/@PathVariable constraint annotations (e.g. @Positive int page) that
  // Spring validates automatically since Spring Framework 6.1, independent of whether the
  // controller class carries @Validated. Without this handler these fell through to the
  // generic Exception handler below and were incorrectly reported as 500.
  @ResponseStatus(BAD_REQUEST)
  @ExceptionHandler(HandlerMethodValidationException.class)
  public ErrorResponseDto handle(HandlerMethodValidationException ex, HttpServletRequest request) {
    writeLog(ex, request);

    return ErrorResponseDto.builder()
        .code(BAD_REQUEST.value())
        .error(BAD_REQUEST.getReasonPhrase())
        .message(INVALIDATION_MESSAGE)
        .path(request.getRequestURI())
        .build();
  }

  // Covers e.g. ?status=FOO failing to convert to a @RequestParam enum — previously fell
  // through to the generic Exception handler and was incorrectly reported as 500.
  @ResponseStatus(BAD_REQUEST)
  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  public ErrorResponseDto handle(
      MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
    writeLog(ex, request);

    String message = format("Invalid value '%s' for parameter '%s'", ex.getValue(), ex.getName());

    return ErrorResponseDto.builder()
        .code(BAD_REQUEST.value())
        .error(BAD_REQUEST.getReasonPhrase())
        .message(message)
        .path(request.getRequestURI())
        .build();
  }

  // Covers service-layer "wrong current state for this action" checks (e.g. reviewing a post
  // that isn't PENDING_REVIEW anymore) — previously fell through to the generic Exception
  // handler and was incorrectly reported as 500 instead of a 409 Conflict.
  @ResponseStatus(CONFLICT)
  @ExceptionHandler(IllegalStateException.class)
  public ErrorResponseDto handle(IllegalStateException ex, HttpServletRequest request) {
    writeLog(ex, request);

    return ErrorResponseDto.builder()
        .code(CONFLICT.value())
        .error(CONFLICT.getReasonPhrase())
        .message(ex.getMessage())
        .path(request.getRequestURI())
        .build();
  }

  // Safety net for unique/FK constraint violations that reach the DB despite an app-level
  // pre-check (e.g. two concurrent requests racing past the same read-then-write check) —
  // previously fell through to the generic Exception handler and was reported as 500.
  @ResponseStatus(CONFLICT)
  @ExceptionHandler(DataIntegrityViolationException.class)
  public ErrorResponseDto handle(DataIntegrityViolationException ex, HttpServletRequest request) {
    writeLog(ex, request);

    return ErrorResponseDto.builder()
        .code(CONFLICT.value())
        .error(CONFLICT.getReasonPhrase())
        .message("This action conflicts with existing data")
        .path(request.getRequestURI())
        .build();
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

  @ResponseStatus(PAYLOAD_TOO_LARGE)
  @ExceptionHandler(MaxUploadSizeExceededException.class)
  public ErrorResponseDto handle(MaxUploadSizeExceededException ex, HttpServletRequest request) {
    writeLog(ex, request);

    return ErrorResponseDto.builder()
        .code(PAYLOAD_TOO_LARGE.value())
        .error(PAYLOAD_TOO_LARGE.getReasonPhrase())
        .message("Maximum upload size exceeded")
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
