package com.socialapp.posts.controller;

import org.springframework.web.bind.annotation.*;

import com.socialapp.posts.dto.UpsertPostReactionRequestDto;
import com.socialapp.posts.service.PostReactionService;
import com.socialapp.security.util.SecurityUtils;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/v1/api/posts/{postId}/reactions")
@RequiredArgsConstructor
public class PostReactionController {
  private final PostReactionService postReactionService;

  @PutMapping
  public void upsertReaction(
      @PathVariable Integer postId, @RequestBody UpsertPostReactionRequestDto request) {
    postReactionService.upsertReaction(SecurityUtils.getCurrentUserId(), postId, request);
  }

  @DeleteMapping
  public void removeReaction(@PathVariable Integer postId) {
    postReactionService.removeReaction(SecurityUtils.getCurrentUserId(), postId);
  }
}
