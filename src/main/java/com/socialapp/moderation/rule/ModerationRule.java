package com.socialapp.moderation.rule;

import com.socialapp.moderation.enums.ViolationType;

/**
 * A single moderation check. Returns the {@link ViolationType} it detected, or {@code null} when
 * the content passes this rule. Implementations are ordered via {@code @Order} so {@link
 * ModerationRuleEngine} evaluates them in a deterministic sequence.
 */
public interface ModerationRule {
  ViolationType check(Integer authorId, String content);
}
