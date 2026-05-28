package com.socialapp.friendships.controller;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.socialapp.friendships.service.FriendshipService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/v1/api/friendships")
@RequiredArgsConstructor
public class FriendshipController {
  private final FriendshipService friendshipService;

  @PostMapping("/requests/{addresseeId}")
  public void sendFriendRequest(
      @RequestHeader("X-User-Id") Integer actorId, @PathVariable Integer addresseeId) {
    friendshipService.sendFriendRequest(actorId, addresseeId);
  }

  @DeleteMapping("/requests/{requestId}")
  public void cancelFriendRequest(
      @RequestHeader("X-User-Id") Integer actorId, @PathVariable Integer requestId) {
    friendshipService.cancelFriendRequest(actorId, requestId);
  }

  @PostMapping("/requests/{requestId}/accept")
  public void acceptFriendRequest(
      @RequestHeader("X-User-Id") Integer actorId, @PathVariable Integer requestId) {
    friendshipService.acceptFriendRequest(actorId, requestId);
  }

  @PostMapping("/requests/{requestId}/reject")
  public void rejectFriendRequest(
      @RequestHeader("X-User-Id") Integer actorId, @PathVariable Integer requestId) {
    friendshipService.rejectFriendRequest(actorId, requestId);
  }
}
