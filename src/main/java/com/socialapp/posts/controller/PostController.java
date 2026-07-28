package com.socialapp.posts.controller;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import com.socialapp.posts.dto.CreatePostRequestDto;
import com.socialapp.posts.dto.UpdatePostRequestDto;
import com.socialapp.posts.service.PostService;
import com.socialapp.security.util.SecurityUtils;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/v1/api/posts")
@RequiredArgsConstructor
public class PostController {
  private final PostService postService;

  @PostMapping
  public void createPost(@RequestBody CreatePostRequestDto request) {
    postService.createPost(SecurityUtils.getCurrentUserId(), request);
  }

  @PostMapping(value = "/books", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public void createBookPost(
      @RequestPart("metadata") CreatePostRequestDto request,
      @RequestPart("file") MultipartFile bookFile,
      @RequestPart(value = "cover", required = false) MultipartFile coverFile) {
    postService.createBookPost(SecurityUtils.getCurrentUserId(), request, bookFile, coverFile);
  }

  @PutMapping("/{postId}")
  public void updatePost(@PathVariable Integer postId, @RequestBody UpdatePostRequestDto request) {
    postService.updatePost(SecurityUtils.getCurrentUserId(), postId, request);
  }

  @DeleteMapping("/{postId}")
  public void deletePost(@PathVariable Integer postId) {
    postService.deletePost(SecurityUtils.getCurrentUserId(), postId);
  }

  @PatchMapping("/{postId}/qna/accept-answer/{commentId}")
  public void acceptAnswer(@PathVariable Integer postId, @PathVariable Integer commentId) {
    postService.acceptAnswer(SecurityUtils.getCurrentUserId(), postId, commentId);
  }

  /**
   * Takes back the accepted answer. No comment id in the path: a post has at most one accepted
   * answer, so the post alone identifies what is being undone. Switching answers is this call
   * followed by {@link #acceptAnswer} — {@code acceptAnswer} refuses to overwrite a pick that is
   * still standing.
   */
  @DeleteMapping("/{postId}/qna/accept-answer")
  public void unacceptAnswer(@PathVariable Integer postId) {
    postService.unacceptAnswer(SecurityUtils.getCurrentUserId(), postId);
  }
}
