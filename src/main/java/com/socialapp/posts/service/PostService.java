package com.socialapp.posts.service;

import com.socialapp.common.exception.UnauthorizedException;
import com.socialapp.posts.dto.CreatePostRequest;
import com.socialapp.posts.entity.PostEntity;
import com.socialapp.posts.repository.PostRepository;

import lombok.RequiredArgsConstructor;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PostService {

  private final PostRepository postRepository;

  public void createPost(CreatePostRequest request) {
    Integer authorId = getAuthenticatedUserId();

    PostEntity post = new PostEntity();
    post.setContent(request.content());
    post.setVisibility(request.visibility());
    post.setImages(request.images());
    post.setLocationDetails(request.location());
    post.setAuthorId(authorId);

    postRepository.save(post);
  }

  private Integer getAuthenticatedUserId() {
    // TEMPORARY: Return hardcoded user ID 1 for testing since JWT isn't implemented yet.
    return 1;
  }
}
