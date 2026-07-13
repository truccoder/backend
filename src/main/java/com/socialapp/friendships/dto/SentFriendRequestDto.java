package com.socialapp.friendships.dto;

import java.time.OffsetDateTime;

import com.socialapp.friendships.entity.enums.FriendRequestStatus;

/** An outgoing friend request, enriched with the addressee's display info. */
public record SentFriendRequestDto(
    Integer id,
    Integer addresseeId,
    String addresseeFullName,
    String addresseeProfilePictureUrl,
    FriendRequestStatus status,
    OffsetDateTime createdAt) {}
