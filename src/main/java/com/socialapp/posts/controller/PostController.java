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
}
