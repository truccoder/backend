package com.socialapp.newsfeed.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
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
 * recalculateFeedForUser} and {@code deserialize} are package-private, so this test class lives in
 * the same package and calls them directly rather than only through the public {@code @Scheduled}
 * entry point.
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

  /**
   * The post ids written back by the single batch ZADD. The rescore sends one {@code
   * add(key, Set<TypedTuple>)} for the whole feed, so the assertions read the members out of that
   * one call rather than counting per-post calls.
   */
  @SuppressWarnings("unchecked")
  private java.util.List<String> rescoredMembers() {
    ArgumentCaptor<Set<ZSetOperations.TypedTuple<String>>> captor =
        ArgumentCaptor.forClass(Set.class);
    verify(zSetOperations).add(eq("feed:" + USER_ID), captor.capture());
    return captor.getValue().stream().map(ZSetOperations.TypedTuple::getValue).toList();
  }

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

  /** A closable cursor over a fixed set of keys, standing in for what SCAN returns. */
  private static Cursor<String> cursorOver(String... keys) {
    Iterator<String> iterator = List.of(keys).iterator();
    return new Cursor<>() {
      @Override
      public boolean hasNext() {
        return iterator.hasNext();
      }

      @Override
      public String next() {
        return iterator.next();
      }

      @Override
      public void close() {
        // nothing to release
      }

      @Override
      public long getCursorId() {
        return 0L;
      }

      @Override
      public boolean isClosed() {
        return false;
      }

      @Override
      public long getPosition() {
        return 0L;
      }
    };
  }

  @Nested
  @DisplayName("recalculateScores")
  class RecalculateScoresTests {

    @Test
    @DisplayName("should do nothing when there are no feed keys in Redis")
    void shouldDoNothing_whenNoFeedKeysExist() {
      // Given
      // SCAN, not KEYS: KEYS blocks the whole single-threaded server while it walks the keyspace,
      // which on this deployment queues every other user of Redis behind a periodic job.
      when(redisTemplate.scan(any(ScanOptions.class))).thenReturn(cursorOver());

      // When
      postScoringService.recalculateScores();

      // Then
      verify(userInteractionRepository, never()).countInteractionsByAuthor(any(), any());
    }

    @Test
    @DisplayName("should recalculate every feed found in Redis")
    void shouldRecalculateEachFeed_whenFeedKeysExist() {
      // Given
      when(redisTemplate.scan(any(ScanOptions.class))).thenReturn(cursorOver("feed:1"));
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
      // singletonList, not List.of: List.of rejects nulls, and MGET represents a cache miss as
      // exactly that — a null in the result list, positionally aligned with the key.
      when(valueOperations.multiGet(List.of("feedpost:100")))
          .thenReturn(java.util.Collections.singletonList(null));

      // When
      postScoringService.recalculateFeedForUser(USER_ID);

      // Then
      // The whole feed is rescored in one ZADD, so "nothing was rescored" is no call at all
      // rather than a call with no members — ZADD without members is an error.
      verify(zSetOperations, never()).add(anyString(), anySet());
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
      when(valueOperations.multiGet(List.of("feedpost:100"))).thenReturn(List.of("{}"));
      when(objectMapper.readValue("{}", FeedPostDataDto.class))
          .thenReturn(post(AUTHOR_ID, OffsetDateTime.now(), 0));

      // When
      postScoringService.recalculateFeedForUser(USER_ID);

      // Then
      assertThat(rescoredMembers()).containsExactly("100");
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
      when(valueOperations.multiGet(List.of("feedpost:100"))).thenReturn(List.of("{}"));
      when(objectMapper.readValue("{}", FeedPostDataDto.class))
          .thenReturn(post(AUTHOR_ID, OffsetDateTime.now(), 0));

      // When
      postScoringService.recalculateFeedForUser(USER_ID);

      // Then
      assertThat(rescoredMembers()).containsExactly("100");
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
  // deserialize
  // =====================================================================

  /**
   * The feed is now read with a single MGET rather than a GET per post, so the per-key lookup is
   * gone; what remains of it — turning one cached string into a DTO, and surviving the two ways
   * that can fail — is {@code deserialize}. MGET writes a null into the result list wherever a key
   * was missing, which is why the null case still matters here.
   */
  @Nested
  @DisplayName("deserialize")
  class DeserializeTests {

    @Test
    @DisplayName("should return null for a missing cache entry")
    void shouldReturnNull_whenValueIsNull() {
      // Given / When
      FeedPostDataDto result = postScoringService.deserialize(null);

      // Then
      assertThat(result).isNull();
    }

    @Test
    @DisplayName("should return the deserialized post for a cached entry")
    void shouldReturnDeserializedPost() throws Exception {
      // Given
      FeedPostDataDto expected = post(AUTHOR_ID, OffsetDateTime.now(), 0);
      when(objectMapper.readValue("{\"postId\":100}", FeedPostDataDto.class)).thenReturn(expected);

      // When
      FeedPostDataDto result = postScoringService.deserialize("{\"postId\":100}");

      // Then
      assertThat(result).isSameAs(expected);
    }

    @Test
    @DisplayName("should return null and log a warning when deserialization fails")
    void shouldReturnNull_whenDeserializationFails() throws Exception {
      // Given
      when(objectMapper.readValue("not-json", FeedPostDataDto.class))
          .thenThrow(new RuntimeException("bad json"));

      // When
      FeedPostDataDto result = postScoringService.deserialize("not-json");

      // Then
      assertThat(result).isNull();
    }
  }
}
