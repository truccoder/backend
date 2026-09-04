package com.socialapp.friendships.dto;

import java.util.List;

/** A cursor page of requests the caller has sent — see {@link FriendRequestPageResponseDto}. */
public record SentFriendRequestPageResponseDto(
    List<SentFriendRequestDto> requests, Integer nextCursor, boolean hasMore) {}
