package com.socialapp.common.exception;

/**
 * Thrown when a file storage operation (MinIO upload, presigned URL generation, etc.) fails —
 * e.g. the downstream storage service times out, refuses the connection, or returns a server
 * error. Callers should not need to know it's MinIO underneath; the original cause is preserved
 * for logging/diagnostics.
 */
public class StorageException extends RuntimeException {
  public StorageException(String message) {
    super(message);
  }

  public StorageException(String message, Throwable cause) {
    super(message, cause);
  }
}
