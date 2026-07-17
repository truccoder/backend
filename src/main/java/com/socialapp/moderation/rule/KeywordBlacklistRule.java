package com.socialapp.moderation.rule;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.socialapp.moderation.enums.ViolationType;

import lombok.RequiredArgsConstructor;

@Component
@Order(3)
@RequiredArgsConstructor
public class KeywordBlacklistRule implements ModerationRule {
  private final KeywordFilter keywordFilter;

  @Override
  public ViolationType check(Integer authorId, String content) {
    return keywordFilter.containsBlacklistedContent(content)
        ? ViolationType.KEYWORD_BLACKLIST
        : null;
  }
}
