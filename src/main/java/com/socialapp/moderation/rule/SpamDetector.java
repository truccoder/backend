package com.socialapp.moderation.rule;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.socialapp.moderation.config.ModerationProperties;

import io.jsonwebtoken.lang.Strings;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class SpamDetector {
  private final StringRedisTemplate redisTemplate;
  private final ModerationProperties properties;

  private static final String CONTENT_HASH_PREFIX = "moderation:content_hash:";
  private static final String RATE_LIMIT_PREFIX = "moderation:rate:";
  private static final int MAX_POSTS_PER_MINUTE = 5;

  public boolean isDuplicateContent(Integer authorId, String content) {
    if (Strings.hasText(content)) {
      return false;
    }

    String contentHash = String.valueOf(content.hashCode());
    String key = CONTENT_HASH_PREFIX + authorId + ":" + contentHash;

    Boolean isNew = redisTemplate.opsForValue().setIfAbsent(key, "1", 60, TimeUnit.SECONDS);
    if (Boolean.FALSE.equals(isNew)) {
      log.debug("Duplicate content detected for user {}", authorId);
      return true;
    }
    return false;
  }

  public boolean isRateLimited(Integer authorId) {
    String key = RATE_LIMIT_PREFIX + authorId;
    Long count = redisTemplate.opsForValue().increment(key);

    if (Objects.nonNull(count) && count == 1) {
      redisTemplate.expire(key, 60, TimeUnit.SECONDS);
    }

    if (Objects.nonNull(count) && count > MAX_POSTS_PER_MINUTE) {
      log.debug("Rate limit exceeded for user {}: {} posts/min", authorId, count);
      return true;
    }
    return false;
  }
}
