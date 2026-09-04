package com.socialapp.hashtags;

import java.util.Locale;

/**
 * The single definition of "what string is this hashtag".
 *
 * <p>Hashtags are stored by {@code PostService.processHashtags} as the lower-cased body of the
 * {@code #(\w+)} match — no {@code #}, no case, no surrounding space. Every read path that takes a
 * hashtag from a query string has to fold the input the same way or it silently matches nothing: a
 * user typing {@code #ReactHooks} into the search box and a badge on {@code post-card.tsx} passing
 * back {@code reacthooks} must both arrive at the same lookup key.
 *
 * <p>Deliberately does <b>not</b> strip characters outside {@code [a-z0-9_]}. A query of {@code c++}
 * folds to {@code c++}, matches no row, and returns an empty result — which is the correct answer,
 * not an error. Stripping would turn it into {@code c}, i.e. a different, populated tag the user
 * never asked for.
 */
public final class HashtagNormalizer {

  private HashtagNormalizer() {}

  /**
   * @return the folded tag name, or {@code null} when the input has nothing left after folding —
   *     which callers treat as "no hashtag filter", never as "the empty hashtag".
   */
  public static String normalize(String raw) {
    if (raw == null) {
      return null;
    }
    String folded = raw.strip();
    // Strip every leading '#', not just one: a paste of "##java" or "# java" from a rendered badge
    // should still resolve to the stored name.
    int start = 0;
    while (start < folded.length()
        && (folded.charAt(start) == '#' || folded.charAt(start) == ' ')) {
      start++;
    }
    folded = folded.substring(start).strip().toLowerCase(Locale.ROOT);
    return folded.isBlank() ? null : folded;
  }
}
