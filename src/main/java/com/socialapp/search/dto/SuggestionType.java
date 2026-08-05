package com.socialapp.search.dto;

/**
 * What a {@link SuggestionDto} points at.
 *
 * <p>No {@code POST} member, on purpose. A post's only text is its body, which is a paragraph, not
 * a label — putting it in a dropdown row means truncating prose to 40 characters and hoping the
 * matched word is inside the window. Posts stay on the results page ({@code GET /v1/api/search}),
 * where there is room to show context around the match.
 */
public enum SuggestionType {
  USER,
  BOOK
}
