package com.socialapp.common.utils;

import lombok.experimental.UtilityClass;

@UtilityClass
public class Constants {
  public static final String DEFAULT_PAGINATION_PAGE_SIZE = "10";
  public static final String DEFAULT_PAGINATION_SEARCH_PAGE_SIZE = "10";
  public static final String DEFAULT_PAGINATION_PAGE = "1";

  /**
   * Upper bound on any client-supplied page size.
   *
   * <p>{@code @Positive} alone let {@code ?size=100000000} through to {@code PageRequest.of(0,
   * 100000001)}, which is a whole-table read the caller chose. {@code BookController} and {@code
   * ProjectController} already capped at 50 individually; this is that same cap, in one place, so
   * the next paginated endpoint inherits it instead of rediscovering the problem.
   */
  public static final int MAX_PAGINATION_PAGE_SIZE = 50;

  /** Rows in the search type-ahead dropdown — smaller than a results page on purpose. */
  public static final String DEFAULT_PAGINATION_SUGGEST_LIMIT = "8";
}
