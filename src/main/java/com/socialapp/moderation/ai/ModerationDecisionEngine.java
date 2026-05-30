package com.socialapp.moderation.ai;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.socialapp.moderation.config.ModerationProperties;
import com.socialapp.moderation.dto.ImageSafeSearchResult;
import com.socialapp.moderation.dto.ModerationResult;
import com.socialapp.moderation.dto.ModerationScores;
import com.socialapp.moderation.enums.Likelihood;
import com.socialapp.moderation.enums.ModerationStatus;
import com.socialapp.moderation.enums.ViolationType;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class ModerationDecisionEngine {
  private final ModerationProperties properties;

  public ModerationResult decide(ModerationScores textScores, ImageSafeSearchResult imageResult) {
    List<ViolationType> violations = new ArrayList<>();

    ModerationStatus textStatus = evaluateTextScores(textScores, violations);
    ModerationStatus imageStatus = evaluateImageResult(imageResult, violations);

    ModerationStatus finalStatus = combineStatuses(textStatus, imageStatus);

    ModerationScores combinedScores =
        ModerationScores.builder()
            .toxicity(textScores.getToxicity())
            .severeToxicity(textScores.getSevereToxicity())
            .insult(textScores.getInsult())
            .threat(textScores.getThreat())
            .sexuallyExplicit(textScores.getSexuallyExplicit())
            .imageSafeScore(imageResult.getNormalizedScore())
            .build();

    log.info(
        "Moderation decision: status={}, violations={}, highestText={}, imageWorst={}",
        finalStatus,
        violations,
        textScores.getHighestTextScore(),
        imageResult.getWorstLikelihood());

    return ModerationResult.builder()
        .status(finalStatus)
        .scores(combinedScores)
        .violations(violations)
        .build();
  }

  private ModerationStatus combineStatuses(
      ModerationStatus textStatus, ModerationStatus imageStatus) {
    if (textStatus == ModerationStatus.REJECTED || imageStatus == ModerationStatus.REJECTED) {
      return ModerationStatus.REJECTED;
    }
    if (textStatus == ModerationStatus.PENDING_REVIEW
        || imageStatus == ModerationStatus.PENDING_REVIEW) {
      return ModerationStatus.PENDING_REVIEW;
    }
    return ModerationStatus.APPROVED;
  }

  private ModerationStatus evaluateTextScores(
      ModerationScores scores, List<ViolationType> violations) {
    double rejectThreshold = properties.getPerspectiveApi().getToxicityThreshold();
    double reviewThreshold = properties.getPerspectiveApi().getReviewThreshold();
    double highest = scores.getHighestTextScore();

    if (scores.getSevereToxicity() >= rejectThreshold) {
      violations.add(ViolationType.HATE_SPEECH);
      return ModerationStatus.REJECTED;
    }
    if (scores.getThreat() >= rejectThreshold) {
      violations.add(ViolationType.THREAT);
      return ModerationStatus.REJECTED;
    }
    if (scores.getSexuallyExplicit() >= rejectThreshold) {
      violations.add(ViolationType.SEXUALLY_EXPLICIT);
      return ModerationStatus.REJECTED;
    }
    if (highest >= rejectThreshold) {
      violations.add(ViolationType.HATE_SPEECH);
      return ModerationStatus.REJECTED;
    }

    if (highest >= reviewThreshold) {
      return ModerationStatus.PENDING_REVIEW;
    }

    return ModerationStatus.APPROVED;
  }

  private ModerationStatus evaluateImageResult(
      ImageSafeSearchResult imageResult, List<ViolationType> violations) {
    Likelihood rejectThreshold = properties.getCloudVision().getRejectLikelihood();
    Likelihood reviewThreshold = properties.getCloudVision().getReviewLikelihood();

    if (imageResult.isRejected(rejectThreshold)) {
      violations.add(ViolationType.NSFW);
      return ModerationStatus.REJECTED;
    }

    if (imageResult.needsReview(reviewThreshold)) {
      return ModerationStatus.PENDING_REVIEW;
    }

    return ModerationStatus.APPROVED;
  }
}
