package com.socialapp.moderation.rule;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.socialapp.common.ratelimit.FixedWindowRateLimiter;
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
  private final FixedWindowRateLimiter rateLimiter;

  private static final String CONTENT_HASH_PREFIX = "moderation:content_hash:";
  private static final String RATE_LIMIT_PREFIX = "moderation:rate:";
  private static final int MAX_POSTS_PER_MINUTE = 5;
  private static final Duration RATE_LIMIT_WINDOW = Duration.ofMinutes(1);

  public boolean isDuplicateContent(Integer authorId, String content) {
    if (!Strings.hasText(content)) {
      return false;
    }

    // SHA-256 rather than String.hashCode(). hashCode is 32 bits and trivially collidable, so two
    // genuinely different posts by the same author inside the window could collide and the second
    // would be refused as a duplicate — a false accusation of spamming, which is worse than
    // missing a real duplicate.
    String key = CONTENT_HASH_PREFIX + authorId + ":" + sha256(content);

    Boolean isNew = redisTemplate.opsForValue().setIfAbsent(key, "1", 60, TimeUnit.SECONDS);
    if (Boolean.FALSE.equals(isNew)) {
      log.debug("Duplicate content detected for user {}", authorId);
      return true;
    }
    return false;
  }

  /**
   * Whether this author has posted too often in the last minute.
   *
   * <p>Delegates to {@link FixedWindowRateLimiter} instead of counting here. The local copy was a
   * separate {@code INCR} and {@code EXPIRE}, so a failure between them left the key with no TTL
   * and that author permanently unable to post — and unlike the shared limiter it had no
   * {@code try/catch}, so a Redis outage threw out through the rule engine and into
   * {@code PostService.createPost}, turning "Redis is down" into "nobody can post at all".
   */
  public boolean isRateLimited(Integer authorId) {
    boolean overLimit =
        rateLimiter.isOverLimit(
            RATE_LIMIT_PREFIX + authorId, MAX_POSTS_PER_MINUTE, RATE_LIMIT_WINDOW);
    if (overLimit) {
      log.debug("Rate limit exceeded for user {}", authorId);
    }
    return overLimit;
  }

  private String sha256(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      // SHA-256 is guaranteed by every conformant JVM, so this cannot happen at runtime.
      throw new IllegalStateException("SHA-256 is unavailable", e);
    }
  }
}
