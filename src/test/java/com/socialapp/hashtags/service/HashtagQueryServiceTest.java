package com.socialapp.hashtags.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import com.socialapp.hashtags.dto.HashtagDto;
import com.socialapp.posts.entity.HashtagEntity;
import com.socialapp.posts.repository.HashtagRepository;

/**
 * Component (unit) tests for {@link HashtagQueryService}, per ISTQB CTFL v4.0.1 (Section 2.2.1
 * component testing, Section 4.3.2 branch testing, Section 2.1.3 BDD Given/When/Then).
 */
@ExtendWith(MockitoExtension.class)
class HashtagQueryServiceTest {

  @Mock private HashtagRepository hashtagRepository;
  @InjectMocks private HashtagQueryService hashtagQueryService;

  private static HashtagEntity tag(String name, Integer usageCount) {
    HashtagEntity entity = new HashtagEntity();
    entity.setName(name);
    entity.setUsageCount(usageCount);
    return entity;
  }

  @Nested
  @DisplayName("suggest")
  class Suggest {

    @Test
    @DisplayName("folds the query and maps usage_count onto postCount")
    void foldsAndMaps() {
      when(hashtagRepository
              .findByNameStartingWithAndUsageCountGreaterThanOrderByUsageCountDescNameAsc(
                  eq("react"), eq(0), any(Pageable.class)))
          .thenReturn(List.of(tag("react", 30), tag("reactnative", 4)));

      List<HashtagDto> result = hashtagQueryService.suggest("#React", 8);

      assertThat(result)
          .containsExactly(new HashtagDto("react", 30), new HashtagDto("reactnative", 4));
    }

    @Test
    @DisplayName("treats a null usage_count as zero rather than throwing")
    void nullUsageCount() {
      when(hashtagRepository
              .findByNameStartingWithAndUsageCountGreaterThanOrderByUsageCountDescNameAsc(
                  eq("go"), eq(0), any(Pageable.class)))
          .thenReturn(List.of(tag("go", null)));

      assertThat(hashtagQueryService.suggest("go", 8)).containsExactly(new HashtagDto("go", 0));
    }

    @Test
    @DisplayName("returns empty without touching the repository when the query folds to nothing")
    void blankQuery() {
      assertThat(hashtagQueryService.suggest("  #  ", 8)).isEmpty();
      verifyNoInteractions(hashtagRepository);
    }
  }

  @Nested
  @DisplayName("trending")
  class Trending {

    private OffsetDateTime capturedSince(String window) {
      when(hashtagRepository.findTrendingSince(any(OffsetDateTime.class), any(Pageable.class)))
          .thenReturn(List.of());
      ArgumentCaptor<OffsetDateTime> since = ArgumentCaptor.forClass(OffsetDateTime.class);

      hashtagQueryService.trending(window, 10);

      verify(hashtagRepository).findTrendingSince(since.capture(), any(Pageable.class));
      return since.getValue();
    }

    @Test
    @DisplayName("today -> ~24h back")
    void today() {
      assertThat(ChronoUnit.HOURS.between(capturedSince("today"), OffsetDateTime.now()))
          .isBetween(23L, 25L);
    }

    @Test
    @DisplayName("week -> ~7d back")
    void week() {
      assertThat(ChronoUnit.HOURS.between(capturedSince("week"), OffsetDateTime.now()))
          .isBetween(167L, 169L);
    }

    @Test
    @DisplayName("month -> ~30d back")
    void month() {
      assertThat(ChronoUnit.DAYS.between(capturedSince("month"), OffsetDateTime.now()))
          .isBetween(29L, 30L);
    }

    @Test
    @DisplayName("an unknown window falls back to week rather than failing")
    void unknownWindow() {
      assertThat(ChronoUnit.HOURS.between(capturedSince("fortnight"), OffsetDateTime.now()))
          .isBetween(167L, 169L);
    }

    @Test
    @DisplayName("passes the repository rows straight through")
    void passesThrough() {
      List<HashtagDto> rows = List.of(new HashtagDto("java", 9));
      when(hashtagRepository.findTrendingSince(any(OffsetDateTime.class), any(Pageable.class)))
          .thenReturn(rows);

      assertThat(hashtagQueryService.trending("week", 10)).isEqualTo(rows);
      verify(hashtagRepository, never())
          .findByNameStartingWithAndUsageCountGreaterThanOrderByUsageCountDescNameAsc(
              any(), anyInt(), any());
    }
  }
}
