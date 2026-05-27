package com.socialapp.posts.controller;

import com.socialapp.posts.dto.CreatePostRequest;
import com.socialapp.posts.service.PostService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/api/posts")
@RequiredArgsConstructor
public class PostController {

  private final PostService postService;

  @PostMapping
  public ResponseEntity<Void> createPost(@Valid @RequestBody CreatePostRequest request) {
    postService.createPost(request);
    return ResponseEntity.noContent().build();
  }
}
