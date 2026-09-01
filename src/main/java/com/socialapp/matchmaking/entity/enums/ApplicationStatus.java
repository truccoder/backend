package com.socialapp.matchmaking.entity.enums;

public enum ApplicationStatus {
  /** Awaiting the owner's decision. The state an application is created in. */
  PENDING,

  /** The owner accepted the application — the applicant is on the team for that position. */
  ACCEPTED,

  /** The owner declined the application. Terminal. */
  REJECTED,

  /**
   * The applicant was accepted and then taken off the team by the owner. Distinct from {@code
   * REJECTED} so an owner's inbox and the applicant's own history can tell "was never let in"
   * apart from "was let in, then removed" — and so the {@code PROJECT_APPLICATION_ACCEPTED}
   * reputation revoke on removal keys off a status the accept path never produces. Terminal.
   */
  REMOVED
}
