package com.socialapp.common.cache;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.function.Supplier;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class CacheTemplate<T> {
  private final StringRedisTemplate redis;
  private final ObjectMapper objectMapper;
  private final JavaType javaType;
  private final CacheProperties properties;
  private static final String LOCK_PREFIX = "lock:";
  private static final RedisScript<Long> RELEASE_LOCK_SCRIPT =
      RedisScript.of(
          """
          if redis.call('get', KEYS[1]) == ARGV[1] then
            return redis.call('del', KEYS[1])
          else
            return 0
          end
          """,
          Long.class);

  CacheTemplate(
      StringRedisTemplate redis,
      ObjectMapper objectMapper,
      JavaType javaType,
      CacheProperties properties) {
    this.redis = redis;
    this.objectMapper = objectMapper;
    this.javaType = javaType;
    this.properties = properties;
  }

  public Optional<T> get(String key) {
    try {
      String json = redis.opsForValue().get(key);
      if (Objects.isNull(json)) return Optional.empty();
      return Optional.of(objectMapper.readValue(json, javaType));
    } catch (Exception e) {
      log.warn("Cache GET failed for key '{}': {}", key, e.getMessage());
      return Optional.empty();
    }
  }

  public void set(String key, T value) {
    set(key, value, properties.getDefaultTtl());
  }

  public void set(String key, T value, Duration baseTtl) {
    try {
      String json = objectMapper.writeValueAsString(value);
      redis.opsForValue().set(key, json, jitter(baseTtl));
    } catch (Exception e) {
      log.warn("Cache SET failed for key '{}': {}", key, e.getMessage());
    }
  }

  public void evict(String key) {
    try {
      redis.delete(key);
    } catch (Exception e) {
      log.warn("Cache EVICT failed for key '{}': {}", key, e.getMessage());
    }
  }

  public T getOrLoad(String key, Supplier<T> loader) {
    Optional<T> cached = get(key);
    if (cached.isPresent()) return cached.get();

    String lockKey = LOCK_PREFIX + key;
    String lockValue = UUID.randomUUID().toString();

    if (acquireLock(lockKey, lockValue)) {
      try {
        // Double-check: another thread may have already populated the cache
        // between the first miss and our lock acquisition.
        cached = get(key);
        if (cached.isPresent()) return cached.get();

        T value = loader.get();
        if (Objects.nonNull(value)) set(key, value);
        return value;
      } finally {
        releaseLock(lockKey, lockValue);
      }
    }

    return awaitAndGet(key, loader);
  }

  public Map<String, T> getOrLoadAll(
      Collection<String> keys, Function<Collection<String>, Map<String, T>> batchLoader) {
    List<String> keyList = new ArrayList<>(keys);
    List<String> jsonValues = redis.opsForValue().multiGet(keyList);

    Map<String, T> result = new HashMap<>(keyList.size());
    List<String> misses = new ArrayList<>();

    for (int i = 0; i < keyList.size(); i++) {
      String key = keyList.get(i);
      String json = Objects.nonNull(jsonValues) ? jsonValues.get(i) : null;

      if (Objects.nonNull(json)) {
        try {
          result.put(key, objectMapper.readValue(json, javaType));
        } catch (Exception e) {
          log.warn(
              "Cache deserialise failed for key '{}', treating as miss: {}", key, e.getMessage());
          misses.add(key);
        }
      } else {
        misses.add(key);
      }
    }

    if (!misses.isEmpty()) {
      batchLoader
          .apply(misses)
          .forEach(
              (k, v) -> {
                if (Objects.nonNull(v)) {
                  set(k, v);
                  result.put(k, v);
                }
              });
    }

    return result;
  }

  // Applies a random ±{@code jitterPercent}% offset to {@code base} to spread cache expiries
  Duration jitter(Duration base) {
    int percent = properties.getJitterPercent();
    double factor = 1.0 + (ThreadLocalRandom.current().nextDouble(-percent, percent) / 100.0);
    return Duration.ofMillis(Math.max(1L, (long) (base.toMillis() * factor)));
  }

  private boolean acquireLock(String lockKey, String lockValue) {
    try {
      Boolean acquired =
          redis.opsForValue().setIfAbsent(lockKey, lockValue, properties.getLockTtl());
      return Boolean.TRUE.equals(acquired);
    } catch (Exception e) {
      log.warn("Lock acquire failed for '{}': {}", lockKey, e.getMessage());
      return false;
    }
  }

  private void releaseLock(String lockKey, String lockValue) {
    try {
      redis.execute(RELEASE_LOCK_SCRIPT, Collections.singletonList(lockKey), lockValue);
    } catch (Exception e) {
      log.warn("Lock release failed for '{}': {}", lockKey, e.getMessage());
    }
  }

  private T awaitAndGet(String key, Supplier<T> loader) {
    int maxRetries = properties.getLockMaxRetries();
    long delayMs = properties.getLockRetryDelay().toMillis();

    for (int attempt = 0; attempt < maxRetries; attempt++) {
      try {
        Thread.sleep(delayMs);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
      Optional<T> cached = get(key);
      if (cached.isPresent()) return cached.get();
    }

    log.warn(
        "Cache not populated after {} retries for key '{}', falling back to direct load",
        maxRetries,
        key);
    return loader.get();
  }
}
