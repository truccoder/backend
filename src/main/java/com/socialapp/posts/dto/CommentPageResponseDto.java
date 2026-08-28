package com.socialapp.posts.dto;

import java.util.List;

/**
 * A cursor page of a post's comments.
 *
 * <p>The page counts <b>top-level comments</b>; replies ride along with the root they belong to, so
 * a busy thread returns {@code limit} roots plus their replies rather than {@code limit} rows of a
 * flattened list. Paging the flat list would let a page end between a reply and its parent, which
 * a threaded UI cannot render.
 *
 * <p>{@code nextCursor} is the id of the last root on this page, {@code null} when there is nothing
 * after it — the same convention as {@link PostPageResponseDto} and {@code FriendListResponseDto}.
 */
public record CommentPageResponseDto(
    List<CommentResponseDto> comments, Integer nextCursor, boolean hasMore) {}
