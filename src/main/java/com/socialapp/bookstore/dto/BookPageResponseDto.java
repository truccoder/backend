package com.socialapp.bookstore.dto;

import java.util.List;

/**
 * A cursor page of books, for the Library surface.
 *
 * <p>Same shape and same cursor rule as {@code PostPageResponseDto}: {@code nextCursor} is the id
 * of the last book on the page and is {@code null} when nothing follows. Id descending rather than
 * {@code createdAt} descending because two books uploaded in the same second would make a
 * timestamp cursor either repeat or skip a row at the page boundary — ids come from a sequence, so
 * descending id is descending insertion order with a unique key.
 */
public record BookPageResponseDto(
    List<BookResponseDto> items, Integer nextCursor, boolean hasMore) {}
