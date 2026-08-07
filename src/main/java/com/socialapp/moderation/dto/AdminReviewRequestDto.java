package com.socialapp.moderation.dto;

import com.socialapp.moderation.enums.Likelihood;
import com.socialapp.moderation.enums.ViolationType;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class AdminReviewRequestDto {
  @NotNull private Likelihood decision;

  /**
   * Which of the nine {@link ViolationType} values the reviewed post actually broke.
   *
   * <p>Until now this was not asked for and the service wrote {@code HATE_SPEECH} for every
   * rejection. That was not merely untidy bookkeeping: {@code UserBanService.determineSeverity}
   * maps HATE_SPEECH to CRITICAL, so removing a piece of spam recorded a critical hate-speech
   * violation against its author and pushed them toward the seven-day ban — and the user is now
   * shown the reason, so a wrong value is a false accusation to their face rather than a bad row.
   *
   * <p>Optional in the schema, not in effect: it is required when {@code decision} rejects the
   * post, and ignored when the post is approved (there is no violation to type). The check is in
   * {@code AdminModerationService.reviewPost} because it depends on {@code decision}, which bean
   * validation on a single field cannot see.
   */
  private ViolationType violationType;

  private String feedback;
}
