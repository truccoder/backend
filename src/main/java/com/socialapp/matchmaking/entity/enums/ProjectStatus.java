package com.socialapp.matchmaking.entity.enums;

public enum ProjectStatus {
  /** Recruiting: new applications are accepted. The state a project is created in. */
  OPEN,

  /**
   * Not recruiting. Set by the owner to stop new applications without ending the project — the
   * team is still forming or the owner is reviewing what they have. {@code applyToPosition}
   * refuses while a project is {@code CLOSED}; the owner can move it back to {@code OPEN}.
   */
  CLOSED,

  /**
   * The project is finished. Terminal — the owner cannot move it back out of this state, and it
   * is the only status transition that cannot be undone. Like {@code CLOSED} it accepts no new
   * applications.
   */
  COMPLETED
}
