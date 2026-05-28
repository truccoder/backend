package com.socialapp.common.cache;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.TypeFactory;

@Component
public class CacheTemplateFactory {
  private final StringRedisTemplate redis;
  private final ObjectMapper objectMapper;
  private final CacheProperties properties;

  public CacheTemplateFactory(
      StringRedisTemplate redis,
      @Qualifier("cacheObjectMapper") ObjectMapper objectMapper,
      CacheProperties properties) {
    this.redis = redis;
    this.objectMapper = objectMapper;
    this.properties = properties;
  }

  public <T> CacheTemplate<T> forType(Class<T> type) {
    JavaType javaType = typeFactory().constructType(type);
    return new CacheTemplate<>(redis, objectMapper, javaType, properties);
  }

  public <T> CacheTemplate<java.util.List<T>> forListOf(Class<T> elementType) {
    JavaType javaType = typeFactory().constructCollectionType(java.util.List.class, elementType);
    return new CacheTemplate<>(redis, objectMapper, javaType, properties);
  }

  public <T> CacheTemplate<T> forJavaType(JavaType javaType) {
    return new CacheTemplate<>(redis, objectMapper, javaType, properties);
  }

  private TypeFactory typeFactory() {
    return objectMapper.getTypeFactory();
  }
}
