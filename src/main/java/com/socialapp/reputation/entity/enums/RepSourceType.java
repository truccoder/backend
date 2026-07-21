package com.socialapp.reputation.entity.enums;

/**
 * Every signal that awards Elite Score points, with the point value baked in so tuning is a
 * one-file change. Values match the design system's `REP_LEVELS` earning model (accepted
 * answers, roadmap verification, project contributions, peer reactions as a stand-in for
 * endorsements).
 */
public enum RepSourceType {
  REACTION_RECEIVED(1),
  ACCEPTED_ANSWER(15),
  ROADMAP_SELF_VERIFIED(5),
  ROADMAP_NODE_VERIFIED(20),
  PROJECT_APPLICATION_ACCEPTED(10);

  private final int points;

  RepSourceType(int points) {
    this.points = points;
  }

  public int getPoints() {
    return points;
  }
}
