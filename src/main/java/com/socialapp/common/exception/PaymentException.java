package com.socialapp.common.exception;

/**
 * Thrown when a payment gateway operation (MoMo API call, HMAC signature generation, etc.) fails
 * for reasons outside the caller's control — a downstream outage, timeout, or misconfigured
 * gateway credential. Distinct from {@link ValidationException}, which covers rejections that are
 * the caller's own fault (e.g. buying a free book, an already-completed purchase).
 */
public class PaymentException extends RuntimeException {
  public PaymentException(String message) {
    super(message);
  }

  public PaymentException(String message, Throwable cause) {
    super(message, cause);
  }
}
