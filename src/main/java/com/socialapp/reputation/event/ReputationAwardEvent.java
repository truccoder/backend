package com.socialapp.reputation.event;

import com.socialapp.reputation.entity.enums.RepSourceType;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Fired after the triggering transaction commits; direction is decided by {@code revoke}. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReputationAwardEvent {
  private Integer userId;
  private RepSourceType sourceType;
  private String sourceId;
  private boolean revoke;
}
