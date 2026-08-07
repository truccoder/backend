package com.socialapp.common.utils;

import lombok.experimental.UtilityClass;

@UtilityClass
public class Constants {
  public static final String DEFAULT_PAGINATION_PAGE_SIZE = "10";
  public static final String DEFAULT_PAGINATION_SEARCH_PAGE_SIZE = "10";
  public static final String DEFAULT_PAGINATION_PAGE = "1";

  /** Rows in the search type-ahead dropdown — smaller than a results page on purpose. */
  public static final String DEFAULT_PAGINATION_SUGGEST_LIMIT = "8";
}
