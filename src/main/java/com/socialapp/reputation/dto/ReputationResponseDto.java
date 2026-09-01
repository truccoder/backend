package com.socialapp.reputation.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Mirrors the shape `RepScore`/`ReputationCard` in the design system expect. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReputationResponseDto {
  private Integer eliteScore;
  private int level;
  private String levelName;

  /**
   * The user this reputation belongs to, so a caller keyed on {@code userId} (e.g. {@code /chats})
   * can still build a {@code /u/{username}} link — FE's {@code docs/backend-plan.md} B38.
   */
  private String username;

  /**
   * Floor of the level the user is on right now. Without it a progress bar can only run
   * {@code 0 → nextLevelMin}; the alternative — the client hardcoding the threshold table — would
   * be a third copy of {@link com.socialapp.reputation.RepLevel} to keep in sync.
   */
  private int currentLevelMin;

  /** Null once the user has reached the top level (Elite). */
  private Integer nextLevelMin;

  /** Derived from {@code UserRoadmapProgress.status == VERIFIED} — no dedicated badge storage. */
  private boolean verifiedExpert;
}
