package com.socialapp.friendships.controller;

import org.springframework.web.bind.annotation.*;

import com.socialapp.friendships.service.FriendshipService;
import com.socialapp.security.util.SecurityUtils;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/v1/api/friendships")
@RequiredArgsConstructor
public class FriendshipController {
  private final FriendshipService friendshipService;

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
