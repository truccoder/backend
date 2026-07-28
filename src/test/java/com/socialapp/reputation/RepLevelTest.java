package com.socialapp.reputation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Pure logic tests for {@link RepLevel} — no mocks needed. Boundary value analysis (ISTQB CTFL
 * v4.0.1 Section 4.2.2) around each level's minimum score, since these thresholds must stay in
 * lockstep with the frontend's {@code REP_LEVELS}.
 */
class RepLevelTest {

  @ParameterizedTest(name = "score={0} -> {1}")
  @DisplayName("forScore should resolve the correct level at every boundary")
  @CsvSource({
    "0, NEWCOMER",
    "99, NEWCOMER",
    "100, CONTRIBUTOR",
    "999, CONTRIBUTOR",
    "1000, PRACTITIONER",
    "4999, PRACTITIONER",
    "5000, EXPERT",
    "14999, EXPERT",
    "15000, AUTHORITY",
    "49999, AUTHORITY",
    "50000, ELITE",
    "1000000, ELITE"
  })
  void forScore_shouldResolveCorrectLevel(int score, String expectedLevel) {
    // When
    RepLevel level = RepLevel.forScore(score);

    // Then
    assertThat(level.name()).isEqualTo(expectedLevel);
  }

  @Test
  @DisplayName("displayNameForScore should label a score, and tolerate a null one")
  void displayNameForScore_shouldLabelScoreAndTolerateNull() {
    // When / Then — the feed and search payloads call this with a nullable column
    assertThat(RepLevel.displayNameForScore(0)).isEqualTo("Newcomer");
    assertThat(RepLevel.displayNameForScore(4_999)).isEqualTo("Practitioner");
    assertThat(RepLevel.displayNameForScore(null)).isNull();
  }

  @Test
  @DisplayName("next should return the following level for every level except ELITE")
  void next_shouldReturnFollowingLevel_forAllButElite() {
    // When / Then
    assertThat(RepLevel.NEWCOMER.next()).isEqualTo(RepLevel.CONTRIBUTOR);
    assertThat(RepLevel.CONTRIBUTOR.next()).isEqualTo(RepLevel.PRACTITIONER);
    assertThat(RepLevel.PRACTITIONER.next()).isEqualTo(RepLevel.EXPERT);
    assertThat(RepLevel.EXPERT.next()).isEqualTo(RepLevel.AUTHORITY);
    assertThat(RepLevel.AUTHORITY.next()).isEqualTo(RepLevel.ELITE);
  }

  @Test
  @DisplayName("next should return null once the top level (Elite) is reached")
  void next_shouldReturnNull_whenLevelIsElite() {
    // When / Then
    assertThat(RepLevel.ELITE.next()).isNull();
  }
}
