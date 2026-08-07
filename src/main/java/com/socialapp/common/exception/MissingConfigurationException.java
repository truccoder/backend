package com.socialapp.common.exception;

/**
 * Thrown when a feature is invoked but its required server-side configuration (an API key/secret,
 * a client ID, etc.) is absent — e.g. the Stream Chat API secret. This is never the caller's
 * fault, so it must not be masked by a fallback value; the feature should fail loudly (503) until
 * an operator fixes the deployment configuration.
 */
public class MissingConfigurationException extends RuntimeException {
  public MissingConfigurationException(String message) {
    super(message);
  }

  public MissingConfigurationException(String message, Throwable cause) {
    super(message, cause);
  }
}
