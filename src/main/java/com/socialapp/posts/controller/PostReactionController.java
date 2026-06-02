package com.socialapp.posts.controller;

import org.springframework.web.bind.annotation.*;

import com.socialapp.posts.dto.UpsertPostReactionRequestDto;
import com.socialapp.posts.service.PostReactionService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/v1/api/posts/{postId}/reactions")
@RequiredArgsConstructor
public class PostReactionController {
  private final PostReactionService postReactionService;

  @PutMapping
  public void upsertReaction(
      @RequestHeader("X-User-Id") Integer userId,
      @PathVariable Integer postId,
      @RequestBody UpsertPostReactionRequestDto request) {
    postReactionService.upsertReaction(userId, postId, request);
  }

  @DeleteMapping
  public void removeReaction(
      @RequestHeader("X-User-Id") Integer userId, @PathVariable Integer postId) {
    postReactionService.removeReaction(userId, postId);
  }
}
