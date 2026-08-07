package com.socialapp.moderation.enums;

/** Where an appeal sits. Mirrors the {@code status} column of {@code t_moderation_appeals}. */
public enum AppealStatus {
  /** Waiting on an admin. Only one appeal per violation may be in this state — see {@code V49}. */
  PENDING,

  /** The sanction was wrong. The violation is deleted and any ban it caused is lifted. */
  APPROVED,

  /** The sanction stands. */
  REJECTED
}
