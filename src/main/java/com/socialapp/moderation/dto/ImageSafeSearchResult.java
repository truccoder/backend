package com.socialapp.moderation.dto;

import com.socialapp.moderation.enums.Likelihood;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ImageSafeSearchResult {
  private Likelihood adult;
  private Likelihood violence;
  private Likelihood racy;

  public static ImageSafeSearchResult safe() {
    return new ImageSafeSearchResult(
        Likelihood.VERY_UNLIKELY, Likelihood.VERY_UNLIKELY, Likelihood.VERY_UNLIKELY);
  }

  public static ImageSafeSearchResult pending() {
    return new ImageSafeSearchResult(Likelihood.UNKNOWN, Likelihood.UNKNOWN, Likelihood.UNKNOWN);
  }

  public Likelihood getWorstLikelihood() {
    Likelihood worst = adult;
    if (violence.getScore() > worst.getScore()) worst = violence;
    if (racy.getScore() > worst.getScore()) worst = racy;
    return worst;
  }

  public double getNormalizedScore() {
    int maxScore = getWorstLikelihood().getScore();
    return maxScore <= 0 ? 0.0 : maxScore / 4.0;
  }

  public boolean isRejected(Likelihood threshold) {
    return getWorstLikelihood().isAtLeast(threshold);
  }

  public boolean needsReview(Likelihood threshold) {
    return getWorstLikelihood().isAtLeast(threshold);
  }
}
