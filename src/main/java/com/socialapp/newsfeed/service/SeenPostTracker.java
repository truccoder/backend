package com.socialapp.newsfeed.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Remembers what a reader has already scrolled past, for as long as they keep reading.
 *
 * <p>This is the storage half of the seen-post demotion; {@code NewsfeedService} owns the other half,
 * which is what the demotion is worth in ranking terms. The split is deliberate: everything here is
 * about Redis — which keys, what expires when, how a batch is written atomically — and none of it is
 * about feeds. Keeping the two apart is also what lets the feed's own tests stub a collaborator
 * instead of impersonating four different Redis data structures.
 *
 * <p><b>Nothing here reaches Postgres, and that is the whole design.</b> {@link
 * com.socialapp.newsfeed.entity.enums.InteractionType} refused a {@code VIEW} constant on the grounds
 * that a row per post per page load would feed the ranking job a signal derived from its own output.
 * A per-session Redis key is not that signal: it never touches affinity, it never outlives the sitting
 * it was written in, and the only thing that reads it is the request about to render a page.
 *
 * <p><b>Everything fails open.</b> Redis is deliberately outside the {@code readiness} health group
 * because losing it should cost features, not the service. A reader whose seen set cannot be read gets
 * their feed in the order it was already in — which is exactly the feed they had before this class
 * existed.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeenPostTracker {

  /**
   * Where a reader's already-scrolled-past posts live, as {@code feedseen:<userId>}.
   *
   * <p><b>Not {@code feed:seen:<userId>}.</b> {@code PostScoringService.recalculateScores} sweeps
   * {@code SCAN MATCH feed:*} and hands every key it finds to {@code Integer.parseInt} and then to
   * {@code ZRANGE}. A key under {@code feed:} would be picked up by a job that has no business
   * knowing about it, fail the parse, and log once per reader every five minutes forever — the trap
   * that file's own comment warns about when it mentions a future {@code feed:v2:}. This prefix does
   * not match the glob, because the pattern wants a literal colon where this has an {@code o}.
   * {@code feedpost:} is spelled the way it is for the same reason.
   */
  static final String SEEN_KEY_PREFIX = "feedseen:";

  /**
   * Where one scroll-through's frozen post order lives, as {@code feedorder:<userId>[:<scope>]}.
   *
   * <p>Named to miss {@code SCAN MATCH feed:*} on the same reasoning as {@link #SEEN_KEY_PREFIX}.
   */
  static final String ORDER_KEY_PREFIX = "feedorder:";

  /**
   * How long a reading session remembers what it has shown, refreshed on every write.
   *
   * <p>Sliding rather than fixed, and that is the definition of "session" here: the window closes six
   * hours after the reader <em>stops</em>, not six hours after they started. A fixed expiry would
   * lapse in the middle of a long sitting and start showing them what they had just read.
   *
   * <p>That it expires at all is the point. This platform's content is seeded rather than
   * continuously produced, so a reader who is never shown a post twice runs out of feed. Forgetting on
   * a timer is what prevents that, and it is why the demotion downstream is a penalty rather than a
   * filter.
   */
  static final Duration SESSION_TTL = Duration.ofHours(6);

  /**
   * The most posts one reader's seen set will hold.
   *
   * <p>Nothing is gained by remembering more posts than a feed can contain, and the cap is what stops
   * an authenticated {@code POST /feed/seen} from growing a Redis key without bound: the oldest
   * entries are evicted by rank, the same trim the feed itself performs.
   */
  static final int MAX_SEEN_POSTS = 1000;

  /**
   * How long a frozen scroll order survives — one sitting with the feed, no longer.
   *
   * <p>Long enough that nobody pages past the end of it in a single scroll; short enough that coming
   * back later re-ranks against whatever has been fanned out since.
   */
  static final Duration ORDER_TTL = Duration.ofMinutes(10);

  /**
   * Records a batch of posts as seen, caps the set, and refreshes the session — atomically.
   *
   * <p>One script rather than {@code ZADD}, {@code ZREMRANGEBYRANK} and {@code PEXPIRE} in sequence,
   * for the reason {@code FixedWindowRateLimiter} gives for its own: split across commands there is a
   * window — a dropped connection, a process death, a Redis failover — in which the set exists with no
   * expiry. Nothing would ever set one afterwards, because every later write takes the same path and
   * would fail the same way, so the reader's session would never end: posts demoted permanently, with
   * no way back short of deleting the key by hand.
   */
  private static final RedisScript<Long> MARK_SEEN =
      RedisScript.of(
          """
          local seenAt = ARGV[1]
          local ttlMillis = ARGV[2]
          local cap = tonumber(ARGV[3])
          for i = 4, #ARGV do
            redis.call('zadd', KEYS[1], seenAt, ARGV[i])
          end
          redis.call('zremrangebyrank', KEYS[1], 0, -(cap + 1))
          redis.call('pexpire', KEYS[1], ttlMillis)
          return redis.call('zcard', KEYS[1])
          """,
          Long.class);

  private final StringRedisTemplate redisTemplate;

  /** The key holding {@code userId}'s frozen order for one feed scope. */
  public String orderKey(Integer userId, Object scope) {
    return Objects.isNull(scope)
        ? ORDER_KEY_PREFIX + userId
        : ORDER_KEY_PREFIX + userId + ":" + scope;
  }

  /**
   * Records that {@code userId} has scrolled past these posts.
   *
   * <p><b>Fire and forget.</b> A client reporting what it has displayed is telling the server
   * something, not asking it for anything, so a Redis failure is logged and swallowed: the reader
   * loses the demotion, not the scroll they are in the middle of.
   */
  public void markSeen(Integer userId, Collection<Integer> postIds) {
    if (Objects.isNull(postIds) || postIds.isEmpty()) {
      return;
    }

    List<String> args = new ArrayList<>(postIds.size() + 3);
    args.add(String.valueOf(System.currentTimeMillis()));
    args.add(String.valueOf(SESSION_TTL.toMillis()));
    args.add(String.valueOf(MAX_SEEN_POSTS));
    postIds.forEach(postId -> args.add(String.valueOf(postId)));

    try {
      redisTemplate.execute(MARK_SEEN, List.of(SEEN_KEY_PREFIX + userId), args.toArray());
    } catch (Exception e) {
      log.warn("Could not record {} seen posts for user {}", postIds.size(), userId, e);
    }
  }

  /**
   * Whether this reader has seen anything at all in the current session.
   *
   * <p>{@code ZCARD} is O(1), and this is the gate in front of every other cost the demotion adds: a
   * reader who has seen nothing — which is everybody, on the first request of a session — pays one
   * cheap command and then takes the path they took before this feature existed.
   */
  public boolean hasSeenAnything(Integer userId) {
    try {
      Long count = redisTemplate.opsForZSet().zCard(SEEN_KEY_PREFIX + userId);
      return Objects.nonNull(count) && count > 0;
    } catch (Exception e) {
      log.warn(
          "Could not read the seen-post count for user {}; treating it as nothing seen", userId, e);
      return false;
    }
  }

  /**
   * When each of these posts was seen, positionally aligned with {@code postIds}, {@code null} where
   * it has not been.
   *
   * <p>One {@code ZMSCORE} for the whole window. The cost is bounded by how many posts are being
   * ranked rather than by how many the reader has ever seen, which is the reason the seen set is a
   * sorted set and not the plain set it otherwise wants to be: {@code SMEMBERS} would be O(N) in the
   * size of a key the client controls, on every feed request.
   */
  public List<Double> seenAt(Integer userId, List<String> postIds) {
    if (postIds.isEmpty()) {
      return List.of();
    }
    try {
      List<Double> scores =
          redisTemplate.opsForZSet().score(SEEN_KEY_PREFIX + userId, postIds.toArray());
      return Objects.isNull(scores) ? List.of() : scores;
    } catch (Exception e) {
      log.warn("Could not read the seen posts of user {}; treating them all as unseen", userId, e);
      return List.of();
    }
  }

  /** Freezes one scroll-through's order, replacing any previous run rather than appending to it. */
  public void saveOrder(String orderKey, List<String> orderedIds) {
    if (orderedIds.isEmpty()) {
      return;
    }
    try {
      redisTemplate.delete(orderKey);
      redisTemplate.opsForList().rightPushAll(orderKey, orderedIds);
      redisTemplate.expire(orderKey, ORDER_TTL);
    } catch (Exception e) {
      // The next page falls back to the unranked window: a worse page, not a broken one.
      log.warn("Could not store the feed order snapshot at {}", orderKey, e);
    }
  }

  /** A slice of a frozen order, or an empty list when there is no longer one to read. */
  public List<String> readOrder(String orderKey, long start, int count) {
    try {
      List<String> ids = redisTemplate.opsForList().range(orderKey, start, start + count - 1L);
      return Objects.isNull(ids) ? List.of() : ids;
    } catch (Exception e) {
      log.warn("Could not read the feed order snapshot at {}", orderKey, e);
      return List.of();
    }
  }
}
