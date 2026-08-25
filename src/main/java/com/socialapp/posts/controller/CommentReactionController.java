package com.socialapp.posts.controller;

import org.springframework.web.bind.annotation.*;

import com.socialapp.common.utils.Constants;
import com.socialapp.posts.dto.ReactorPageResponseDto;
import com.socialapp.posts.dto.UpsertPostReactionRequestDto;
import com.socialapp.posts.entity.enums.ReactionType;
import com.socialapp.posts.service.CommentReactionService;
import com.socialapp.security.util.SecurityUtils;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;

/**
 * Reactions on a comment, the sibling of {@link PostReactionController}.
 *
 * <p>PUT, not POST, and that is what "symmetric with posts" means here: a caller has at most one
 * reaction per comment, so setting it is idempotent — sending LIKE twice leaves the same single
 * row rather than adding a second. {@code PostReactionController} settled this one level up and
 * the two would be needlessly different.
 *
 * <p><b>A comment can only be liked.</b> {@code LIKE} is the one value the PUT accepts; the other
 * six are 400. The body still carries a {@link
 * com.socialapp.posts.dto.UpsertPostReactionRequestDto} because the post path shares it — a post
 * does take all seven — so the narrowing lives in {@code CommentReactionService#upsertReaction}
 * and not in the DTO. That also means {@code reactionSummary} on a comment now holds at most one
 * key; it stays a map because dropping it would break a contract for no gain.
 *
 * <p>No {@code /me} and no {@code /summary}: both would have no caller. The comment list already
 * carries {@code likeCount}, {@code reactionSummary} and {@code myReaction} per comment, which is
 * where a client reads them — one query per thread rather than one request per comment, which is
 * the trade the post-level {@code /summary} endpoint gets wrong for a list.
 *
 * <p>{@code GET} on the collection root is the exception, because a list of people cannot be
 * carried inline: a comment with two hundred reactors would put two hundred profiles inside every
 * thread response to serve a dialog nobody may open.
 */
@RestController
@RequestMapping("/v1/api/posts/{postId}/comments/{commentId}/reactions")
@RequiredArgsConstructor
public class CommentReactionController {
  private final CommentReactionService commentReactionService;

  /**
   * Who reacted. {@code type} narrows it to one reaction; omitted, it returns every reactor.
   *
   * <p>Mapped on the collection root next to the PUT that writes the caller's own reaction, the
   * same shape as {@link PostReactionController#getReactors} — the verb, not the path, separates
   * reading the list from setting your own entry.
   */
  @GetMapping
  public ReactorPageResponseDto getReactors(
      @PathVariable Integer postId,
      @PathVariable Integer commentId,
      @RequestParam(required = false) ReactionType type,
      @RequestParam(required = false) Integer cursor,
      @RequestParam(defaultValue = "20") @Positive @Max(Constants.MAX_PAGINATION_PAGE_SIZE)
          int limit) {
    return commentReactionService.getReactors(
        SecurityUtils.getCurrentUserId(), postId, commentId, type, cursor, limit);
  }

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
