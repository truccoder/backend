package com.socialapp.matchmaking.dto;

import java.util.List;

/**
 * A cursor page of projects. Same {@code {items, nextCursor, hasMore}} contract and same
 * descending-id cursor as {@code PostPageResponseDto} and {@code BookPageResponseDto} — one paging
 * convention across the API, so a client writes the scroll logic once.
 */
public record ProjectPageResponseDto(
    List<ProjectResponseDto> items, Integer nextCursor, boolean hasMore) {}
