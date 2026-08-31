package com.socialapp.common.ratelimit;

import com.fasterxml.jackson.core.io.JsonStringEncoder;

import lombok.experimental.UtilityClass;

/**
 * The 429 body the two rate-limit filters write.
 *
 * <p>A filter runs before Spring MVC has picked a handler, so it cannot return an {@code
 * ErrorResponseDto} and let the configured {@code HttpMessageConverter} render it the way {@code
 * GlobalExceptionHandler} does — it has to write bytes to the response itself. Both filters did
 * that by interpolating {@code request.getRequestURI()} straight into a JSON string literal, which
 * is the one place in the application where a request-controlled value reached a response body
 * without going through Jackson.
 *
 * <p>Not exploitable as it stood: Tomcat rejects a request line containing a raw double quote
 * before any filter sees it, so the quote needed to break out of the string never arrived. That is
 * a property of the container in front, though, not of this code — and it says nothing about the
 * backslashes and control characters a URI can legitimately carry. Escaping it here costs nothing
 * and stops the correctness of the response depending on what Tomcat happens to reject.
 */
@UtilityClass
class RateLimitErrorBody {

  /** The JSON body for a 429, with {@code path} escaped as a JSON string value. */
  static String tooManyRequests(String message, String path) {
    return "{\"code\":429,\"error\":\"Too Many Requests\",\"message\":\""
        + escape(message)
        + "\",\"path\":\""
        + escape(path)
        + "\"}";
  }

  private static String escape(String value) {
    if (value == null) {
      return "";
    }
    return new String(JsonStringEncoder.getInstance().quoteAsString(value));
  }
}
