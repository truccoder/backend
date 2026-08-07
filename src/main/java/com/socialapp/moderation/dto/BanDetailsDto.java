package com.socialapp.moderation.dto;

import java.time.OffsetDateTime;

import com.socialapp.moderation.enums.ViolationType;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Why an account is locked and until when, as structured fields.
 *
 * <p>Replaces the sentence the API used to return — {@code "Your account is banned until
 * 2026-08-11T09:14:22.481Z. Please contact support."} — as the machine-readable half of that
 * answer. A client that wants to render "3 days left" in the user's own language and timezone has
 * to parse a date out of English prose to do it, and any change to that prose (a translation, a
 * comma) silently breaks the parse. The sentence is still sent as {@code message}; this is what a
 * client should actually read.
 *
 * <p>{@code violationType} and {@code reason} come from the user's most recent violation, and are
 * null when a ban predates the record or was applied by hand. They are only truthful because the
 * type an admin picks is now the type that gets stored — see {@code
 * AdminModerationService.reviewPost}. Showing a person a reason drawn from a field that was
 * hardcoded to HATE_SPEECH would have been worse than showing them nothing.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BanDetailsDto {
  private OffsetDateTime bannedUntil;
  private ViolationType violationType;
  private String reason;
}
