package com.socialapp.common.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * Component (unit) tests for {@link RedisConfig}, per ISTQB CTFL v4.0.1 (Section 2.2.1 component
 * testing, Section 2.1.3 BDD Given/When/Then).
 *
 * <p>The bean is a three-line factory, so what is worth testing is the contract it carries: a
 * cache reads JSON that an older build wrote, so the mapper must survive properties the current
 * class no longer declares. Asserted through real (de)serialization rather than by reading feature
 * flags, so the test still fails if a future edit satisfies the flag by some other route.
 */
class RedisConfigTest {

  /** Stands in for a cached DTO that has since had a field removed. */
  static class ShrunkDto {
    private String keptField;

    public String getKeptField() {
      return keptField;
    }

    public void setKeptField(String keptField) {
      this.keptField = keptField;
    }
  }

  private final ObjectMapper mapper = new RedisConfig().cacheObjectMapper();

  @Test
  @DisplayName("should deserialize a cached entry that carries a field the DTO no longer declares")
  void shouldIgnoreUnknownProperties() throws Exception {
    // Given — JSON written before droppedField was removed from the DTO
    String legacyJson =
        "{\"keptField\":\"still here\",\"droppedField\":\"written by an old build\"}";

    // When / Then — the old entry must still load; failing here means the post silently falls out
    // of the feed until its TTL expires.
    assertThatCode(() -> mapper.readValue(legacyJson, ShrunkDto.class)).doesNotThrowAnyException();
    assertThat(mapper.readValue(legacyJson, ShrunkDto.class).getKeptField())
        .isEqualTo("still here");
  }

  @Test
  @DisplayName("should keep writing dates as ISO strings, not epoch numbers")
  void shouldSerializeDatesAsIsoStrings() throws Exception {
    // Given
    OffsetDateTime moment = OffsetDateTime.of(2026, 5, 28, 17, 44, 15, 0, ZoneOffset.UTC);

    // When
    String json = mapper.writeValueAsString(moment);

    // Then
    assertThat(json).startsWith("\"2026-05-28T17:44:15");
  }

  @Test
  @DisplayName("should round-trip a date through the cache mapper unchanged")
  void shouldRoundTripDates() throws Exception {
    // Given
    OffsetDateTime moment = OffsetDateTime.of(2026, 5, 28, 17, 44, 15, 0, ZoneOffset.UTC);

    // When
    OffsetDateTime restored =
        mapper.readValue(mapper.writeValueAsString(moment), OffsetDateTime.class);

    // Then
    assertThat(restored.toInstant()).isEqualTo(moment.toInstant());
  }

  @Test
  @DisplayName("should expose the two features the cache contract depends on")
  void shouldConfigureFeatures() {
    // Then
    assertThat(mapper.isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)).isFalse();
    assertThat(mapper.isEnabled(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)).isFalse();
  }
}
