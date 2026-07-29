package com.socialapp.common.cache;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

@Configuration
@EnableConfigurationProperties(CacheProperties.class)
public class RedisConfig {
  @Bean
  public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory factory) {
    StringRedisTemplate template = new StringRedisTemplate();
    template.setConnectionFactory(factory);
    template.setKeySerializer(new StringRedisSerializer());
    template.setValueSerializer(new StringRedisSerializer());
    template.afterPropertiesSet();
    return template;
  }

  // Dedicated ObjectMapper for Redis
  // Make it use JavaTimeModule to serialize date "2026-05-28T17:44:15"
  @Bean("cacheObjectMapper")
  public ObjectMapper cacheObjectMapper() {
    return new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        // A cache always reads JSON written by an older version of the code, so unknown properties
        // are the normal case, not a bug: the moment a field is dropped from a cached DTO, every
        // entry written before that deploy carries a property the new class cannot bind. With
        // FAIL_ON_UNKNOWN_PROPERTIES left on (Jackson's default) each of those reads throws and is
        // swallowed as "WARN Failed to deserialize cached post" — the post silently disappears
        // from the feed until its 7-day TTL runs out. Dropping the field is meant to be a
        // display-only change; this makes it one. Two quiz DTOs already carry a per-class
        // @JsonIgnoreProperties for exactly this reason; this moves the guarantee to the one place
        // that cannot be forgotten on the next DTO.
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
  }
}
