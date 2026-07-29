package com.socialapp.common.exception;

import static java.lang.String.format;
import static java.util.stream.Collectors.toList;
import static org.springframework.http.HttpStatus.*;

import java.util.LinkedHashSet;
import java.util.Objects;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;

import com.socialapp.moderation.exception.ContentViolationException;
import com.socialapp.moderation.exception.UserBannedException;
import com.socialapp.security.exception.AccountBannedException;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;

/**
 * Centralized exception -&gt; HTTP response mapping for every controller.
 *
 * <p><b>400 vs 422 is an intentional split, not an inconsistency:</b> {@code @RequestParam}/{@code
 * @PathVariable} constraint failures ({@link HandlerMethodValidationException}, {@link
 * MethodArgumentTypeMismatchException}) return <b>400 Bad Request</b> — the request itself is
 * malformed (wrong type, out-of-range query param). {@code @RequestBody @Valid} failures ({@link
 * MethodArgumentNotValidException}) return <b>422 Unprocessable Entity</b> — the request is
 * syntactically well-formed JSON, but a field's value fails a semantic constraint. Reviewed and
 * confirmed as the intended behavior; left as-is rather than collapsed to a single status code.
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

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

  // No @ResponseStatus here: ResponseStatusException carries its own status/reason dynamically,
  // so it must be honored via ResponseEntity instead of a fixed annotation value.
  @ExceptionHandler(ResponseStatusException.class)
  public ResponseEntity<ErrorResponseDto> handle(
      ResponseStatusException ex, HttpServletRequest request) {
    writeLog(ex, request);

    HttpStatus status = HttpStatus.valueOf(ex.getStatusCode().value());
    String message = ex.getReason() != null ? ex.getReason() : status.getReasonPhrase();

    return ResponseEntity.status(status)
        .body(
            ErrorResponseDto.builder()
                .code(status.value())
                .error(status.getReasonPhrase())
                .message(message)
                .path(request.getRequestURI())
                .build());
  }

  @ResponseStatus(UNAUTHORIZED)
  @ExceptionHandler(AuthenticationException.class)
  public ErrorResponseDto handle(AuthenticationException ex, HttpServletRequest request) {
    writeLog(ex, request);

    return ErrorResponseDto.builder()
        .code(UNAUTHORIZED.value())
        .error(UNAUTHORIZED.getReasonPhrase())
        .message(ex.getMessage())
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

  // Covers manual parsing of a request input that turns out not to be numeric (e.g.
  // EventController#handleGoogleCallback doing Integer.parseInt(state) itself, outside of
  // Spring's own @RequestParam/@PathVariable type conversion, which
  // MethodArgumentTypeMismatchException already covers above). Previously fell through to the
  // generic Exception handler and was incorrectly reported as 500.
  @ResponseStatus(BAD_REQUEST)
  @ExceptionHandler(NumberFormatException.class)
  public ErrorResponseDto handle(NumberFormatException ex, HttpServletRequest request) {
    writeLog(ex, request);

    return ErrorResponseDto.builder()
        .code(BAD_REQUEST.value())
        .error(BAD_REQUEST.getReasonPhrase())
        .message("Invalid numeric value in request")
        .path(request.getRequestURI())
        .build();
  }

  // Covers a required @RequestParam that's omitted entirely (e.g. GET /v1/api/search with no
  // "q" at all) — distinct from a param that's present but fails a constraint like @NotBlank,
  // which HandlerMethodValidationException/ConstraintViolationException handle instead.
  // Previously fell through to the generic Exception handler and was incorrectly reported as 500.
  @ResponseStatus(BAD_REQUEST)
  @ExceptionHandler(MissingServletRequestParameterException.class)
  public ErrorResponseDto handle(
      MissingServletRequestParameterException ex, HttpServletRequest request) {
    writeLog(ex, request);

    String message = format("Missing required parameter '%s'", ex.getParameterName());

    return ErrorResponseDto.builder()
        .code(BAD_REQUEST.value())
        .error(BAD_REQUEST.getReasonPhrase())
        .message(message)
        .path(request.getRequestURI())
        .build();
  }

  // Covers a required @RequestHeader that's omitted entirely (e.g. KnowledgeSyncController's
  // Authorization header, which every other endpoint gets via JwtAuthenticationFilter instead).
  // Previously fell through to the generic Exception handler and was incorrectly reported as 500.
  @ResponseStatus(BAD_REQUEST)
  @ExceptionHandler(MissingRequestHeaderException.class)
  public ErrorResponseDto handle(MissingRequestHeaderException ex, HttpServletRequest request) {
    writeLog(ex, request);

    String message = format("Missing required header '%s'", ex.getHeaderName());

    return ErrorResponseDto.builder()
        .code(BAD_REQUEST.value())
        .error(BAD_REQUEST.getReasonPhrase())
        .message(message)
        .path(request.getRequestURI())
        .build();
  }

  // Covers a request body that isn't parseable JSON at all (syntax error, truncated body, wrong
  // structure) — distinct from MethodArgumentNotValidException, where the JSON parses fine but a
  // field fails a Bean Validation constraint. Previously fell through to the generic Exception
  // handler and was incorrectly reported as 500 for what is really a malformed client request.
  @ResponseStatus(BAD_REQUEST)
  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ErrorResponseDto handle(HttpMessageNotReadableException ex, HttpServletRequest request) {
    writeLog(ex, request);

    return ErrorResponseDto.builder()
        .code(BAD_REQUEST.value())
        .error(BAD_REQUEST.getReasonPhrase())
        .message("Malformed request body")
        .path(request.getRequestURI())
        .build();
  }

  // Covers a request body sent with a missing or unsupported Content-Type (e.g. no header at
  // all, or text/plain instead of application/json), so Spring can't pick an HttpMessageConverter
  // for the @RequestBody parameter. Previously fell through to the generic Exception handler and
  // was incorrectly reported as 500.
  @ResponseStatus(UNSUPPORTED_MEDIA_TYPE)
  @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
  public ErrorResponseDto handle(
      HttpMediaTypeNotSupportedException ex, HttpServletRequest request) {
    writeLog(ex, request);

    return ErrorResponseDto.builder()
        .code(UNSUPPORTED_MEDIA_TYPE.value())
        .error(UNSUPPORTED_MEDIA_TYPE.getReasonPhrase())
        .message("Content-Type must be application/json")
        .path(request.getRequestURI())
        .build();
  }

  // Wrong verb on a path that exists — e.g. a client still on PATCH after /qna/accept-answer moved
  // to POST. Fell through to the generic Exception handler and came back 500, which reads like the
  // server broke rather than "you called it with the wrong method".
  // Returns ResponseEntity, unlike every other handler here, because RFC 9110 §15.5.6 makes the
  // Allow header mandatory on a 405 and @ResponseStatus alone cannot set it.
  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  public ResponseEntity<ErrorResponseDto> handle(
      HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {
    writeLog(ex, request);

    HttpHeaders headers = new HttpHeaders();
    if (ex.getSupportedHttpMethods() != null) {
      headers.setAllow(new LinkedHashSet<>(ex.getSupportedHttpMethods()));
    }
    return ResponseEntity.status(METHOD_NOT_ALLOWED)
        .headers(headers)
        .body(
            ErrorResponseDto.builder()
                .code(METHOD_NOT_ALLOWED.value())
                .error(METHOD_NOT_ALLOWED.getReasonPhrase())
                .message("Method " + ex.getMethod() + " is not supported for this endpoint")
                .path(request.getRequestURI())
                .build());
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

  // Thrown by @Version-guarded entities (e.g. ProjectPositionEntity) when two concurrent
  // requests race to update the same row — the second one to commit loses instead of silently
  // overwriting the first, so surface it as a retryable conflict rather than a 500.
  @ResponseStatus(CONFLICT)
  @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
  public ErrorResponseDto handle(
      ObjectOptimisticLockingFailureException ex, HttpServletRequest request) {
    writeLog(ex, request);

    return ErrorResponseDto.builder()
        .code(CONFLICT.value())
        .error(CONFLICT.getReasonPhrase())
        .message("This was just updated by someone else, please retry")
        .path(request.getRequestURI())
        .build();
  }

  // Downstream storage failure (MinIO timeout, connection refused, server error, etc.) — the
  // client's request was fine, the storage backend is the one having trouble, so 503 (not 500)
  // signals this is transient/retryable rather than a bug in our own request handling.
  @ResponseStatus(SERVICE_UNAVAILABLE)
  @ExceptionHandler(StorageException.class)
  public ErrorResponseDto handle(StorageException ex, HttpServletRequest request) {
    writeLog(ex, request);

    return ErrorResponseDto.builder()
        .code(SERVICE_UNAVAILABLE.value())
        .error(SERVICE_UNAVAILABLE.getReasonPhrase())
        .message(ex.getMessage())
        .path(request.getRequestURI())
        .build();
  }

  // Same rationale as StorageException, for the payment gateway (MoMo) instead of MinIO — a
  // downstream/transient failure, not a bug in our own request handling, hence 503.
  @ResponseStatus(SERVICE_UNAVAILABLE)
  @ExceptionHandler(PaymentException.class)
  public ErrorResponseDto handle(PaymentException ex, HttpServletRequest request) {
    writeLog(ex, request);

    return ErrorResponseDto.builder()
        .code(SERVICE_UNAVAILABLE.value())
        .error(SERVICE_UNAVAILABLE.getReasonPhrase())
        .message(ex.getMessage())
        .path(request.getRequestURI())
        .build();
  }

  // Same rationale as StorageException/PaymentException, for third-party APIs (GitHub, Google,
  // Gemini) instead of MinIO/MoMo — the call failed or returned something we can't use, not a bug
  // in our own request handling, hence 503.
  @ResponseStatus(SERVICE_UNAVAILABLE)
  @ExceptionHandler(ExternalApiException.class)
  public ErrorResponseDto handle(ExternalApiException ex, HttpServletRequest request) {
    writeLog(ex, request);

    return ErrorResponseDto.builder()
        .code(SERVICE_UNAVAILABLE.value())
        .error(SERVICE_UNAVAILABLE.getReasonPhrase())
        .message(ex.getMessage())
        .path(request.getRequestURI())
        .build();
  }

  // A required server-side secret/key is unset (e.g. Stream Chat's api-secret) — never the
  // caller's fault, and must not be silently masked by a fallback value. 503 signals the feature
  // itself is unavailable until an operator fixes the deployment config, same rationale as
  // StorageException/PaymentException above.
  @ResponseStatus(SERVICE_UNAVAILABLE)
  @ExceptionHandler(MissingConfigurationException.class)
  public ErrorResponseDto handle(MissingConfigurationException ex, HttpServletRequest request) {
    writeLog(ex, request);

    return ErrorResponseDto.builder()
        .code(SERVICE_UNAVAILABLE.value())
        .error(SERVICE_UNAVAILABLE.getReasonPhrase())
        .message(ex.getMessage())
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
