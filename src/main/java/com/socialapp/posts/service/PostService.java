package com.socialapp.posts.service;

import com.socialapp.posts.dto.CreatePostRequest;
import com.socialapp.posts.repository.PostRepository;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PostService {

  private final PostRepository postRepository;

  public void createPost(CreatePostRequest request) {
    Integer authorId = getAuthenticatedUserId();

    Post post = new Post();
    post.setContent(request.content());
    post.setGooglePlaceId(request.googlePlaceId());
    post.setLocationType(request.locationType());
    post.setLocationDetails(request.location());
    post.setAuthorId(authorId);

    postRepository.save(post);
  }

  private Integer getAuthenticatedUserId() {
    // TEMPORARY: Return hardcoded user ID 1 for testing since JWT isn't implemented yet.
    return 1;
  }
}
