package com.socialapp.friendships.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import com.socialapp.common.utils.Constants;
import com.socialapp.friendships.dto.FriendListResponseDto;
import com.socialapp.friendships.dto.FriendSuggestionDto;
import com.socialapp.friendships.dto.PendingFriendRequestDto;
import com.socialapp.friendships.dto.SentFriendRequestDto;
import com.socialapp.friendships.service.FriendshipService;
import com.socialapp.security.util.SecurityUtils;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/v1/api/friendships")
@RequiredArgsConstructor
public class FriendshipController {
  private final FriendshipService friendshipService;

  @GetMapping
  public FriendListResponseDto getFriends(
      @RequestParam(required = false) Integer cursor,
      @RequestParam(defaultValue = "20") @Positive @Max(Constants.MAX_PAGINATION_PAGE_SIZE)
          int limit) {
    return friendshipService.getFriends(SecurityUtils.getCurrentUserId(), cursor, limit);
  }

  @GetMapping("/suggestions")
  public List<FriendSuggestionDto> getSuggestions(
      @RequestParam(defaultValue = "10") @Positive @Max(Constants.MAX_PAGINATION_PAGE_SIZE)
          int limit) {
    return friendshipService.getSuggestions(SecurityUtils.getCurrentUserId(), limit);
  }

  @GetMapping("/requests/pending")
  public List<PendingFriendRequestDto> getPendingRequests() {
    return friendshipService.getPendingRequests(SecurityUtils.getCurrentUserId());
  }

  @GetMapping("/requests/sent")
  public List<SentFriendRequestDto> getSentRequests() {
    return friendshipService.getSentRequests(SecurityUtils.getCurrentUserId());
  }

  /**
   * Unfriend. 204 even when the two were not friends — see {@code FriendshipService#unfriend} for
   * why this is idempotent rather than a 404.
   *
   * <p>{@code /{userId}} and not {@code /requests/{id}}: what is being deleted is the friendship,
   * which is identified by the other person, not the request row that once created it.
   */
  @DeleteMapping("/{userId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void unfriend(@PathVariable Integer userId) {
    friendshipService.unfriend(SecurityUtils.getCurrentUserId(), userId);
  }

  @PostMapping("/requests/{addresseeId}")
  public void sendFriendRequest(@PathVariable Integer addresseeId) {
    friendshipService.sendFriendRequest(SecurityUtils.getCurrentUserId(), addresseeId);
  }

  @DeleteMapping("/requests/{requestId}")
  public void cancelFriendRequest(@PathVariable Integer requestId) {
    friendshipService.cancelFriendRequest(SecurityUtils.getCurrentUserId(), requestId);
  }

  @PostMapping("/requests/{requestId}/accept")
  public void acceptFriendRequest(@PathVariable Integer requestId) {
    friendshipService.acceptFriendRequest(SecurityUtils.getCurrentUserId(), requestId);
  }

  @PostMapping("/requests/{requestId}/reject")
  public void rejectFriendRequest(@PathVariable Integer requestId) {
    friendshipService.rejectFriendRequest(SecurityUtils.getCurrentUserId(), requestId);
  }
}
