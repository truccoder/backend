package com.socialapp.moderation.rule;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.socialapp.moderation.enums.ViolationType;

import lombok.RequiredArgsConstructor;

@Component
@Order(1)
@RequiredArgsConstructor
public class RateLimitRule implements ModerationRule {
  private final SpamDetector spamDetector;

  @Override
  public ViolationType check(Integer authorId, String content) {
    return spamDetector.isRateLimited(authorId) ? ViolationType.SPAM : null;
  }
}
