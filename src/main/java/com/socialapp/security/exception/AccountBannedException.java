package com.socialapp.security.exception;

import java.time.OffsetDateTime;

import com.socialapp.moderation.dto.BanDetailsDto;

import lombok.Getter;

/**
 * Thrown when a banned account attempts to authenticate (login, magic-link login, or refresh) or
 * use an already-issued token. Distinct from
 * com.socialapp.moderation.exception.UserBannedException, which only blocks content-creation
 * actions (posting/commenting/reacting) for an authenticated session; this one blocks
 * authentication/access itself.
 *
 * <p>Carries the structured {@link BanDetailsDto} rather than only the sentence, so the 403 tells a
 * client when the ban ends without it having to parse English prose. The sentence stays as the
 * human-readable {@code message}.
 *
 * <p><b>The details are attached by the thrower, not looked up by the handler.</b> Having {@code
 * GlobalExceptionHandler} resolve them would give the {@code @RestControllerAdvice} a service
 * dependency, and Spring pulls that advice into every {@code @WebMvcTest} slice — so a lookup here
 * would mean ~30 unrelated controller tests suddenly needing a mock for a bean they never touch.
 * The two places that throw this already sit behind a mocked service in those slices.
 */
@Getter
public class AccountBannedException extends RuntimeException {

  private final BanDetailsDto banDetails;

  public AccountBannedException(BanDetailsDto banDetails) {
    super(
        "Your account is banned until "
            + (banDetails == null ? null : banDetails.getBannedUntil())
            + ". Please contact support.");
    this.banDetails = banDetails;
  }

  /** For callers that only know the date — {@code banDetails} then carries just that. */
  public AccountBannedException(OffsetDateTime bannedUntil) {
    this(BanDetailsDto.builder().bannedUntil(bannedUntil).build());
  }
}
