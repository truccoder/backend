package com.socialapp.posts.controller;

import java.util.List;

import org.springframework.web.bind.annotation.*;

import com.socialapp.posts.dto.CommentResponseDto;
import com.socialapp.posts.dto.CreateCommentRequestDto;
import com.socialapp.posts.dto.UpdateCommentRequestDto;
import com.socialapp.posts.service.CommentService;
import com.socialapp.security.util.SecurityUtils;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/v1/api/posts/{postId}/comments")
@RequiredArgsConstructor
public class CommentController {
  private final CommentService commentService;

  @GetMapping
  public List<CommentResponseDto> getComments(@PathVariable Integer postId) {
    return commentService.getComments(postId);
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
