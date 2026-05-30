package com.socialapp.moderation.exception;

import java.time.OffsetDateTime;

public class UserBannedException extends RuntimeException {
  public UserBannedException(OffsetDateTime expiresAt) {
    super(
        "Your account is temporarily restricted from posting, commenting, and reacting. "
            + "The restriction will be lifted on: "
            + expiresAt);
  }
}
