package com.socialapp.moderation.rule;

import java.util.ArrayList;
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
  private final KeywordFilter keywordFilter;
  private final SpamDetector spamDetector;

  public ModerationResult evaluate(Integer authorId, String content) {
    List<ViolationType> violations = new ArrayList<>();

    if (spamDetector.isRateLimited(authorId)) {
      violations.add(ViolationType.SPAM);
      return ModerationResult.builder()
          .status(ModerationStatus.REJECTED)
          .violations(violations)
          .build();
    }

    if (spamDetector.isDuplicateContent(authorId, content)) {
      violations.add(ViolationType.DUPLICATE_CONTENT);
      return ModerationResult.builder()
          .status(ModerationStatus.REJECTED)
          .violations(violations)
          .build();
    }

    if (keywordFilter.containsBlacklistedContent(content)) {
      violations.add(ViolationType.KEYWORD_BLACKLIST);
      return ModerationResult.builder()
          .status(ModerationStatus.REJECTED)
          .violations(violations)
          .build();
    }

    return ModerationResult.builder()
        .status(ModerationStatus.PENDING_MODERATION)
        .violations(violations)
        .build();
  }
}
