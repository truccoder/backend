package com.socialapp.moderation.dto;

import java.util.ArrayList;
import java.util.List;

import com.socialapp.moderation.enums.ModerationStatus;
import com.socialapp.moderation.enums.ViolationType;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModerationResult {
  private ModerationStatus status;
  private ModerationScores scores;

  @Builder.Default private List<ViolationType> violations = new ArrayList<>();

  public boolean isRejected() {
    return ModerationStatus.REJECTED.equals(status);
  }

  public boolean isApproved() {
    return ModerationStatus.APPROVED.equals(status);
  }
}
