package com.socialapp.posts.controller;

import java.util.Map;

import org.springframework.web.bind.annotation.*;

import com.socialapp.common.utils.Constants;
import com.socialapp.posts.dto.MyReactionResponseDto;
import com.socialapp.posts.dto.ReactorPageResponseDto;
import com.socialapp.posts.dto.UpsertPostReactionRequestDto;
import com.socialapp.posts.entity.enums.ReactionType;
import com.socialapp.posts.service.PostReactionService;
import com.socialapp.security.util.SecurityUtils;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/v1/api/posts/{postId}/reactions")
@RequiredArgsConstructor
public class PostReactionController {
  private final PostReactionService postReactionService;

  @GetMapping("/me")
  public MyReactionResponseDto getMyReaction(@PathVariable Integer postId) {
    return postReactionService.getMyReaction(SecurityUtils.getCurrentUserId(), postId);
  }

  /** Counts per reaction type, e.g. {@code {"LIKE": 4, "LOVE": 2}}. */
  @GetMapping("/summary")
  public Map<ReactionType, Long> getReactionSummary(@PathVariable Integer postId) {
    return postReactionService.getReactionSummary(SecurityUtils.getCurrentUserId(), postId);
  }

  /**
   * Who reacted. {@code type} narrows it to one reaction; omitted, it returns every reactor.
   *
   * <p>Mapped on the collection root, next to the PUT that writes the caller's own reaction — the
   * verb, not the path, is what distinguishes reading the list from setting your own entry.
   */
  @GetMapping
  public ReactorPageResponseDto getReactors(
      @PathVariable Integer postId,
      @RequestParam(required = false) ReactionType type,
      @RequestParam(required = false) Integer cursor,
      @RequestParam(defaultValue = "20") @Positive @Max(Constants.MAX_PAGINATION_PAGE_SIZE)
          int limit) {
    return postReactionService.getReactors(
        SecurityUtils.getCurrentUserId(), postId, type, cursor, limit);
  }

  @PutMapping
  public void upsertReaction(
      @PathVariable Integer postId, @Valid @RequestBody UpsertPostReactionRequestDto request) {
    postReactionService.upsertReaction(SecurityUtils.getCurrentUserId(), postId, request);
  }

  @DeleteMapping
  public void removeReaction(@PathVariable Integer postId) {
    postReactionService.removeReaction(SecurityUtils.getCurrentUserId(), postId);
  }
}
