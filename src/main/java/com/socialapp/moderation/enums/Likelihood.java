package com.socialapp.moderation.enums;

import java.util.Objects;

import lombok.Getter;

@Getter
public enum Likelihood {
  UNKNOWN(-1),
  VERY_UNLIKELY(0),
  UNLIKELY(1),
  POSSIBLE(2),
  LIKELY(3),
  VERY_LIKELY(4);

  private final int score;

  Likelihood(int score) {
    this.score = score;
  }

  public static Likelihood fromString(String value) {
    if (Objects.isNull(value)) {
      return UNKNOWN;
    }
    try {
      return valueOf(value);
    } catch (IllegalArgumentException e) {
      return UNKNOWN;
    }
  }

  public boolean isAtLeast(Likelihood threshold) {
    return this.score >= threshold.score;
  }
}
