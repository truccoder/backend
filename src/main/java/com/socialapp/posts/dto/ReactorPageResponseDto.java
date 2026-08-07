package com.socialapp.posts.dto;

import java.util.List;

import com.socialapp.security.dto.PublicUserResponse;

/**
 * A cursor page of the people who reacted to a post.
 *
 * <p>Items are {@link PublicUserResponse}, the same type the public profile returns, so a client
 * can reuse one "person row" component here, on a profile and anywhere else a stranger is
 * rendered. {@code totalCount} is the total for the requested filter, not for the page, so the UI
 * can show "12 reactions" without walking the cursor.
 */
public record ReactorPageResponseDto(
    List<PublicUserResponse> reactors, Integer nextCursor, boolean hasMore, long totalCount) {}
