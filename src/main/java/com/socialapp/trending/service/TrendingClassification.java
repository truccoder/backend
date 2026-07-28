package com.socialapp.trending.service;

import java.util.List;
import java.util.Objects;

import com.socialapp.trending.entity.enums.TrendingCategory;

/**
 * Everything the model was asked for about one crawled article.
 *
 * <p>The prompt has always requested {@code {"category": ..., "tags": [...]}} and the model has
 * always answered with both, but the classifier only ever handed back the category, so the tags
 * were paid for and dropped on the floor — 110 of 110 rows in {@code t_trending_items} had no
 * tags. Returning a value object rather than a bare enum is what stops that happening again:
 * adding a field to the prompt now means adding it here, where the caller has to see it.
 */
public record TrendingClassification(TrendingCategory category, List<String> tags) {

  /** Fallback for an article the model could not classify, or did not answer for at all. */
  public static TrendingClassification other() {
    return new TrendingClassification(TrendingCategory.OTHER, List.of());
  }

  public TrendingClassification {
    category = Objects.isNull(category) ? TrendingCategory.OTHER : category;
    tags = Objects.isNull(tags) ? List.of() : List.copyOf(tags);
  }
}
