package com.socialapp.common.exception;

/**
 * The action asked for is impossible in the resource's current state.
 *
 * <p>Reviewing a post that is no longer PENDING_REVIEW, accepting an application that was already
 * decided, applying to a position that has been filled. Maps to 409, and its message reaches the
 * caller — so the message must be written for a reader, not lifted from a stack trace.
 *
 * <p><b>This type exists so {@code IllegalStateException} no longer has to.</b> The handler used to
 * map {@code IllegalStateException} to 409 and pass {@code ex.getMessage()} straight through, which
 * worked for the eight places that threw it on purpose and was wrong for every other source: the
 * JDK, Hibernate, Spring and Jackson all raise it for genuine programming errors. Those arrived at
 * the client as "409 Conflict" — telling it to retry something that will never succeed — with the
 * internal message attached, which is the exact disclosure the catch-all handler takes care to
 * avoid.
 */
public class ConflictException extends RuntimeException {
  public ConflictException(String message) {
    super(message);
  }

  public ConflictException(String message, Throwable cause) {
    super(message, cause);
  }
}
