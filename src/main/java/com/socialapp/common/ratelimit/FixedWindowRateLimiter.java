package com.socialapp.common.ratelimit;

import java.time.Duration;
import java.util.List;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Counts events per key inside a fixed time window, in Redis.
 *
 * <p>Extracted so the guest limiter, the auth limiter, the per-email throttle and the post-rate
 * check all count the same way. They differ only in what they key on and how generous the budget
 * is; separate copies of {@code INCR} plus a first-write {@code EXPIRE} would be separate chances
 * to get the expiry subtly wrong — which is exactly what happened while {@code SpamDetector} kept
 * its own.
 *
 * <p><b>Fixed window, not a token bucket, and no new dependency.</b> A caller can spend two windows'
 * worth of requests across a boundary; that is a real weakness and an accepted one — the goal is to
 * stop bulk abuse, not to smooth traffic.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class FixedWindowRateLimiter {

  /**
   * Increment, and set the expiry on the same round trip if this call created the key.
   *
   * <p><b>One script rather than {@code INCR} then {@code EXPIRE}.</b> Split across two commands
   * there is a window — process death, a dropped connection, a Redis failover — in which the
   * counter exists with no TTL. Nothing ever sets one afterwards, because the {@code count == 1}
   * branch that would have is long past, so the key sits above the limit <em>permanently</em>: an
   * IP that can never sign in again, or an email address that can never receive another password
   * reset, with no way back short of deleting the key by hand.
   *
   * <p>Redis runs a script as one atomic unit, so the counter and its expiry are now created
   * together or not at all. Same shape as {@code CacheTemplate}'s release-lock script.
   */
  private static final RedisScript<Long> INCREMENT_IN_WINDOW =
      RedisScript.of(
          """
          local count = redis.call('incr', KEYS[1])
          if count == 1 then
            redis.call('pexpire', KEYS[1], ARGV[1])
          end
          return count
          """,
          Long.class);

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
      Long count =
          redisTemplate.execute(
              INCREMENT_IN_WINDOW, List.of(key), String.valueOf(window.toMillis()));
      if (count == null) {
        return false;
      }
      return count > limit;
    } catch (Exception e) {
      log.warn("Rate-limit check failed for key {}; allowing the request", key, e);
      return false;
    }
  }
}
