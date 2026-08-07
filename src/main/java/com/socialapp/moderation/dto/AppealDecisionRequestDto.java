package com.socialapp.moderation.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/** An admin's answer to an appeal. The decision itself is in the path (approve / reject). */
@Data
public class AppealDecisionRequestDto {

  /** Optional note back to the user. Bounded to match the column. */
  @Size(max = 2000)
  private String reviewerNote;
}
