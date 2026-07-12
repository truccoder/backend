package com.socialapp.common.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Component (unit) tests for {@link CacheTemplateFactory}, per ISTQB CTFL v4.0.1 (Section 2.2.1
 * component testing, Section 2.1.3 BDD Given/When/Then). The class is branch-free (three
 * straight-line factory methods), so these tests exist to confirm each method wires the correct
 * {@link JavaType} into the {@link CacheTemplate} it builds — verified end-to-end with a real
 * {@link ObjectMapper} rather than by inspecting private fields.
 */
@ExtendWith(MockitoExtension.class)
class CacheTemplateFactoryTest {

  @Mock private StringRedisTemplate redis;
  @Mock private ValueOperations<String, String> valueOperations;

  private final ObjectMapper objectMapper = new ObjectMapper();
  private CacheTemplateFactory factory;

  @BeforeEach
  void setUp() {
    factory = new CacheTemplateFactory(redis, objectMapper, new CacheProperties());
  }

  @Test
  @DisplayName(
      "forType should build a template that (de)serializes a single instance of the given type")
  void shouldBuildTemplate_forSingleType() {
    // Given
    when(redis.opsForValue()).thenReturn(valueOperations);
    when(valueOperations.get("k")).thenReturn("\"Alice\"");

    // When
    CacheTemplate<String> template = factory.forType(String.class);

    // Then
    assertThat(template.get("k")).contains("Alice");
  }

  @Test
  @DisplayName(
      "forListOf should build a template that deserializes a list of the given element type")
  void shouldBuildTemplate_forListType() {
    // Given
    when(redis.opsForValue()).thenReturn(valueOperations);
    when(valueOperations.get("k")).thenReturn("[\"a\",\"b\"]");

    // When
    CacheTemplate<List<String>> template = factory.forListOf(String.class);

    // Then
    assertThat(template.get("k")).contains(List.of("a", "b"));
  }

  @Test
  @DisplayName("forJavaType should build a template using the exact JavaType provided")
  void shouldBuildTemplate_forExplicitJavaType() {
    // Given
    when(redis.opsForValue()).thenReturn(valueOperations);
    when(valueOperations.get("k")).thenReturn("42");
    JavaType intType = objectMapper.getTypeFactory().constructType(Integer.class);

    // When
    CacheTemplate<Integer> template = factory.forJavaType(intType);

    // Then
    assertThat(template.get("k")).contains(42);
  }
}
