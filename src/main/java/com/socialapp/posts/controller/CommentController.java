package com.socialapp.posts.controller;

import org.springframework.web.bind.annotation.*;

import com.socialapp.common.utils.Constants;
import com.socialapp.posts.dto.CommentPageResponseDto;
import com.socialapp.posts.dto.CreateCommentRequestDto;
import com.socialapp.posts.dto.UpdateCommentRequestDto;
import com.socialapp.posts.service.CommentService;
import com.socialapp.security.util.SecurityUtils;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/v1/api/posts/{postId}/comments")
@RequiredArgsConstructor
public class CommentController {
  private final CommentService commentService;

  /**
   * One page of a post's comments, oldest first.
   *
   * <p>This used to return the whole thread. A comment list has no upper bound the way a feed does,
   * so the busiest post in the system produced the largest response the API could emit, on the
   * endpoint that gets hit hardest while a post is trending. The page counts top-level comments and
   * carries their replies with them — see {@code CommentPageResponseDto}.
   */
  @GetMapping
  public CommentPageResponseDto getComments(
      @PathVariable Integer postId,
      @RequestParam(required = false) Integer cursor,
      @RequestParam(defaultValue = "20") @Positive @Max(Constants.MAX_PAGINATION_PAGE_SIZE)
          int limit) {
    return commentService.getComments(SecurityUtils.getCurrentUserId(), postId, cursor, limit);
  }

  @PostMapping
  public void createComment(
      @PathVariable Integer postId, @Valid @RequestBody CreateCommentRequestDto request) {
    commentService.createComment(SecurityUtils.getCurrentUserId(), postId, request);
  }

  @PutMapping("/{commentId}")
  public void updateComment(
      @PathVariable Integer postId,
      @PathVariable Integer commentId,
      @Valid @RequestBody UpdateCommentRequestDto request) {
    commentService.updateComment(SecurityUtils.getCurrentUserId(), postId, commentId, request);
  }

  @DeleteMapping("/{commentId}")
  public void deleteComment(@PathVariable Integer postId, @PathVariable Integer commentId) {
    commentService.deleteComment(SecurityUtils.getCurrentUserId(), postId, commentId);
  }
}
