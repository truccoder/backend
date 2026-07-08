package com.socialapp.friendships.dto;

import java.util.List;

public record FriendListResponseDto(
    List<UserProfileDto> friends, Integer nextCursor, boolean hasMore, long totalCount) {}
