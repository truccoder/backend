package com.socialapp.friendships.controller;

import java.util.List;

import org.springframework.web.bind.annotation.*;

import com.socialapp.friendships.dto.FriendListResponseDto;
import com.socialapp.friendships.dto.FriendSuggestionDto;
import com.socialapp.friendships.service.FriendshipService;
import com.socialapp.security.util.SecurityUtils;

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
      @RequestParam(defaultValue = "20") @Positive int limit) {
    return friendshipService.getFriends(SecurityUtils.getCurrentUserId(), cursor, limit);
  }

  @GetMapping("/suggestions")
  public List<FriendSuggestionDto> getSuggestions(
      @RequestParam(defaultValue = "10") @Positive int limit) {
    return friendshipService.getSuggestions(SecurityUtils.getCurrentUserId(), limit);
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
