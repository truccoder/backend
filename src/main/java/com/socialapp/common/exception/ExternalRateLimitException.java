package com.socialapp.common.exception;

/**
 * A third-party API refused the call because we are over its rate limit or quota — an HTTP 429, or
 * Gemini's {@code RESOURCE_EXHAUSTED} — as distinct from the dependency being down, timing out, or
 * returning a shape we cannot use.
 *
 * <p>Extends {@link ExternalApiException} so every existing {@code catch (ExternalApiException)}
 * keeps working unchanged, but {@link GlobalExceptionHandler} maps this subclass to <b>429 Too
 * Many Requests</b> instead of 503. The distinction is not cosmetic: retrying a 503 can succeed a
 * moment later, whereas retrying a 429 immediately just spends more of an allowance that is
 * already gone. The frontend reads the two apart on status alone — 429 hides its retry button,
 * 503 keeps it (see {@code explain-post-action.tsx}, backend-plan B32).
 */
public class ExternalRateLimitException extends ExternalApiException {
  public ExternalRateLimitException(String message) {
    super(message);
  }

  public ExternalRateLimitException(String message, Throwable cause) {
    super(message, cause);
  }
}
