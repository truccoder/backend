package com.socialapp.reputation.event;

import com.socialapp.reputation.entity.enums.RepSourceType;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Fired after the triggering transaction commits.
 *
 * <p>Three shapes: an award ({@code revoke=false}, {@code sourceIdPrefix=null}), a single revoke
 * ({@code revoke=true}, exact {@code sourceId}), and a bulk revoke ({@code sourceIdPrefix} set) —
 * the last for deleting a post, whose per-reactor {@code REACTION_RECEIVED} rows share the prefix
 * {@code "{postId}:"} and cannot be named one triple at a time.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReputationAwardEvent {
  private Integer userId;
  private RepSourceType sourceType;
  private String sourceId;
  private boolean revoke;

  /** When set, the listener bulk-revokes every event of {@code sourceType} whose id starts here. */
  private String sourceIdPrefix;
}
