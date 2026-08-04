package com.socialapp.posts.dto;

import java.util.List;

import com.socialapp.newsfeed.dto.FeedPostDataDto;

/**
 * A cursor page of posts.
 *
 * <p>The items are {@link FeedPostDataDto} — the exact shape {@code /v1/api/feed} already returns
 * — so the client can render an author's posts, the discovery feed and the personal feed with one
 * card component. {@code nextCursor} is the id of the last post on this page and is {@code null}
 * when there is nothing after it; it mirrors {@code FriendListResponseDto}, which is the cursor
 * convention this API already established.
 */
public record PostPageResponseDto(
    List<FeedPostDataDto> posts, Integer nextCursor, boolean hasMore) {}
