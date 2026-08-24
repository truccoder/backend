package com.socialapp.posts.controller;

import org.springframework.web.bind.annotation.*;

import com.socialapp.posts.dto.UpsertPostReactionRequestDto;
import com.socialapp.posts.service.CommentReactionService;
import com.socialapp.security.util.SecurityUtils;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Reactions on a comment, the sibling of {@link PostReactionController}.
 *
 * <p>PUT, not POST, and that is what "symmetric with posts" means here: a caller has at most one
 * reaction per comment, so setting it is idempotent — sending INSIGHT twice leaves the same single
 * row, and switching from LIKE replaces it rather than adding a second. {@code
 * PostReactionController} settled this one level up and the two would be needlessly different.
 *
 * <p>No {@code /me} and no {@code /summary}: both would have no caller. The comment list already
 * carries {@code likeCount} and {@code myReaction} per comment, which is where a client reads
 * them.
 */
@RestController
@RequestMapping("/v1/api/posts/{postId}/comments/{commentId}/reactions")
@RequiredArgsConstructor
public class CommentReactionController {
  private final CommentReactionService commentReactionService;

  @PutMapping
  public void upsertReaction(
      @PathVariable Integer postId,
      @PathVariable Integer commentId,
      @Valid @RequestBody UpsertPostReactionRequestDto request) {
    commentReactionService.upsertReaction(
        SecurityUtils.getCurrentUserId(), postId, commentId, request);
  }

  @DeleteMapping
  public void removeReaction(@PathVariable Integer postId, @PathVariable Integer commentId) {
    commentReactionService.removeReaction(SecurityUtils.getCurrentUserId(), postId, commentId);
  }
}
