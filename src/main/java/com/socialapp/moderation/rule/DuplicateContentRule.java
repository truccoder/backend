package com.socialapp.moderation.rule;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.socialapp.moderation.enums.ViolationType;

import lombok.RequiredArgsConstructor;

@Component
@Order(2)
@RequiredArgsConstructor
public class DuplicateContentRule implements ModerationRule {
  private final SpamDetector spamDetector;

  @Override
  public ViolationType check(Integer authorId, String content) {
    return spamDetector.isDuplicateContent(authorId, content)
        ? ViolationType.DUPLICATE_CONTENT
        : null;
  }
}
