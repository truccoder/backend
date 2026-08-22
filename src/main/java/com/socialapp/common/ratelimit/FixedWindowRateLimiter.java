package com.socialapp.common.ratelimit;

import java.time.Duration;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Counts events per key inside a fixed time window, in Redis.
 *
 * <p>Extracted so the guest limiter, the auth limiter and the per-email throttle all count the same
 * way. They differ only in what they key on and how generous the budget is; three copies of {@code
 * INCR} plus a first-write {@code EXPIRE} would be three chances to get the expiry subtly wrong.
 *
 * <p><b>Fixed window, not a token bucket, and no new dependency.</b> A caller can spend two windows'
 * worth of requests across a boundary; that is a real weakness and an accepted one — the goal is to
 * stop bulk abuse, not to smooth traffic. It is two Redis commands and no library, and it follows
 * the counter pattern {@code SpamDetector} already uses in this codebase.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class FixedWindowRateLimiter {

  private final StringRedisTemplate redisTemplate;

  /**
   * Records one event against {@code key} and reports whether the budget is now exceeded.
   *
   * <p><b>Fails open.</b> Redis being unavailable must not take down whatever this protects: a
   * limiter that cannot count is a degraded defence, while one that rejects everything is an
   * outage. That trade-off is deliberate and is the reason the return is {@code false} on error —
   * callers get "not over limit" and carry on.
   *
   * @param key the thing being limited, already namespaced by the caller
   * @param limit how many events the window allows
   * @param window how long the budget lasts
   */
  public boolean isOverLimit(String key, int limit, Duration window) {
    try {
      Long count = redisTemplate.opsForValue().increment(key);
      if (count == null) {
        return false;
      }
      // Only the request that created the counter sets the TTL. Setting it on every request would
      // slide the window forward forever and the counter would never reset for a steady caller.
      if (count == 1L) {
        redisTemplate.expire(key, window);
      }
      return count > limit;
    } catch (Exception e) {
      log.warn("Rate-limit check failed for key {}; allowing the request", key, e);
      return false;
    }
  }
}
