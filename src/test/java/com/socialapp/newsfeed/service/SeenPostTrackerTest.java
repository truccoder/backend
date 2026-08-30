package com.socialapp.newsfeed.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * Component (unit) tests for {@link SeenPostTracker}, per ISTQB CTFL v4.0.1 (Section 2.2.1 component
 * testing, Section 4.3.2 branch testing, Section 2.1.3 BDD Given/When/Then) — see {@code
 * PostServiceTest} for the full rationale.
 *
 * <p>This class is the storage half of the seen-post demotion, so these tests are about Redis
 * specifically: which key is written, what expiry rides along with it, and what happens when the
 * server cannot be reached. What a seen post is <i>worth</i> once it has been recorded is asserted in
 * {@code NewsfeedServiceTest} instead.
 */
@ExtendWith(MockitoExtension.class)
class SeenPostTrackerTest {

  private static final Integer USER_ID = 1;
  private static final String SEEN_KEY = "feedseen:1";
  private static final String ORDER_KEY = "feedorder:1";

  @Mock private StringRedisTemplate redisTemplate;
  @Mock private ZSetOperations<String, String> zSetOperations;
  @Mock private ListOperations<String, String> listOperations;

  @InjectMocks private SeenPostTracker seenPostTracker;

  @Nested
  @DisplayName("key naming")
  class KeyNamingTests {

    @Test
    @DisplayName("should keep both key prefixes outside the range the rescoring job sweeps")
    void shouldNotCollideWithTheFeedKeyGlob() {
      // Given: PostScoringService sweeps `SCAN MATCH feed:*` and hands every key it finds to
      // Integer.parseInt and then to ZRANGE. A key under `feed:` would be picked up by a job with
      // no business knowing about it, fail the parse, and log once per reader every five minutes.
      String glob = PostScoringService.FEED_KEY_PREFIX + "*";

      // When / Then — a regression test for a naming decision, which is the only way a naming
      // decision can be kept
      assertThat(SeenPostTracker.SEEN_KEY_PREFIX).doesNotStartWith(glob.replace("*", ""));
      assertThat(SeenPostTracker.ORDER_KEY_PREFIX).doesNotStartWith(glob.replace("*", ""));
    }

    @Test
    @DisplayName("should scope the order key by feed scope when one is given")
    void shouldScopeTheOrderKey() {
      // When / Then: the two tabs page through different lists and must not share a snapshot
      assertThat(seenPostTracker.orderKey(USER_ID, null)).isEqualTo(ORDER_KEY);
      assertThat(seenPostTracker.orderKey(USER_ID, "SKILLS")).isEqualTo(ORDER_KEY + ":SKILLS");
    }
  }

  @Nested
  @DisplayName("markSeen")
  class MarkSeenTests {

    /**
     * The script arguments are spelled out rather than captured.
     *
     * <p>An {@code ArgumentCaptor} in a varargs position matches exactly one argument, and this call
     * passes a variable number of them — and because {@code markSeen} swallows every exception on
     * purpose, a matcher that fails to line up is not reported as a mismatch but silently caught,
     * leaving a test that asserts nothing and passes anyway.
     */
    @Test
    @DisplayName("should write every id to the caller's own seen set")
    void shouldWriteIdsToTheCallersOwnKey() {
      // When
      seenPostTracker.markSeen(USER_ID, List.of(10, 11));

      // Then
      verify(redisTemplate)
          .execute(
              any(RedisScript.class),
              eq(List.of(SEEN_KEY)),
              anyString(),
              anyString(),
              anyString(),
              eq("10"),
              eq("11"));
    }

    @Test
    @DisplayName("should carry the session expiry and the cap on every write")
    void shouldCarryTtlAndCap() {
      // When
      seenPostTracker.markSeen(USER_ID, List.of(7));

      // Then: the expiry rides along on every write, so the session ends when the reader stops
      // rather than when they started, and the cap is what stops an authenticated 204 from growing
      // a Redis key without bound
      verify(redisTemplate)
          .execute(
              any(RedisScript.class),
              eq(List.of(SEEN_KEY)),
              anyString(),
              eq(String.valueOf(Duration.ofHours(6).toMillis())),
              eq(String.valueOf(1000)),
              eq("7"));
    }

    @Test
    @DisplayName("should send nothing when the id list is empty")
    void shouldSendNothing_whenEmpty() {
      // When
      seenPostTracker.markSeen(USER_ID, List.of());

      // Then
      verifyNoInteractions(redisTemplate);
    }

    @Test
    @DisplayName("should send nothing when the id list is null")
    void shouldSendNothing_whenNull() {
      // When
      seenPostTracker.markSeen(USER_ID, null);

      // Then
      verifyNoInteractions(redisTemplate);
    }

    @Test
    @DisplayName("should swallow a Redis failure so a scroll beacon never fails a request")
    void shouldSwallowRedisFailure() {
      // Given
      when(redisTemplate.execute(
              any(RedisScript.class),
              anyList(),
              anyString(),
              anyString(),
              anyString(),
              anyString()))
          .thenThrow(new RuntimeException("redis down"));

      // When / Then: the reader loses the demotion, not the scroll they are in the middle of
      assertThatCode(() -> seenPostTracker.markSeen(USER_ID, List.of(1)))
          .doesNotThrowAnyException();
    }
  }

  @Nested
  @DisplayName("hasSeenAnything")
  class HasSeenAnythingTests {

    @Test
    @DisplayName("should report nothing seen when the session key does not exist")
    void shouldReportFalse_whenKeyMissing() {
      // Given: ZCARD on a missing key is zero
      when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
      when(zSetOperations.zCard(SEEN_KEY)).thenReturn(0L);

      // When / Then
      assertThat(seenPostTracker.hasSeenAnything(USER_ID)).isFalse();
    }

    @Test
    @DisplayName("should report nothing seen when Redis answers with null")
    void shouldReportFalse_whenNull() {
      // Given
      when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
      when(zSetOperations.zCard(SEEN_KEY)).thenReturn(null);

      // When / Then
      assertThat(seenPostTracker.hasSeenAnything(USER_ID)).isFalse();
    }

    @Test
    @DisplayName("should report something seen when the session holds anything at all")
    void shouldReportTrue_whenNonEmpty() {
      // Given
      when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
      when(zSetOperations.zCard(SEEN_KEY)).thenReturn(3L);

      // When / Then
      assertThat(seenPostTracker.hasSeenAnything(USER_ID)).isTrue();
    }

    @Test
    @DisplayName("should fail open and report nothing seen when Redis throws")
    void shouldFailOpen_whenRedisThrows() {
      // Given: Redis is deliberately outside the readiness group — losing it costs features, not
      // the service
      when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
      when(zSetOperations.zCard(SEEN_KEY)).thenThrow(new RuntimeException("redis down"));

      // When / Then: the reader gets the feed in the order it was already in
      assertThat(seenPostTracker.hasSeenAnything(USER_ID)).isFalse();
    }
  }

  @Nested
  @DisplayName("seenAt")
  class SeenAtTests {

    @Test
    @DisplayName("should ask for every id in one round trip")
    void shouldUseOneMultiScoreCall() {
      // Given
      when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
      when(zSetOperations.score(eq(SEEN_KEY), any(Object[].class)))
          .thenReturn(Arrays.asList(1d, null));

      // When
      List<Double> result = seenPostTracker.seenAt(USER_ID, List.of("1", "2"));

      // Then: the cost is bounded by how many posts are being ranked rather than by how many the
      // reader has ever seen — the reason this is a sorted set and not a plain one
      assertThat(result).containsExactly(1d, null);
    }

    @Test
    @DisplayName("should not call Redis at all for an empty window")
    void shouldSkipRedis_whenNoIds() {
      // When / Then
      assertThat(seenPostTracker.seenAt(USER_ID, List.of())).isEmpty();
      verifyNoInteractions(redisTemplate);
    }

    @Test
    @DisplayName("should treat everything as unseen when Redis throws")
    void shouldFailOpen_whenRedisThrows() {
      // Given
      when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
      when(zSetOperations.score(eq(SEEN_KEY), any(Object[].class)))
          .thenThrow(new RuntimeException("redis down"));

      // When / Then
      assertThat(seenPostTracker.seenAt(USER_ID, List.of("1"))).isEmpty();
    }

    @Test
    @DisplayName("should treat everything as unseen when Redis answers with null")
    void shouldFailOpen_whenNull() {
      // Given
      when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
      when(zSetOperations.score(eq(SEEN_KEY), any(Object[].class))).thenReturn(null);

      // When / Then
      assertThat(seenPostTracker.seenAt(USER_ID, List.of("1"))).isEmpty();
    }
  }

  @Nested
  @DisplayName("saveOrder / readOrder")
  class OrderSnapshotTests {

    @Test
    @DisplayName("should replace any previous run rather than appending to it")
    void shouldReplaceThePreviousOrder() {
      // Given
      when(redisTemplate.opsForList()).thenReturn(listOperations);

      // When
      seenPostTracker.saveOrder(ORDER_KEY, List.of("2", "1"));

      // Then: appending would leave a reader paging through the concatenation of two scrolls
      verify(redisTemplate).delete(ORDER_KEY);
      verify(listOperations).rightPushAll(ORDER_KEY, List.of("2", "1"));
      verify(redisTemplate).expire(ORDER_KEY, Duration.ofMinutes(10));
    }

    @Test
    @DisplayName("should write nothing when there is no order to freeze")
    void shouldWriteNothing_whenEmpty() {
      // When
      seenPostTracker.saveOrder(ORDER_KEY, List.of());

      // Then
      verifyNoInteractions(redisTemplate);
    }

    @Test
    @DisplayName("should swallow a failure to store the order")
    void shouldSwallowSaveFailure() {
      // Given
      when(redisTemplate.opsForList()).thenReturn(listOperations);
      when(listOperations.rightPushAll(anyString(), anyList()))
          .thenThrow(new RuntimeException("redis down"));

      // When / Then: the next page falls back to the unranked window — a worse page, not a broken
      // one
      assertThatCode(() -> seenPostTracker.saveOrder(ORDER_KEY, List.of("1")))
          .doesNotThrowAnyException();
      verify(redisTemplate, never()).expire(anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("should read an inclusive range of the requested length")
    void shouldReadAnInclusiveRange() {
      // Given: LRANGE is inclusive at both ends, so asking for 3 from offset 2 is 2..4
      when(redisTemplate.opsForList()).thenReturn(listOperations);
      when(listOperations.range(ORDER_KEY, 2L, 4L)).thenReturn(List.of("a", "b", "c"));

      // When / Then
      assertThat(seenPostTracker.readOrder(ORDER_KEY, 2, 3)).containsExactly("a", "b", "c");
    }

    @Test
    @DisplayName("should report an empty order when the snapshot has expired")
    void shouldReportEmpty_whenExpired() {
      // Given
      when(redisTemplate.opsForList()).thenReturn(listOperations);
      when(listOperations.range(ORDER_KEY, 0L, 9L)).thenReturn(null);

      // When / Then: the caller reads this as "fall back", never as "end of feed"
      assertThat(seenPostTracker.readOrder(ORDER_KEY, 0, 10)).isEmpty();
    }

    @Test
    @DisplayName("should report an empty order when Redis throws")
    void shouldFailOpen_whenRedisThrows() {
      // Given
      when(redisTemplate.opsForList()).thenReturn(listOperations);
      when(listOperations.range(anyString(), anyLong(), anyLong()))
          .thenThrow(new RuntimeException("redis down"));

      // When / Then
      assertThat(seenPostTracker.readOrder(ORDER_KEY, 0, 10)).isEmpty();
    }
  }
}
