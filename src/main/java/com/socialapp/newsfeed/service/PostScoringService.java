package com.socialapp.newsfeed.service;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.*;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

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
    if (feedKeys.isEmpty()) {
      return;
    }

    log.info("Recalculating feed scores for {} feeds", feedKeys.size());

    for (String feedKey : feedKeys) {
      Integer userId = Integer.parseInt(feedKey.substring(FEED_KEY_PREFIX.length()));
      recalculateFeedForUser(userId);
    }
  }

  void recalculateFeedForUser(Integer userId) {
    String feedKey = FEED_KEY_PREFIX + userId;
    Set<String> postIds = redisTemplate.opsForZSet().range(feedKey, 0, -1);
    if (Objects.isNull(postIds) || postIds.isEmpty()) {
      return;
    }

    Map<Integer, Double> affinityMap = loadAffinityMap(userId);

    for (String postId : postIds) {
      FeedPostDataDto post = loadPostFromCache(postId);
      if (Objects.isNull(post)) continue;

      double affinity = affinityMap.getOrDefault(post.getAuthorId(), 0.0);
      double newScore = calculateScore(post, affinity);
      redisTemplate.opsForZSet().add(feedKey, postId, newScore);
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

  /** log scale — diminishing returns on high engagement, normalized to [0, 1] */
  private double engagementFactor(FeedPostDataDto post) {
    double raw =
        Math.log(
            1 + post.getLikeCount() + 2.0 * post.getCommentCount() + 3.0 * post.getShareCount());
    return Math.min(1.0, raw / MAX_ENGAGEMENT_LOG);
  }

  FeedPostDataDto loadPostFromCache(String postId) {
    try {
      String json = redisTemplate.opsForValue().get(POST_CACHE_KEY_PREFIX + postId);
      if (Objects.isNull(json)) return null;
      return objectMapper.readValue(json, FeedPostDataDto.class);
    } catch (Exception e) {
      log.warn("Failed to read post cache for {}", postId, e);
      return null;
    }
  }
}
