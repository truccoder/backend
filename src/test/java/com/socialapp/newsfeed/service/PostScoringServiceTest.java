package com.socialapp.newsfeed.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socialapp.newsfeed.dto.FeedPostDataDto;
import com.socialapp.newsfeed.repository.AuthorInteractionCount;
import com.socialapp.newsfeed.repository.UserInteractionRepository;

/**
 * Component (unit) tests for {@link PostScoringService}, per ISTQB CTFL v4.0.1 (Section 2.2.1
 * component testing, Section 5.1.6 test pyramid, Section 4.3.2 branch testing, Section 2.1.3 BDD
 * Given/When/Then) — see {@code PostServiceTest} for the full rationale. {@code
 * recalculateFeedForUser} and {@code loadPostFromCache} are package-private, so this test class
 * lives in the same package and calls them directly rather than only through the public {@code
 * @Scheduled} entry point.
 */
@ExtendWith(MockitoExtension.class)
class PostScoringServiceTest {

  private static final Integer USER_ID = 1;
  private static final Integer AUTHOR_ID = 2;

  @Mock private UserInteractionRepository userInteractionRepository;
  @Mock private StringRedisTemplate redisTemplate;
  @Mock private ObjectMapper objectMapper;

  @Mock private ZSetOperations<String, String> zSetOperations;
  @Mock private ValueOperations<String, String> valueOperations;

  @InjectMocks private PostScoringService postScoringService;

  private static FeedPostDataDto post(Integer authorId, OffsetDateTime createdAt, int likes) {
    return FeedPostDataDto.builder()
        .postId(100)
        .authorId(authorId)
        .createdAt(createdAt)
        .likeCount(likes)
        .build();
  }

  private static AuthorInteractionCount interactionCount(Integer authorId, long count) {
    AuthorInteractionCount row = org.mockito.Mockito.mock(AuthorInteractionCount.class);
    when(row.getAuthorId()).thenReturn(authorId);
    when(row.getInteractionCount()).thenReturn(count);
    return row;
  }

  // =====================================================================
  // recalculateScores
  // =====================================================================

  @Nested
  @DisplayName("recalculateScores")
  class RecalculateScoresTests {

    @Test
    @DisplayName("should do nothing when there are no feed keys in Redis")
    void shouldDoNothing_whenNoFeedKeysExist() {
      // Given
      when(redisTemplate.keys("feed:*")).thenReturn(Set.of());

      // When
      postScoringService.recalculateScores();

      // Then
      verify(userInteractionRepository, never()).countInteractionsByAuthor(any(), any());
    }

    @Test
    @DisplayName("should recalculate every feed found in Redis")
    void shouldRecalculateEachFeed_whenFeedKeysExist() {
      // Given
      when(redisTemplate.keys("feed:*")).thenReturn(Set.of("feed:1"));
      when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
      when(zSetOperations.range("feed:1", 0, -1)).thenReturn(Set.of());

      // When
      postScoringService.recalculateScores();

      // Then
      verify(zSetOperations).range("feed:1", 0, -1);
    }
  }

  // =====================================================================
  // recalculateFeedForUser
  // =====================================================================

  @Nested
  @DisplayName("recalculateFeedForUser")
  class RecalculateFeedForUserTests {

    @Test
    @DisplayName("should do nothing when Redis returns a null post-id set")
    void shouldDoNothing_whenPostIdsIsNull() {
      // Given
      when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
      when(zSetOperations.range("feed:" + USER_ID, 0, -1)).thenReturn(null);

      // When
      postScoringService.recalculateFeedForUser(USER_ID);

      // Then
      verify(userInteractionRepository, never()).countInteractionsByAuthor(any(), any());
    }

    @Test
    @DisplayName("should do nothing when the post-id set is empty")
    void shouldDoNothing_whenPostIdsIsEmpty() {
      // Given
      when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
      when(zSetOperations.range("feed:" + USER_ID, 0, -1)).thenReturn(Set.of());

      // When
      postScoringService.recalculateFeedForUser(USER_ID);

      // Then
      verify(userInteractionRepository, never()).countInteractionsByAuthor(any(), any());
    }

    @Test
    @DisplayName("should skip a post that is no longer in the cache")
    void shouldSkipPost_whenNotInCache() throws Exception {
      // Given
      when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
      when(zSetOperations.range("feed:" + USER_ID, 0, -1)).thenReturn(Set.of("100"));
      when(userInteractionRepository.countInteractionsByAuthor(eq(USER_ID), any()))
          .thenReturn(List.of());
      when(redisTemplate.opsForValue()).thenReturn(valueOperations);
      when(valueOperations.get("feedpost:100")).thenReturn(null);

      // When
      postScoringService.recalculateFeedForUser(USER_ID);

      // Then
      verify(zSetOperations, never()).add(anyString(), anyString(), anyDouble());
    }

    @Test
    @DisplayName("should default affinity to zero when no interactions were recorded")
    void shouldUseZeroAffinity_whenNoInteractionsExist() throws Exception {
      // Given
      when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
      when(zSetOperations.range("feed:" + USER_ID, 0, -1)).thenReturn(Set.of("100"));
      when(userInteractionRepository.countInteractionsByAuthor(eq(USER_ID), any()))
          .thenReturn(List.of());
      when(redisTemplate.opsForValue()).thenReturn(valueOperations);
      when(valueOperations.get("feedpost:100")).thenReturn("{}");
      when(objectMapper.readValue("{}", FeedPostDataDto.class))
          .thenReturn(post(AUTHOR_ID, OffsetDateTime.now(), 0));

      // When
      postScoringService.recalculateFeedForUser(USER_ID);

      // Then
      verify(zSetOperations).add(eq("feed:" + USER_ID), eq("100"), anyDouble());
    }

    @Test
    @DisplayName("should apply a non-zero affinity boost derived from recorded interactions")
    void shouldApplyAffinityBoost_whenInteractionsExist() throws Exception {
      // Given
      when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
      when(zSetOperations.range("feed:" + USER_ID, 0, -1)).thenReturn(Set.of("100"));
      List<AuthorInteractionCount> rows =
          List.of(interactionCount(AUTHOR_ID, 5L), interactionCount(99, 10L));
      when(userInteractionRepository.countInteractionsByAuthor(eq(USER_ID), any()))
          .thenReturn(rows);
      when(redisTemplate.opsForValue()).thenReturn(valueOperations);
      when(valueOperations.get("feedpost:100")).thenReturn("{}");
      when(objectMapper.readValue("{}", FeedPostDataDto.class))
          .thenReturn(post(AUTHOR_ID, OffsetDateTime.now(), 0));

      // When
      postScoringService.recalculateFeedForUser(USER_ID);

      // Then
      verify(zSetOperations).add(eq("feed:" + USER_ID), eq("100"), anyDouble());
    }
  }

  // =====================================================================
  // calculateScore
  // =====================================================================

  @Nested
  @DisplayName("calculateScore")
  class CalculateScoreTests {

    @Test
    @DisplayName("should base the score on the post's own creation instant when present")
    void shouldUseCreatedAtInstant_whenPresent() {
      // Given
      OffsetDateTime createdAt = OffsetDateTime.now().minusHours(1);
      FeedPostDataDto post = post(AUTHOR_ID, createdAt, 0);

      // When
      double score = postScoringService.calculateScore(post, 0.0);

      // Then
      assertThat(score)
          .isCloseTo(
              createdAt.toInstant().toEpochMilli(), org.assertj.core.data.Offset.offset(1000.0));
    }

    @Test
    @DisplayName("should base the score on the current instant when createdAt is missing")
    void shouldUseCurrentInstant_whenCreatedAtIsNull() {
      // Given
      FeedPostDataDto post = post(AUTHOR_ID, null, 0);
      long before = System.currentTimeMillis();

      // When
      double score = postScoringService.calculateScore(post, 0.0);

      // Then
      assertThat(score).isGreaterThanOrEqualTo(before);
    }

    @Test
    @DisplayName("should increase the score for higher engagement and affinity")
    void shouldIncreaseScore_forHigherEngagementAndAffinity() {
      // Given
      OffsetDateTime createdAt = OffsetDateTime.now();
      FeedPostDataDto quiet = post(AUTHOR_ID, createdAt, 0);
      FeedPostDataDto popular = post(AUTHOR_ID, createdAt, 100);

      // When
      double quietScore = postScoringService.calculateScore(quiet, 0.0);
      double popularScore = postScoringService.calculateScore(popular, 1.0);

      // Then
      assertThat(popularScore).isGreaterThan(quietScore);
    }
  }

  // =====================================================================
  // loadPostFromCache
  // =====================================================================

  @Nested
  @DisplayName("loadPostFromCache")
  class LoadPostFromCacheTests {

    @Test
    @DisplayName("should return null on a cache miss")
    void shouldReturnNull_whenCacheMiss() {
      // Given
      when(redisTemplate.opsForValue()).thenReturn(valueOperations);
      when(valueOperations.get("feedpost:100")).thenReturn(null);

      // When
      FeedPostDataDto result = postScoringService.loadPostFromCache("100");

      // Then
      assertThat(result).isNull();
    }

    @Test
    @DisplayName("should return the deserialized post on a cache hit")
    void shouldReturnDeserializedPost_whenCacheHit() throws Exception {
      // Given
      when(redisTemplate.opsForValue()).thenReturn(valueOperations);
      when(valueOperations.get("feedpost:100")).thenReturn("{\"postId\":100}");
      FeedPostDataDto expected = post(AUTHOR_ID, OffsetDateTime.now(), 0);
      when(objectMapper.readValue("{\"postId\":100}", FeedPostDataDto.class)).thenReturn(expected);

      // When
      FeedPostDataDto result = postScoringService.loadPostFromCache("100");

      // Then
      assertThat(result).isSameAs(expected);
    }

    @Test
    @DisplayName("should return null and log a warning when deserialization fails")
    void shouldReturnNull_whenDeserializationFails() throws Exception {
      // Given
      when(redisTemplate.opsForValue()).thenReturn(valueOperations);
      when(valueOperations.get("feedpost:100")).thenReturn("not-json");
      when(objectMapper.readValue("not-json", FeedPostDataDto.class))
          .thenThrow(new RuntimeException("bad json"));

      // When
      FeedPostDataDto result = postScoringService.loadPostFromCache("100");

      // Then
      assertThat(result).isNull();
    }
  }
}
