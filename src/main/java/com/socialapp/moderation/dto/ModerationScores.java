package com.socialapp.moderation.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModerationScores {
  private double toxicity;
  private double severeToxicity;
  private double insult;
  private double threat;
  private double sexuallyExplicit;
  private double imageSafeScore;

  public double getHighestTextScore() {
    return Math.max(
        toxicity, Math.max(severeToxicity, Math.max(insult, Math.max(threat, sexuallyExplicit))));
  }
}
