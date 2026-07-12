package com.socialapp.trending.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import com.socialapp.trending.dto.TrendingPageResponseDto;
import com.socialapp.trending.entity.TrendingItemEntity;
import com.socialapp.trending.entity.enums.TrendingCategory;
import com.socialapp.trending.repository.TrendingItemRepository;

/**
 * Component (unit) tests for {@link TrendingService}, per ISTQB CTFL v4.0.1 (Section 2.2.1
 * component testing, Section 5.1.6 test pyramid, Section 4.3.2 branch testing — including
 * switch-case branches per {@code resolveTimeRange}'s {@code switch} expression, Section 2.1.3 BDD
 * Given/When/Then) — see {@code PostServiceTest} for the full rationale.
 */
@ExtendWith(MockitoExtension.class)
class TrendingServiceTest {

  @Mock private TrendingItemRepository trendingItemRepository;

  @InjectMocks private TrendingService trendingService;

  @Captor private ArgumentCaptor<OffsetDateTime> sinceCaptor;

  private static TrendingItemEntity item(Integer id, String title) {
    return TrendingItemEntity.builder().id(id).title(title).category(TrendingCategory.TOOL).build();
  }

  // =====================================================================
  // getTrending — category branch
  // =====================================================================

  @Nested
  @DisplayName("getTrending")
  class GetTrendingTests {

    @Test
    @DisplayName("should query by category when one is provided")
    void shouldQueryByCategory_whenCategoryProvided() {
      // Given
      Page<TrendingItemEntity> page = new PageImpl<>(java.util.List.of(item(1, "A")));
      when(trendingItemRepository.findTrendingByCategorySince(
              eq(TrendingCategory.TOOL), any(), eq(PageRequest.of(0, 10))))
          .thenReturn(page);

      // When
      TrendingPageResponseDto result =
          trendingService.getTrending(TrendingCategory.TOOL, "week", 1, 10);

      // Then
      assertThat(result.getItems()).hasSize(1);
      assertThat(result.getItems().get(0).getTitle()).isEqualTo("A");
    }

    @Test
    @DisplayName("should query across all categories when none is provided")
    void shouldQueryAllCategories_whenCategoryIsNull() {
      // Given
      Page<TrendingItemEntity> page = new PageImpl<>(java.util.List.of(item(1, "A")));
      when(trendingItemRepository.findTrendingSince(any(), eq(PageRequest.of(0, 10))))
          .thenReturn(page);

      // When
      TrendingPageResponseDto result = trendingService.getTrending(null, "week", 1, 10);

      // Then
      assertThat(result.getItems()).hasSize(1);
    }

    @Test
    @DisplayName("should map every page metadata field")
    void shouldMapPageMetadata() {
      // Given
      Page<TrendingItemEntity> page =
          new PageImpl<>(java.util.List.of(item(1, "A")), PageRequest.of(0, 5), 12);
      when(trendingItemRepository.findTrendingSince(any(), eq(PageRequest.of(0, 5))))
          .thenReturn(page);

      // When
      TrendingPageResponseDto result = trendingService.getTrending(null, "week", 1, 5);

      // Then
      assertThat(result.getPage()).isEqualTo(1);
      assertThat(result.getSize()).isEqualTo(5);
      assertThat(result.getTotalElements()).isEqualTo(12);
      assertThat(result.getTotalPages()).isEqualTo(page.getTotalPages());
      assertThat(result.isHasNext()).isEqualTo(page.hasNext());
    }
  }

  // =====================================================================
  // getTrending — resolveTimeRange branches (if/else + switch)
  // =====================================================================

  @Nested
  @DisplayName("resolveTimeRange (via getTrending)")
  class ResolveTimeRangeTests {

    private OffsetDateTime captureSince(String timeRange) {
      when(trendingItemRepository.findTrendingSince(sinceCaptor.capture(), any()))
          .thenReturn(Page.empty());
      trendingService.getTrending(null, timeRange, 1, 10);
      return sinceCaptor.getValue();
    }

    @Test
    @DisplayName("should default to a 7-day window when the time range is null")
    void shouldUseSevenDayWindow_whenTimeRangeIsNull() {
      OffsetDateTime since = captureSince(null);
      assertThat(since).isCloseTo(OffsetDateTime.now().minusDays(7), within(2));
    }

    @Test
    @DisplayName("should default to a 7-day window when the time range is blank")
    void shouldUseSevenDayWindow_whenTimeRangeIsBlank() {
      OffsetDateTime since = captureSince("   ");
      assertThat(since).isCloseTo(OffsetDateTime.now().minusDays(7), within(2));
    }

    @Test
    @DisplayName("should use a 1-day window for \"today\"")
    void shouldUseOneDayWindow_whenTimeRangeIsToday() {
      OffsetDateTime since = captureSince("today");
      assertThat(since).isCloseTo(OffsetDateTime.now().minusDays(1), within(2));
    }

    @Test
    @DisplayName("should be case-insensitive when matching \"today\"")
    void shouldMatchToday_caseInsensitively() {
      OffsetDateTime since = captureSince("TODAY");
      assertThat(since).isCloseTo(OffsetDateTime.now().minusDays(1), within(2));
    }

    @Test
    @DisplayName("should use a 7-day window for \"week\"")
    void shouldUseSevenDayWindow_whenTimeRangeIsWeek() {
      OffsetDateTime since = captureSince("week");
      assertThat(since).isCloseTo(OffsetDateTime.now().minusDays(7), within(2));
    }

    @Test
    @DisplayName("should use a 30-day window for \"month\"")
    void shouldUseThirtyDayWindow_whenTimeRangeIsMonth() {
      OffsetDateTime since = captureSince("month");
      assertThat(since).isCloseTo(OffsetDateTime.now().minusDays(30), within(2));
    }

    @Test
    @DisplayName("should fall back to a 7-day window for an unrecognized value")
    void shouldUseSevenDayWindowAsDefault_whenTimeRangeIsUnrecognized() {
      OffsetDateTime since = captureSince("decade");
      assertThat(since).isCloseTo(OffsetDateTime.now().minusDays(7), within(2));
    }

    private org.assertj.core.data.TemporalUnitOffset within(long minutes) {
      return org.assertj.core.api.Assertions.within(minutes, ChronoUnit.MINUTES);
    }
  }
}
