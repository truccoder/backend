package com.socialapp.common.cache;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

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
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
  }
}
