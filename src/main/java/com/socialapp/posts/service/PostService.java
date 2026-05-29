package com.socialapp.posts.service;

import java.util.List;

import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import com.socialapp.common.exception.ForbiddenException;
import com.socialapp.common.exception.NotFoundException;
import com.socialapp.common.exception.ValidationException;
import com.socialapp.newsfeed.dto.FeedPostDataDto;
import com.socialapp.newsfeed.service.NewsfeedService;
import com.socialapp.posts.dto.CreatePostRequestDto;
import com.socialapp.posts.dto.UpdatePostRequestDto;
import com.socialapp.posts.entity.PostEntity;
import com.socialapp.posts.entity.enums.PostVisibility;
import com.socialapp.posts.repository.PostRepository;
import com.socialapp.security.entity.UserEntity;
import com.socialapp.security.repository.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class PostService {

  private final PostRepository postRepository;
  private final UserRepository userRepository;
  private final NewsfeedService newsfeedService;

  @Transactional
  public void createPost(Integer authorId, CreatePostRequestDto request) {
    UserEntity author = findUserOrThrow(authorId);
    validateVisibilityAndTags(request.getVisibility(), request.getTaggedUserIds());

    PostEntity post = new PostEntity();
    BeanUtils.copyProperties(request, post);
    post.setAuthorId(authorId);
    post = postRepository.save(post);

    try {
      newsfeedService.fanOutPost(buildFeedData(post, author), post.getTaggedUserIds());
    } catch (Exception e) {
      log.warn("Failed to fan-out post {}: {}", post.getId(), e.getMessage());
    }
  }

  @Transactional
  public void updatePost(Integer actorId, Integer postId, UpdatePostRequestDto request) {
    PostEntity post = findPostOrThrow(postId);
    verifyAuthor(actorId, post);
    UserEntity author = findUserOrThrow(actorId);
    validateVisibilityAndTags(post.getVisibility(), post.getTaggedUserIds());

    BeanUtils.copyProperties(request, post);
    postRepository.save(post);

    try {
      newsfeedService.updatePostCache(buildFeedData(post, author));
    } catch (Exception e) {
      log.warn("Failed to update feed cache for post {}: {}", post.getId(), e.getMessage());
    }
  }

  @Transactional
  public void deletePost(Integer actorId, Integer postId) {
    PostEntity post = findPostOrThrow(postId);
    verifyAuthor(actorId, post);
    postRepository.delete(post);

    try {
      newsfeedService.removePost(postId, actorId, post.getTaggedUserIds());
    } catch (Exception e) {
      log.warn("Failed to remove post {} from feeds: {}", postId, e.getMessage());
    }
  }

  private void validateVisibilityAndTags(PostVisibility visibility, List<Integer> taggedUserIds) {
    if (PostVisibility.PRIVATE.equals(visibility) && !CollectionUtils.isEmpty(taggedUserIds)) {
      throw new ValidationException("Private posts cannot tag other users");
    }
  }

  private FeedPostDataDto buildFeedData(PostEntity post, UserEntity author) {
    return FeedPostDataDto.builder()
        .postId(post.getId())
        .authorId(post.getAuthorId())
        .authorFullName(author.getFullName())
        .authorProfilePictureUrl(author.getProfilePictureUrl())
        .content(post.getContent())
        .visibility(post.getVisibility())
        .createdAt(post.getCreatedAt())
        .build();
  }

  private PostEntity findPostOrThrow(Integer postId) {
    return postRepository
        .findById(postId)
        .orElseThrow(() -> new NotFoundException("Post not found with ID: " + postId));
  }

  private UserEntity findUserOrThrow(Integer userId) {
    return userRepository
        .findById(userId.longValue())
        .orElseThrow(() -> new NotFoundException("User not found with ID: " + userId));
  }

  private void verifyAuthor(Integer actorId, PostEntity post) {
    if (!post.getAuthorId().equals(actorId)) {
      throw new ForbiddenException("Only the author can modify this post");
    }
  }
}
