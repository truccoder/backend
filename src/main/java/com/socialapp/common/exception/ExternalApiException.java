package com.socialapp.common.exception;

/**
 * Thrown when a call to a third-party API (GitHub, Google, Gemini, etc.) fails or returns a
 * response the app can't use — a network/HTTP failure, or a 200 response missing the fields the
 * caller needed. Never the caller's fault, so it's surfaced as a retryable 503 rather than a 500,
 * matching {@link StorageException}/{@link PaymentException} for other downstream dependencies.
 */
public class ExternalApiException extends RuntimeException {
  public ExternalApiException(String message) {
    super(message);
  }

  public ExternalApiException(String message, Throwable cause) {
    super(message, cause);
  }
}
