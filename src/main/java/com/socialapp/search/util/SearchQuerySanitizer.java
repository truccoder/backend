package com.socialapp.search.util;

import lombok.experimental.UtilityClass;

/**
 * Diacritics (Vietnamese included) are normalized away by Postgres's {@code unaccent(...)} in the
 * repository queries themselves, so both stored data and this sanitized input are compared on
 * equal footing. This class only handles what SQL can't: trimming, and escaping LIKE's own
 * wildcard characters so a literal {@code %}/{@code _}/{@code \} in a search term doesn't get
 * treated as a pattern.
 */
@UtilityClass
public class SearchQuerySanitizer {

  public String sanitize(String rawQuery) {
    if (rawQuery == null) {
      return "";
    }
    return rawQuery.strip().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }
}
