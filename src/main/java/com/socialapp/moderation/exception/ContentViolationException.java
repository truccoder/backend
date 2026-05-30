package com.socialapp.moderation.exception;

import java.util.List;

import com.socialapp.moderation.enums.ViolationType;

public class ContentViolationException extends RuntimeException {
  public ContentViolationException(List<ViolationType> violations) {
    super("Content violates community guidelines: " + violations);
  }
}
