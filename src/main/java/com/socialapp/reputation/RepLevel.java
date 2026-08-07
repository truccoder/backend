package com.socialapp.reputation;

/**
 * Levels derived from Elite Score. Thresholds must stay in lockstep with {@code REP_LEVELS} in
 * elite-nexus-design-system/project/components/display/RepScore.d.ts — a mismatch here silently
 * desyncs what the frontend labels a user vs. what the backend would say.
 */
public enum RepLevel {
  NEWCOMER(1, "Newcomer", 0),
  CONTRIBUTOR(2, "Contributor", 100),
  PRACTITIONER(3, "Practitioner", 1_000),
  EXPERT(4, "Expert", 5_000),
  AUTHORITY(5, "Authority", 15_000),
  ELITE(6, "Elite", 50_000);

  private final int level;
  private final String displayName;
  private final int min;

  RepLevel(int level, String displayName, int min) {
    this.level = level;
    this.displayName = displayName;
    this.min = min;
  }

  public int getLevel() {
    return level;
  }

  public String getDisplayName() {
    return displayName;
  }

  public int getMin() {
    return min;
  }

  public static RepLevel forScore(int score) {
    RepLevel result = NEWCOMER;
    for (RepLevel candidate : values()) {
      if (score >= candidate.min) {
        result = candidate;
      }
    }
    return result;
  }

  /**
   * Label for a raw Elite Score, null-tolerant. Shared by the feed and search payloads so the
   * level shown next to a score is computed from this table and nowhere else.
   */
  public static String displayNameForScore(Integer score) {
    return score == null ? null : forScore(score).getDisplayName();
  }

  /** Null once ELITE is reached — there's no next level. */
  public RepLevel next() {
    RepLevel[] all = values();
    int idx = this.ordinal() + 1;
    return idx < all.length ? all[idx] : null;
  }
}
