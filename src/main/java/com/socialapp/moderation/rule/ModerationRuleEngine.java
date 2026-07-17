package com.socialapp.moderation.rule;

import java.util.List;

import org.springframework.stereotype.Component;

import com.socialapp.moderation.dto.ModerationResult;
import com.socialapp.moderation.enums.ModerationStatus;
import com.socialapp.moderation.enums.ViolationType;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class ModerationRuleEngine {
  // Spring injects every ModerationRule bean here, ordered by each implementation's @Order —
  // add a new rule by implementing ModerationRule, no change needed here (Strategy pattern,
  // mirroring how TrendingCrawlScheduler injects List<TrendingCrawler>).
  private final List<ModerationRule> rules;

  public ModerationResult evaluate(Integer authorId, String content) {
    for (ModerationRule rule : rules) {
      ViolationType violation = rule.check(authorId, content);
      if (violation != null) {
        return ModerationResult.builder()
            .status(ModerationStatus.REJECTED)
            .violations(List.of(violation))
            .build();
      }
    }

    return ModerationResult.builder()
        .status(ModerationStatus.PENDING_MODERATION)
        .violations(List.of())
        .build();
  }
}
