package com.socialapp.trending.service;

import java.time.OffsetDateTime;
import java.util.Objects;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import com.socialapp.trending.dto.TrendingItemDto;
import com.socialapp.trending.dto.TrendingPageResponseDto;
import com.socialapp.trending.entity.TrendingItemEntity;
import com.socialapp.trending.entity.enums.TrendingCategory;
import com.socialapp.trending.repository.TrendingItemRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class TrendingService {
  private final TrendingItemRepository trendingItemRepository;

  public TrendingPageResponseDto getTrending(
      TrendingCategory category, String timeRange, int page, int size) {
    Pageable pageable = PageRequest.of(page - 1, size);
    OffsetDateTime since = resolveTimeRange(timeRange);

    Page<TrendingItemEntity> resultPage;
    if (Objects.nonNull(category)) {
      resultPage = trendingItemRepository.findTrendingByCategorySince(category, since, pageable);
    } else {
      resultPage = trendingItemRepository.findTrendingSince(since, pageable);
    }

    return TrendingPageResponseDto.builder()
        .items(resultPage.getContent().stream().map(this::toDto).toList())
        .page(page)
        .size(size)
        .totalElements(resultPage.getTotalElements())
        .totalPages(resultPage.getTotalPages())
        .hasNext(resultPage.hasNext())
        .build();
  }

  private OffsetDateTime resolveTimeRange(String timeRange) {
    if (Objects.isNull(timeRange) || timeRange.isBlank()) {
      return OffsetDateTime.now().minusDays(7);
    }
    return switch (timeRange.toLowerCase()) {
      case "today" -> OffsetDateTime.now().minusDays(1);
      case "week" -> OffsetDateTime.now().minusDays(7);
      case "month" -> OffsetDateTime.now().minusDays(30);
      default -> OffsetDateTime.now().minusDays(7);
    };
  }

  private TrendingItemDto toDto(TrendingItemEntity entity) {
    return TrendingItemDto.builder()
        .id(entity.getId())
        .title(entity.getTitle())
        .summary(entity.getSummary())
        .url(entity.getUrl())
        .source(entity.getSource())
        .category(entity.getCategory())
        .tags(entity.getTags())
        .score(entity.getScore())
        .author(entity.getAuthor())
        .publishedAt(entity.getPublishedAt())
        .build();
  }
}
