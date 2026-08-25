package com.socialapp.friendships.dto;

import java.util.List;

/**
 * A cursor page of incoming friend requests.
 *
 * <p>These lists are bounded by the caller's own popularity rather than by anything global, so for
 * an ordinary account a page is the whole list. The ceiling still matters: nothing limits how many
 * requests one account may send, so a spamming account hands its targets a list as long as it cares
 * to make it, and the target is the one who pays to render it.
 *
 * <p>{@code nextCursor} is the id of the last request on this page — the same convention as
 * {@link FriendListResponseDto}.
 */
public record FriendRequestPageResponseDto(
    List<PendingFriendRequestDto> requests, Integer nextCursor, boolean hasMore) {}
