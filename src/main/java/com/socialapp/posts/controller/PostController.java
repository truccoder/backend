package com.socialapp.posts.controller;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.socialapp.posts.dto.CreatePostRequestDto;
import com.socialapp.posts.dto.UpdatePostRequestDto;
import com.socialapp.posts.service.PostService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/v1/api/posts")
@RequiredArgsConstructor
public class PostController {
  private final PostService postService;

  @PostMapping
  public void createPost(
      @RequestHeader("X-User-Id") Integer authorId, @RequestBody CreatePostRequestDto request) {
    postService.createPost(authorId, request);
  }

  @PutMapping("/{postId}")
  public void updatePost(
      @RequestHeader("X-User-Id") Integer actorId,
      @PathVariable Integer postId,
      @RequestBody UpdatePostRequestDto request) {
    postService.updatePost(actorId, postId, request);
  }

  @DeleteMapping("/{postId}")
  public void deletePost(
      @RequestHeader("X-User-Id") Integer actorId, @PathVariable Integer postId) {
    postService.deletePost(actorId, postId);
  }
}
