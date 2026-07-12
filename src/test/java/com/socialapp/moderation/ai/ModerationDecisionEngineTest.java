package com.socialapp.moderation.ai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.socialapp.moderation.config.ModerationProperties;
import com.socialapp.moderation.dto.ImageSafeSearchResult;
import com.socialapp.moderation.dto.ModerationResult;
import com.socialapp.moderation.dto.ModerationScores;
import com.socialapp.moderation.enums.Likelihood;
import com.socialapp.moderation.enums.ModerationStatus;
import com.socialapp.moderation.enums.ViolationType;

/**
 * Component (unit) tests for {@link ModerationDecisionEngine}, per ISTQB CTFL v4.0.1 Section
 * 2.2.1 (component testing), with test inputs chosen using Boundary Value Analysis (Section
 * 4.3.2) around the configured toxicity/review thresholds. Pure decision logic, no I/O or
 * external dependency — {@link ModerationProperties} is a plain settings holder, used directly
 * with its default thresholds (toxicity reject = 0.7, review = 0.5).
 */
class ModerationDecisionEngineTest {

  private ModerationProperties properties;
  private ModerationDecisionEngine engine;

  @BeforeEach
  void setUp() {
    properties = new ModerationProperties();
    engine = new ModerationDecisionEngine(properties);
  }

  private static ModerationScores scoresWith(
      double toxicity,
      double severeToxicity,
      double insult,
      double threat,
      double sexuallyExplicit) {
    return ModerationScores.builder()
        .toxicity(toxicity)
        .severeToxicity(severeToxicity)
        .insult(insult)
        .threat(threat)
        .sexuallyExplicit(sexuallyExplicit)
        .build();
  }

  private static ModerationScores allZero() {
    return scoresWith(0, 0, 0, 0, 0);
  }

  // =====================================================================
  // Text score thresholds (Boundary Value Analysis around 0.7 reject / 0.5 review)
  // =====================================================================

  @Nested
  @DisplayName("Text score thresholds")
  class TextThresholdTests {

    @Test
    @DisplayName("shouldApprove_whenAllScoresAreZero")
    void shouldApprove_whenAllScoresAreZero() {
      ModerationResult result = engine.decide(1, 1, allZero(), ImageSafeSearchResult.safe());

      assertThat(result.getStatus()).isEqualTo(ModerationStatus.APPROVED);
      assertThat(result.getViolations()).isEmpty();
    }

    @Test
    @DisplayName("shouldReject_whenSevereToxicityIsExactlyAtRejectThreshold")
    void shouldReject_whenSevereToxicityIsExactlyAtRejectThreshold() {
      ModerationScores scores = scoresWith(0, 0.7, 0, 0, 0);

      ModerationResult result = engine.decide(1, 1, scores, ImageSafeSearchResult.safe());

      assertThat(result.getStatus()).isEqualTo(ModerationStatus.REJECTED);
      assertThat(result.getViolations()).containsExactly(ViolationType.HATE_SPEECH);
    }

    @Test
    @DisplayName("shouldNotRejectOnSevereToxicityAlone_whenJustBelowRejectThreshold")
    void shouldNotRejectOnSevereToxicityAlone_whenJustBelowRejectThreshold() {
      ModerationScores scores = scoresWith(0, 0.69, 0, 0, 0);

      ModerationResult result = engine.decide(1, 1, scores, ImageSafeSearchResult.safe());

      // 0.69 is still >= the 0.5 review threshold via getHighestTextScore()
      assertThat(result.getStatus()).isEqualTo(ModerationStatus.PENDING_REVIEW);
      assertThat(result.getViolations()).isEmpty();
    }

    @Test
    @DisplayName("shouldReject_whenThreatIsExactlyAtRejectThreshold")
    void shouldReject_whenThreatIsExactlyAtRejectThreshold() {
      ModerationScores scores = scoresWith(0, 0, 0, 0.7, 0);

      ModerationResult result = engine.decide(1, 1, scores, ImageSafeSearchResult.safe());

      assertThat(result.getStatus()).isEqualTo(ModerationStatus.REJECTED);
      assertThat(result.getViolations()).containsExactly(ViolationType.THREAT);
    }

    @Test
    @DisplayName("shouldReject_whenSexuallyExplicitIsExactlyAtRejectThreshold")
    void shouldReject_whenSexuallyExplicitIsExactlyAtRejectThreshold() {
      ModerationScores scores = scoresWith(0, 0, 0, 0, 0.7);

      ModerationResult result = engine.decide(1, 1, scores, ImageSafeSearchResult.safe());

      assertThat(result.getStatus()).isEqualTo(ModerationStatus.REJECTED);
      assertThat(result.getViolations()).containsExactly(ViolationType.SEXUALLY_EXPLICIT);
    }

    @Test
    @DisplayName("shouldRejectAsHateSpeech_whenOnlyToxicityReachesRejectThreshold")
    void shouldRejectAsHateSpeech_whenOnlyToxicityReachesRejectThreshold() {
      // Given — toxicity alone hitting 0.7 falls through to the generic "highest score" branch,
      // which is (per current code) also labeled HATE_SPEECH
      ModerationScores scores = scoresWith(0.7, 0, 0, 0, 0);

      ModerationResult result = engine.decide(1, 1, scores, ImageSafeSearchResult.safe());

      assertThat(result.getStatus()).isEqualTo(ModerationStatus.REJECTED);
      assertThat(result.getViolations()).containsExactly(ViolationType.HATE_SPEECH);
    }

    @Test
    @DisplayName("shouldReviewInsult_whenHighestScoreIsExactlyAtReviewThreshold")
    void shouldReviewInsult_whenHighestScoreIsExactlyAtReviewThreshold() {
      ModerationScores scores = scoresWith(0, 0, 0.5, 0, 0);

      ModerationResult result = engine.decide(1, 1, scores, ImageSafeSearchResult.safe());

      assertThat(result.getStatus()).isEqualTo(ModerationStatus.PENDING_REVIEW);
      assertThat(result.getViolations()).isEmpty();
    }

    @Test
    @DisplayName("shouldApprove_whenHighestScoreIsJustBelowReviewThreshold")
    void shouldApprove_whenHighestScoreIsJustBelowReviewThreshold() {
      ModerationScores scores = scoresWith(0, 0, 0.49, 0, 0);

      ModerationResult result = engine.decide(1, 1, scores, ImageSafeSearchResult.safe());

      assertThat(result.getStatus()).isEqualTo(ModerationStatus.APPROVED);
    }
  }

  // =====================================================================
  // Image likelihood thresholds
  // =====================================================================

  @Nested
  @DisplayName("Image likelihood thresholds")
  class ImageThresholdTests {

    @Test
    @DisplayName("shouldReject_whenImageLikelihoodMeetsRejectThreshold")
    void shouldReject_whenImageLikelihoodMeetsRejectThreshold() {
      // Default rejectLikelihood = LIKELY
      ImageSafeSearchResult image =
          new ImageSafeSearchResult(
              Likelihood.LIKELY, Likelihood.VERY_UNLIKELY, Likelihood.VERY_UNLIKELY);

      ModerationResult result = engine.decide(1, 1, allZero(), image);

      assertThat(result.getStatus()).isEqualTo(ModerationStatus.REJECTED);
      assertThat(result.getViolations()).containsExactly(ViolationType.NSFW);
    }

    @Test
    @DisplayName("shouldReview_whenImageLikelihoodMeetsReviewThresholdButNotReject")
    void shouldReview_whenImageLikelihoodMeetsReviewThresholdButNotReject() {
      // Default reviewLikelihood = POSSIBLE, rejectLikelihood = LIKELY
      ImageSafeSearchResult image =
          new ImageSafeSearchResult(
              Likelihood.POSSIBLE, Likelihood.VERY_UNLIKELY, Likelihood.VERY_UNLIKELY);

      ModerationResult result = engine.decide(1, 1, allZero(), image);

      assertThat(result.getStatus()).isEqualTo(ModerationStatus.PENDING_REVIEW);
      assertThat(result.getViolations()).isEmpty();
    }

    @Test
    @DisplayName("shouldApprove_whenImageLikelihoodIsBelowReviewThreshold")
    void shouldApprove_whenImageLikelihoodIsBelowReviewThreshold() {
      ImageSafeSearchResult image =
          new ImageSafeSearchResult(
              Likelihood.UNLIKELY, Likelihood.VERY_UNLIKELY, Likelihood.VERY_UNLIKELY);

      ModerationResult result = engine.decide(1, 1, allZero(), image);

      assertThat(result.getStatus()).isEqualTo(ModerationStatus.APPROVED);
    }

    @Test
    @DisplayName("shouldMapImageResultIntoCombinedScores_asNormalizedScore")
    void shouldMapImageResultIntoCombinedScores_asNormalizedScore() {
      ImageSafeSearchResult image =
          new ImageSafeSearchResult(
              Likelihood.VERY_LIKELY, Likelihood.VERY_UNLIKELY, Likelihood.VERY_UNLIKELY);

      ModerationResult result = engine.decide(1, 1, allZero(), image);

      assertThat(result.getScores().getImageSafeScore()).isEqualTo(image.getNormalizedScore());
    }
  }

  // =====================================================================
  // combineStatuses precedence
  // =====================================================================

  @Nested
  @DisplayName("Status precedence: REJECTED > PENDING_REVIEW > APPROVED")
  class CombineStatusesTests {

    @Test
    @DisplayName("shouldReject_whenTextIsRejectedEvenIfImageIsSafe")
    void shouldReject_whenTextIsRejectedEvenIfImageIsSafe() {
      ModerationScores rejectedText = scoresWith(0, 0.9, 0, 0, 0);

      ModerationResult result = engine.decide(1, 1, rejectedText, ImageSafeSearchResult.safe());

      assertThat(result.getStatus()).isEqualTo(ModerationStatus.REJECTED);
    }

    @Test
    @DisplayName("shouldReject_whenImageIsRejectedEvenIfTextIsClean")
    void shouldReject_whenImageIsRejectedEvenIfTextIsClean() {
      ImageSafeSearchResult rejectedImage =
          new ImageSafeSearchResult(
              Likelihood.VERY_LIKELY, Likelihood.VERY_UNLIKELY, Likelihood.VERY_UNLIKELY);

      ModerationResult result = engine.decide(1, 1, allZero(), rejectedImage);

      assertThat(result.getStatus()).isEqualTo(ModerationStatus.REJECTED);
    }

    @Test
    @DisplayName("shouldReview_whenTextNeedsReviewAndImageIsSafe")
    void shouldReview_whenTextNeedsReviewAndImageIsSafe() {
      ModerationScores reviewText = scoresWith(0, 0, 0.6, 0, 0);

      ModerationResult result = engine.decide(1, 1, reviewText, ImageSafeSearchResult.safe());

      assertThat(result.getStatus()).isEqualTo(ModerationStatus.PENDING_REVIEW);
    }

    @Test
    @DisplayName("shouldReview_whenImageNeedsReviewAndTextIsClean")
    void shouldReview_whenImageNeedsReviewAndTextIsClean() {
      ImageSafeSearchResult reviewImage =
          new ImageSafeSearchResult(
              Likelihood.POSSIBLE, Likelihood.VERY_UNLIKELY, Likelihood.VERY_UNLIKELY);

      ModerationResult result = engine.decide(1, 1, allZero(), reviewImage);

      assertThat(result.getStatus()).isEqualTo(ModerationStatus.PENDING_REVIEW);
    }
  }
}
