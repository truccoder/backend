package com.socialapp.newsfeed.service;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.*;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socialapp.newsfeed.dto.FeedPostDataDto;
import com.socialapp.newsfeed.repository.AuthorInteractionCount;
import com.socialapp.newsfeed.repository.UserInteractionRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class PostScoringService {
  private final UserInteractionRepository userInteractionRepository;
  private final StringRedisTemplate redisTemplate;
  private final ObjectMapper objectMapper;

  static final String FEED_KEY_PREFIX = "feed:";
  static final String POST_CACHE_KEY_PREFIX = "feedpost:";

  private static final double MAX_ENGAGEMENT_LOG = Math.log(1 + 500);

  // Boost = how many "hours of freshness" a factor is worth (in millis)
  private static final long ENGAGEMENT_BOOST_MILLIS = 4 * 3600 * 1000L; // 4 hours
  private static final long AFFINITY_BOOST_MILLIS = 6 * 3600 * 1000L; // 6 hours

  @Scheduled(fixedRate = 5 * 60 * 1000)
  public void recalculateScores() {
    Set<String> feedKeys = redisTemplate.keys(FEED_KEY_PREFIX + "*");
    // keys() returns null, not an empty set, when the connection hands back nothing.
    if (CollectionUtils.isEmpty(feedKeys)) {
      return;
    }

    log.info("Recalculating feed scores for {} feeds", feedKeys.size());

    // Per-key isolation. This loop used to let one bad key end the whole tick: any key matching
    // `feed:*` whose suffix is not an integer — a future `feed:v2:...`, a stray manual key,
    // anything else sharing the Redis database — threw NumberFormatException out of the loop, so
    // no user's feed was rescored, on that tick or any tick after. One unusable key should cost
    // one user's rescore, not everybody's.
    for (String feedKey : feedKeys) {
      try {
        recalculateFeedForUser(Integer.parseInt(feedKey.substring(FEED_KEY_PREFIX.length())));
      } catch (NumberFormatException e) {
        log.warn("Skipping feed key with a non-numeric user id: {}", feedKey);
      } catch (Exception e) {
        log.error("Failed to recalculate feed for key {}", feedKey, e);
      }
    }
  }

  void recalculateFeedForUser(Integer userId) {
    String feedKey = FEED_KEY_PREFIX + userId;
    Set<String> postIds = redisTemplate.opsForZSet().range(feedKey, 0, -1);
    if (Objects.isNull(postIds) || postIds.isEmpty()) {
      return;
    }

    Map<Integer, Double> affinityMap = loadAffinityMap(userId);

    // One MGET for the whole feed instead of a GET per post. A feed holds up to a thousand ids and
    // this runs for every user every five minutes, so the round trips dominated the job.
    // NewsfeedService.loadPostsFromCache already did it this way.
    List<String> ids = List.copyOf(postIds);
    List<String> cached =
        redisTemplate
            .opsForValue()
            .multiGet(ids.stream().map(id -> POST_CACHE_KEY_PREFIX + id).toList());
    if (Objects.isNull(cached)) {
      return;
    }

    // One ZADD for the whole feed, to match the single MGET above. Scoring a thousand-post feed
    // one ZADD at a time put the round trips straight back that the MGET had just removed.
    Set<ZSetOperations.TypedTuple<String>> rescored = new HashSet<>();
    for (int i = 0; i < ids.size(); i++) {
      // multiGet keeps positional alignment with the keys, writing null where a key is missing.
      FeedPostDataDto post = deserialize(cached.get(i));
      if (Objects.isNull(post)) continue;

      double affinity = affinityMap.getOrDefault(post.getAuthorId(), 0.0);
      rescored.add(ZSetOperations.TypedTuple.of(ids.get(i), calculateScore(post, affinity)));
    }

    // ZADD with no members is an error, and a feed whose every post has fallen out of the cache
    // reaches here empty.
    if (!rescored.isEmpty()) {
      redisTemplate.opsForZSet().add(feedKey, rescored);
    }
  }

  FeedPostDataDto deserialize(String json) {
    if (Objects.isNull(json)) {
      return null;
    }
    try {
      return objectMapper.readValue(json, FeedPostDataDto.class);
    } catch (Exception e) {
      log.warn("Failed to deserialize cached post", e);
      return null;
    }
  }

  /** Affinity = interaction_count / max_interaction_count, normalized to [0.0, 1.0]. */
  private Map<Integer, Double> loadAffinityMap(Integer userId) {
    OffsetDateTime since = OffsetDateTime.now().minusDays(30);
    List<AuthorInteractionCount> rows =
        userInteractionRepository.countInteractionsByAuthor(userId, since);

    if (rows.isEmpty()) {
      return Map.of();
    }

    long maxCount =
        rows.stream().mapToLong(AuthorInteractionCount::getInteractionCount).max().orElse(1);
    Map<Integer, Double> map = new HashMap<>();
    for (AuthorInteractionCount row : rows) {
      map.put(row.getAuthorId(), (double) row.getInteractionCount() / maxCount);
    }
    return map;
  }

  public double calculateScore(FeedPostDataDto post, double affinity) {
    Instant createdAt =
        post.getCreatedAt() != null ? post.getCreatedAt().toInstant() : Instant.now();
    long base = createdAt.toEpochMilli();
    double engagement = engagementFactor(post);
    return base + engagement * ENGAGEMENT_BOOST_MILLIS + affinity * AFFINITY_BOOST_MILLIS;
  }

  /**
   * log scale — diminishing returns on high engagement, normalized to [0, 1]
   *
   * <p>A {@code 3.0 * shareCount} term used to sit here. It contributed exactly nothing on every
   * post ever scored, because nothing in this backend writes a share count — the field was dropped
   * from {@link FeedPostDataDto} for that reason, and this term went with it. Removing it leaves
   * every score identical, so {@code MAX_ENGAGEMENT_LOG} stays as it is; a share feature would
   * bring both the field and this term back, and only then would the constant need revisiting.
   */
  private double engagementFactor(FeedPostDataDto post) {
    double raw = Math.log(1 + post.getLikeCount() + 2.0 * post.getCommentCount());
    return Math.min(1.0, raw / MAX_ENGAGEMENT_LOG);
  }
}
