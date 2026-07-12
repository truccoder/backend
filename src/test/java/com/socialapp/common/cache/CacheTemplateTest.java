package com.socialapp.common.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Component (unit) tests for {@link CacheTemplate}, per ISTQB CTFL v4.0.1 (Section 2.2.1
 * component testing, Section 5.1.6 test pyramid, Section 4.3.2 branch testing, Section 2.1.3 BDD
 * Given/When/Then) — see {@code PostServiceTest} for the full rationale.
 *
 * <p>{@code CacheTemplate}'s constructor is package-private, and this test class lives in the
 * same package so it can call it directly instead of going through {@link CacheTemplateFactory}.
 * {@code lockRetryDelay} is set to 1ms and {@code lockMaxRetries} to 2 so the retry-loop tests
 * (which really do sleep) stay fast.
 */
@ExtendWith(MockitoExtension.class)
class CacheTemplateTest {

  private static final String KEY = "user:1";

  @Mock private StringRedisTemplate redis;
  @Mock private ObjectMapper objectMapper;
  @Mock private ValueOperations<String, String> valueOperations;

  private CacheProperties properties;
  private CacheTemplate<String> cacheTemplate;

  @BeforeEach
  void setUp() {
    properties = new CacheProperties();
    properties.setDefaultTtl(Duration.ofMinutes(30));
    properties.setJitterPercent(10);
    properties.setLockTtl(Duration.ofSeconds(5));
    properties.setLockRetryDelay(Duration.ofMillis(1));
    properties.setLockMaxRetries(2);

    JavaType javaType = new ObjectMapper().getTypeFactory().constructType(String.class);
    cacheTemplate = new CacheTemplate<>(redis, objectMapper, javaType, properties);
  }

  // =====================================================================
  // get
  // =====================================================================

  @Nested
  @DisplayName("get")
  class GetTests {

    @Test
    @DisplayName("should return empty on a cache miss")
    void shouldReturnEmpty_whenCacheMiss() {
      // Given
      when(redis.opsForValue()).thenReturn(valueOperations);
      when(valueOperations.get(KEY)).thenReturn(null);

      // When / Then
      assertThat(cacheTemplate.get(KEY)).isEmpty();
    }

    @Test
    @DisplayName("should return the deserialized value on a cache hit")
    void shouldReturnValue_whenCacheHit() throws Exception {
      // Given
      when(redis.opsForValue()).thenReturn(valueOperations);
      when(valueOperations.get(KEY)).thenReturn("\"Alice\"");
      when(objectMapper.readValue(eq("\"Alice\""), any(JavaType.class))).thenReturn("Alice");

      // When / Then
      assertThat(cacheTemplate.get(KEY)).contains("Alice");
    }

    @Test
    @DisplayName("should return empty when deserialization fails")
    void shouldReturnEmpty_whenDeserializationFails() throws Exception {
      // Given
      when(redis.opsForValue()).thenReturn(valueOperations);
      when(valueOperations.get(KEY)).thenReturn("not-json");
      when(objectMapper.readValue(eq("not-json"), any(JavaType.class)))
          .thenThrow(new RuntimeException("bad json"));

      // When / Then
      assertThat(cacheTemplate.get(KEY)).isEmpty();
    }
  }

  // =====================================================================
  // set
  // =====================================================================

  @Nested
  @DisplayName("set")
  class SetTests {

    @Test
    @DisplayName("should serialize and store the value with the default TTL")
    void shouldStoreWithDefaultTtl() throws Exception {
      // Given
      when(objectMapper.writeValueAsString("Alice")).thenReturn("\"Alice\"");
      when(redis.opsForValue()).thenReturn(valueOperations);

      // When
      cacheTemplate.set(KEY, "Alice");

      // Then
      verify(valueOperations).set(eq(KEY), eq("\"Alice\""), any(Duration.class));
    }

    @Test
    @DisplayName("should serialize and store the value with an explicit TTL")
    void shouldStoreWithExplicitTtl() throws Exception {
      // Given
      when(objectMapper.writeValueAsString("Alice")).thenReturn("\"Alice\"");
      when(redis.opsForValue()).thenReturn(valueOperations);

      // When
      cacheTemplate.set(KEY, "Alice", Duration.ofMinutes(5));

      // Then
      verify(valueOperations).set(eq(KEY), eq("\"Alice\""), any(Duration.class));
    }

    @Test
    @DisplayName("should swallow the error and not propagate when serialization fails")
    void shouldSwallowException_whenSerializationFails() throws Exception {
      // Given
      when(objectMapper.writeValueAsString(any())).thenThrow(new RuntimeException("boom"));

      // When / Then
      org.assertj.core.api.Assertions.assertThatCode(() -> cacheTemplate.set(KEY, "Alice"))
          .doesNotThrowAnyException();
      verify(redis, never()).opsForValue();
    }
  }

  // =====================================================================
  // evict
  // =====================================================================

  @Nested
  @DisplayName("evict")
  class EvictTests {

    @Test
    @DisplayName("should delete the key")
    void shouldDeleteKey() {
      // When
      cacheTemplate.evict(KEY);

      // Then
      verify(redis).delete(KEY);
    }

    @Test
    @DisplayName("should swallow the error and not propagate when delete fails")
    void shouldSwallowException_whenDeleteFails() {
      // Given
      when(redis.delete(anyString())).thenThrow(new RuntimeException("boom"));

      // When / Then
      org.assertj.core.api.Assertions.assertThatCode(() -> cacheTemplate.evict(KEY))
          .doesNotThrowAnyException();
    }
  }

  // =====================================================================
  // getOrLoad
  // =====================================================================

  @Nested
  @DisplayName("getOrLoad")
  class GetOrLoadTests {

    @Test
    @DisplayName("should return the cached value without acquiring a lock when already cached")
    void shouldReturnCachedValue_whenAlreadyCached() throws Exception {
      // Given
      when(redis.opsForValue()).thenReturn(valueOperations);
      when(valueOperations.get(KEY)).thenReturn("cached");
      when(objectMapper.readValue(eq("cached"), any(JavaType.class))).thenReturn("cached");

      // When
      String result = cacheTemplate.getOrLoad(KEY, () -> "loaded");

      // Then
      assertThat(result).isEqualTo("cached");
      verify(valueOperations, never()).setIfAbsent(anyString(), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("should load, cache, and return the value when the lock is acquired")
    void shouldLoadAndCache_whenLockAcquired() throws Exception {
      // Given
      when(redis.opsForValue()).thenReturn(valueOperations);
      when(valueOperations.get(KEY)).thenReturn(null);
      when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
          .thenReturn(true);
      when(objectMapper.writeValueAsString("loaded")).thenReturn("\"loaded\"");

      // When
      String result = cacheTemplate.getOrLoad(KEY, () -> "loaded");

      // Then
      assertThat(result).isEqualTo("loaded");
      verify(valueOperations).set(eq(KEY), eq("\"loaded\""), any(Duration.class));
      verify(redis).execute(any(RedisScript.class), anyList(), any());
    }

    @Test
    @DisplayName(
        "should return the double-checked cached value when another thread populated it during lock wait")
    void shouldReturnDoubleCheckedValue_whenPopulatedDuringLockWait() throws Exception {
      // Given
      when(redis.opsForValue()).thenReturn(valueOperations);
      when(valueOperations.get(KEY)).thenReturn(null).thenReturn("populated-by-other-thread");
      when(objectMapper.readValue(eq("populated-by-other-thread"), any(JavaType.class)))
          .thenReturn("populated-by-other-thread");
      when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
          .thenReturn(true);

      // When
      String result = cacheTemplate.getOrLoad(KEY, () -> "loaded");

      // Then
      assertThat(result).isEqualTo("populated-by-other-thread");
      verify(objectMapper, never()).writeValueAsString(any());
    }

    @Test
    @DisplayName("should not cache a null value returned by the loader")
    void shouldNotCacheNullValue_whenLoaderReturnsNull() {
      // Given
      when(redis.opsForValue()).thenReturn(valueOperations);
      when(valueOperations.get(KEY)).thenReturn(null);
      when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
          .thenReturn(true);

      // When
      String result = cacheTemplate.getOrLoad(KEY, () -> null);

      // Then
      assertThat(result).isNull();
      verify(valueOperations, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("should await and return the cached value once another holder populates it")
    void shouldAwaitAndReturnCachedValue_whenLockNotAcquired() throws Exception {
      // Given: first get() misses, lock fails (someone else holds it), then the retry loop's own
      // get() finally hits.
      when(redis.opsForValue()).thenReturn(valueOperations);
      when(valueOperations.get(KEY)).thenReturn(null).thenReturn("populated-by-lock-holder");
      when(objectMapper.readValue(eq("populated-by-lock-holder"), any(JavaType.class)))
          .thenReturn("populated-by-lock-holder");
      when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
          .thenReturn(false);

      // When
      String result = cacheTemplate.getOrLoad(KEY, () -> "fallback-loaded");

      // Then
      assertThat(result).isEqualTo("populated-by-lock-holder");
    }

    @Test
    @DisplayName(
        "should fall back to a direct load when retries are exhausted and the cache never populates")
    void shouldFallBackToDirectLoad_whenRetriesExhausted() {
      // Given
      when(redis.opsForValue()).thenReturn(valueOperations);
      when(valueOperations.get(KEY)).thenReturn(null);
      when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
          .thenReturn(false);

      // When
      String result = cacheTemplate.getOrLoad(KEY, () -> "fallback-loaded");

      // Then
      assertThat(result).isEqualTo("fallback-loaded");
    }

    @Test
    @DisplayName(
        "should treat a lock-acquisition error as lock-not-acquired and fall through to awaiting")
    void shouldTreatLockErrorAsNotAcquired() {
      // Given
      when(redis.opsForValue()).thenReturn(valueOperations);
      when(valueOperations.get(KEY)).thenReturn(null);
      when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
          .thenThrow(new RuntimeException("redis down"));

      // When
      String result = cacheTemplate.getOrLoad(KEY, () -> "fallback-loaded");

      // Then
      assertThat(result).isEqualTo("fallback-loaded");
    }

    @Test
    @DisplayName("should swallow a lock-release error without propagating it")
    void shouldSwallowLockReleaseError() throws Exception {
      // Given
      when(redis.opsForValue()).thenReturn(valueOperations);
      when(valueOperations.get(KEY)).thenReturn(null);
      when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
          .thenReturn(true);
      when(redis.execute(any(RedisScript.class), anyList(), any()))
          .thenThrow(new RuntimeException("boom"));
      when(objectMapper.writeValueAsString("loaded")).thenReturn("\"loaded\"");

      // When / Then
      org.assertj.core.api.Assertions.assertThatCode(
              () -> cacheTemplate.getOrLoad(KEY, () -> "loaded"))
          .doesNotThrowAnyException();
    }
  }

  // =====================================================================
  // getOrLoadAll
  // =====================================================================

  @Nested
  @DisplayName("getOrLoadAll")
  class GetOrLoadAllTests {

    @Test
    @DisplayName("should return every value from the cache when all keys hit")
    void shouldReturnAllFromCache_whenEveryKeyHits() throws Exception {
      // Given
      when(redis.opsForValue()).thenReturn(valueOperations);
      when(valueOperations.multiGet(List.of("k1"))).thenReturn(List.of("\"v1\""));
      when(objectMapper.readValue(eq("\"v1\""), any(JavaType.class))).thenReturn("v1");

      // When
      Map<String, String> result = cacheTemplate.getOrLoadAll(List.of("k1"), missed -> Map.of());

      // Then
      assertThat(result).containsEntry("k1", "v1");
    }

    @Test
    @DisplayName("should load missing keys from the batch loader")
    void shouldLoadMissesFromBatchLoader() throws Exception {
      // Given
      when(redis.opsForValue()).thenReturn(valueOperations);
      when(valueOperations.multiGet(List.of("k1")))
          .thenReturn(java.util.Collections.singletonList(null));
      when(objectMapper.writeValueAsString("loaded-v1")).thenReturn("\"loaded-v1\"");

      // When
      Map<String, String> result =
          cacheTemplate.getOrLoadAll(List.of("k1"), missed -> Map.of("k1", "loaded-v1"));

      // Then
      assertThat(result).containsEntry("k1", "loaded-v1");
      verify(valueOperations).set(eq("k1"), eq("\"loaded-v1\""), any(Duration.class));
    }

    @Test
    @DisplayName("should treat a null multiGet result as every key missing")
    void shouldTreatNullMultiGetResult_asAllMisses() {
      // Given
      when(redis.opsForValue()).thenReturn(valueOperations);
      when(valueOperations.multiGet(List.of("k1"))).thenReturn(null);

      // When
      AtomicInteger calls = new AtomicInteger();
      Map<String, String> result =
          cacheTemplate.getOrLoadAll(
              List.of("k1"),
              missed -> {
                calls.incrementAndGet();
                return Map.of();
              });

      // Then
      assertThat(calls.get()).isEqualTo(1);
      assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("should treat an undeserializable cached entry as a miss")
    void shouldTreatUndeserializableEntry_asMiss() throws Exception {
      // Given
      when(redis.opsForValue()).thenReturn(valueOperations);
      when(valueOperations.multiGet(List.of("k1"))).thenReturn(List.of("not-json"));
      when(objectMapper.readValue(eq("not-json"), any(JavaType.class)))
          .thenThrow(new RuntimeException("bad json"));

      // When
      Map<String, String> result =
          cacheTemplate.getOrLoadAll(List.of("k1"), missed -> Map.of("k1", "recovered"));

      // Then
      assertThat(result).containsEntry("k1", "recovered");
    }

    @Test
    @DisplayName("should skip null values returned by the batch loader")
    void shouldSkipNullValuesFromBatchLoader() {
      // Given
      when(redis.opsForValue()).thenReturn(valueOperations);
      when(valueOperations.multiGet(List.of("k1")))
          .thenReturn(java.util.Collections.singletonList(null));
      Map<String, String> loaderResult = new HashMap<>();
      loaderResult.put("k1", null);

      // When
      Map<String, String> result =
          cacheTemplate.getOrLoadAll(List.of("k1"), missed -> loaderResult);

      // Then
      assertThat(result).doesNotContainKey("k1");
      verify(valueOperations, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("should not call the batch loader when there are no misses")
    void shouldNotCallBatchLoader_whenNoMisses() throws Exception {
      // Given
      when(redis.opsForValue()).thenReturn(valueOperations);
      when(valueOperations.multiGet(List.of("k1"))).thenReturn(List.of("\"v1\""));
      when(objectMapper.readValue(eq("\"v1\""), any(JavaType.class))).thenReturn("v1");
      AtomicInteger calls = new AtomicInteger();

      // When
      cacheTemplate.getOrLoadAll(
          List.of("k1"),
          missed -> {
            calls.incrementAndGet();
            return Map.of();
          });

      // Then
      assertThat(calls.get()).isZero();
    }
  }

  // =====================================================================
  // jitter
  // =====================================================================

  @Nested
  @DisplayName("jitter")
  class JitterTests {

    @Test
    @DisplayName("should keep the jittered duration within the configured percentage band")
    void shouldStayWithinExpectedBounds() {
      // Given
      properties.setJitterPercent(10);
      Duration base = Duration.ofMinutes(30);

      // When / Then — run several times since jitter is randomized.
      for (int i = 0; i < 20; i++) {
        Duration jittered = cacheTemplate.jitter(base);
        assertThat(jittered.toMillis())
            .isBetween((long) (base.toMillis() * 0.9), (long) (base.toMillis() * 1.1) + 1);
      }
    }

    @Test
    @DisplayName("should never return a non-positive duration even for a tiny base")
    void shouldNeverReturnNonPositiveDuration() {
      // Given
      Duration tinyBase = Duration.ofMillis(1);

      // When / Then
      assertThat(cacheTemplate.jitter(tinyBase).toMillis()).isPositive();
    }
  }
}
