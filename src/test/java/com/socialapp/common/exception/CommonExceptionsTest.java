package com.socialapp.common.exception;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Component (unit) tests for the small custom {@link RuntimeException} subclasses in this
 * package, per ISTQB CTFL v4.0.1 Section 2.2.1. Every public constructor is exercised directly —
 * including the {@code (message, cause)} overloads, which no current call site uses but which
 * remain part of each class's public contract for future callers that need to preserve a cause.
 */
class CommonExceptionsTest {

  @Nested
  @DisplayName("NotFoundException")
  class NotFoundExceptionTests {

    @Test
    @DisplayName("should carry the given message")
    void shouldCarryMessage() {
      NotFoundException ex = new NotFoundException("not found");
      assertThat(ex.getMessage()).isEqualTo("not found");
      assertThat(ex.getCause()).isNull();
    }

    @Test
    @DisplayName("should carry both the message and the cause")
    void shouldCarryMessageAndCause() {
      Throwable cause = new IllegalStateException("root cause");
      NotFoundException ex = new NotFoundException("not found", cause);
      assertThat(ex.getMessage()).isEqualTo("not found");
      assertThat(ex.getCause()).isSameAs(cause);
    }
  }

  @Nested
  @DisplayName("ForbiddenException")
  class ForbiddenExceptionTests {

    @Test
    @DisplayName("should carry the given message")
    void shouldCarryMessage() {
      ForbiddenException ex = new ForbiddenException("forbidden");
      assertThat(ex.getMessage()).isEqualTo("forbidden");
      assertThat(ex.getCause()).isNull();
    }

    @Test
    @DisplayName("should carry both the message and the cause")
    void shouldCarryMessageAndCause() {
      Throwable cause = new IllegalStateException("root cause");
      ForbiddenException ex = new ForbiddenException("forbidden", cause);
      assertThat(ex.getMessage()).isEqualTo("forbidden");
      assertThat(ex.getCause()).isSameAs(cause);
    }
  }

  @Nested
  @DisplayName("ValidationException")
  class ValidationExceptionTests {

    @Test
    @DisplayName("should carry the given message")
    void shouldCarryMessage() {
      ValidationException ex = new ValidationException("invalid");
      assertThat(ex.getMessage()).isEqualTo("invalid");
      assertThat(ex.getCause()).isNull();
    }

    @Test
    @DisplayName("should carry both the message and the cause")
    void shouldCarryMessageAndCause() {
      Throwable cause = new IllegalStateException("root cause");
      ValidationException ex = new ValidationException("invalid", cause);
      assertThat(ex.getMessage()).isEqualTo("invalid");
      assertThat(ex.getCause()).isSameAs(cause);
    }
  }

  @Nested
  @DisplayName("StorageException")
  class StorageExceptionTests {

    @Test
    @DisplayName("should carry the given message")
    void shouldCarryMessage() {
      StorageException ex = new StorageException("storage failed");
      assertThat(ex.getMessage()).isEqualTo("storage failed");
      assertThat(ex.getCause()).isNull();
    }

    @Test
    @DisplayName("should carry both the message and the cause")
    void shouldCarryMessageAndCause() {
      Throwable cause = new IllegalStateException("root cause");
      StorageException ex = new StorageException("storage failed", cause);
      assertThat(ex.getMessage()).isEqualTo("storage failed");
      assertThat(ex.getCause()).isSameAs(cause);
    }
  }
}
