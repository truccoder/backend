package com.socialapp.hashtags.service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialapp.hashtags.HashtagNormalizer;
import com.socialapp.hashtags.dto.HashtagDto;
import com.socialapp.posts.entity.HashtagEntity;
import com.socialapp.posts.repository.HashtagRepository;

import lombok.RequiredArgsConstructor;

/**
 * Reads over {@code t_hashtags} — B31 in {@code docs/backend-plan.md}.
 *
 * <p>The table has existed since {@code V41} and, until this class, was written by {@code
 * PostService.processHashtags} and read only by {@code SkillTagResolver} for the feed's SKILLS tab.
 * Nothing exposed a hashtag to a client, so the badge on {@code post-card.tsx} was inert and the
 * search box had no way to complete a {@code #}. These two lists are the read side.
 *
 * <p>The clickable third leg the FE asked for is not here: a hashtag on a post links to {@code GET
 * /v1/api/posts/public?hashtag=}, which is {@code PostQueryService}'s discovery feed with one more
 * predicate rather than a second feed with its own visibility rules to keep in step.
 */
@Service
@RequiredArgsConstructor
public class HashtagQueryService {

  private final HashtagRepository hashtagRepository;

  /**
   * The tags whose name starts with what the user has typed, most-used first.
   *
   * <p>{@code query} is folded through {@link HashtagNormalizer} so {@code #ReactHooks}, {@code
   * reacthooks} and {@code  ReactHooks } all look up the same prefix. A query that folds to nothing
   * (a lone {@code #}, whitespace) returns an empty list rather than every tag in the table — the
   * "no query typed" case is what {@code /hashtags/trending} answers.
   *
   * <p>Tags no post carries are left out — see the repository method for why a suggestion that
   * completes to an empty feed is worse than a shorter list.
   */
  @Transactional(readOnly = true)
  public List<HashtagDto> suggest(String query, int limit) {
    String prefix = HashtagNormalizer.normalize(query);
    if (prefix == null) {
      return List.of();
    }
    return hashtagRepository
        .findByNameStartingWithAndUsageCountGreaterThanOrderByUsageCountDescNameAsc(
            prefix, 0, PageRequest.of(0, limit))
        .stream()
        .map(HashtagQueryService::toDto)
        .toList();
  }

  /**
   * The tags on the most public posts inside {@code window}.
   *
   * <p>{@code window} reuses the vocabulary of {@code GET /v1/api/trending} — {@code today} / {@code
   * week} / {@code month}, anything else falling back to {@code week} — so a client that already
   * speaks to the trending endpoint does not learn a second set of tokens.
   */
  @Transactional(readOnly = true)
  public List<HashtagDto> trending(String window, int limit) {
    return hashtagRepository.findTrendingSince(since(window), PageRequest.of(0, limit));
  }

  private static HashtagDto toDto(HashtagEntity entity) {
    long count = entity.getUsageCount() == null ? 0L : entity.getUsageCount();
    return new HashtagDto(entity.getName(), count);
  }

  private static OffsetDateTime since(String window) {
    OffsetDateTime now = OffsetDateTime.now();
    if (Objects.isNull(window) || window.isBlank()) {
      return now.minusDays(7);
    }
    return switch (window.toLowerCase(Locale.ROOT)) {
      case "today" -> now.minusDays(1);
      case "month" -> now.minusDays(30);
      default -> now.minusDays(7);
    };
  }
}
