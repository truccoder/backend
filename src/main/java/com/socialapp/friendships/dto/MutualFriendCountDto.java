package com.socialapp.friendships.dto;

/**
 * Cache-friendly (Jackson-serializable) copy of {@link FriendSuggestionProjection}.
 */
public record MutualFriendCountDto(Integer userId, long mutualFriends) {}
