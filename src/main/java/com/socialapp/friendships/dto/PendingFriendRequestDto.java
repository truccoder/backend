package com.socialapp.friendships.dto;

import java.time.OffsetDateTime;

import com.socialapp.friendships.entity.enums.FriendRequestStatus;

/** An incoming friend request, enriched with the requester's display info. */
public record PendingFriendRequestDto(
    Integer id,
    Integer requesterId,
    String requesterFullName,
    String requesterProfilePictureUrl,
    FriendRequestStatus status,
    OffsetDateTime createdAt) {}
