package com.socialapp.moderation.enums;

/**
 * Why a reader reported a post.
 *
 * <p>Deliberately <b>not</b> {@link ViolationType}. That enum is the moderator's verdict — it is
 * what gets recorded against an author, what {@code UserBanService.determineSeverity} weighs
 * towards a ban, and what the author is shown when they are locked out. This one is a member of the
 * public saying what bothered them, which is a claim and not a finding. Sharing one type between
 * the two would make an accusation look like a decision in every list that renders it, and would
 * hand a reporter the vocabulary that decides how hard someone gets sanctioned.
 *
 * <p>Shorter than {@link ViolationType} for the same reason: a report form with nine options is a
 * form people pick the first item on. The moderator refines it into the precise violation when they
 * rule.
 */
public enum ReportReason {
  SPAM,
  HARASSMENT,
  HATE_SPEECH,
  ADULT_CONTENT,
  VIOLENCE,
  MISINFORMATION,

  /** Anything the list above does not cover — {@code details} carries what the reporter wrote. */
  OTHER
}
