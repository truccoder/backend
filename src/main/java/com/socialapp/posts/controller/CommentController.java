package com.socialapp.posts.controller;

import org.springframework.web.bind.annotation.*;

import com.socialapp.posts.dto.CreateCommentRequestDto;
import com.socialapp.posts.dto.UpdateCommentRequestDto;
import com.socialapp.posts.service.CommentService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/v1/api/posts/{postId}/comments")
@RequiredArgsConstructor
public class CommentController {
  private final CommentService commentService;

  @PostMapping
  public void createComment(
      @RequestHeader("X-User-Id") Integer authorId,
      @PathVariable Integer postId,
      @RequestBody CreateCommentRequestDto request) {
    commentService.createComment(authorId, postId, request);
  }

  @DeleteMapping("/{commentId}")
  public void deleteComment(
      @RequestHeader("X-User-Id") Integer actorId,
      @PathVariable Integer postId,
      @PathVariable Integer commentId) {
    commentService.deleteComment(actorId, postId, commentId);
  }

  @PutMapping("/{commentId}")
  public void updateComment(
      @RequestHeader("X-User-Id") Integer actorId,
      @PathVariable Integer postId,
      @PathVariable Integer commentId,
      @RequestBody UpdateCommentRequestDto request) {
    commentService.updateComment(actorId, postId, commentId, request);
  }
}
