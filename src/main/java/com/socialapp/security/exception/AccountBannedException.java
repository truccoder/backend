package com.socialapp.security.exception;

import java.time.OffsetDateTime;

/**
 * Thrown when a banned account attempts to authenticate (login, magic-link login, or refresh) or
 * use an already-issued token. Distinct from
 * com.socialapp.moderation.exception.UserBannedException, which only blocks content-creation
 * actions (posting/commenting/reacting) for an authenticated session; this one blocks
 * authentication/access itself.
 */
public class AccountBannedException extends RuntimeException {
  public AccountBannedException(OffsetDateTime bannedUntil) {
    super("Your account is banned until " + bannedUntil + ". Please contact support.");
  }
}
