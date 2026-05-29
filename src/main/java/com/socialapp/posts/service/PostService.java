package com.socialapp.posts.service;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
import com.socialapp.posts.entity.PostTagEntity;
import com.socialapp.posts.entity.PostTagId;
import com.socialapp.posts.entity.enums.PostVisibility;
import com.socialapp.posts.repository.PostRepository;
import com.socialapp.security.entity.UserEntity;
import com.socialapp.security.repository.UserRepository;

import io.jsonwebtoken.lang.Strings;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class PostService {

  private final PostRepository postRepository;
  private final UserRepository userRepository;
  private final NewsfeedService newsfeedService;

  private static final int MAX_TAGS = 20;
  private static final Pattern TAG_PLACEHOLDER = Pattern.compile("@\\[(\\d+)]");

  @Transactional
  public void createPost(Integer authorId, CreatePostRequestDto request) {
    UserEntity author = findUserOrThrow(authorId);
    validateTags(request.getVisibility(), request.getTaggedUserIds(), request.getContent());

    PostEntity post = new PostEntity();
    BeanUtils.copyProperties(request, post);
    post.setAuthorId(authorId);
    setTags(post, request.getTaggedUserIds());
    postRepository.save(post);

    try {
      newsfeedService.fanOutPost(buildFeedData(post, author), request.getTaggedUserIds());
    } catch (Exception e) {
      log.warn("Failed to fan-out post {}: {}", post.getId(), e.getMessage());
    }
  }

  @Transactional
  public void updatePost(Integer actorId, Integer postId, UpdatePostRequestDto request) {
    PostEntity post = findPostOrThrow(postId);
    verifyAuthor(actorId, post);
    UserEntity author = findUserOrThrow(actorId);
    validateTags(request.getVisibility(), request.getTaggedUserIds(), request.getContent());

    BeanUtils.copyProperties(request, post);
    post.getTags().clear();
    postRepository.flush();
    setTags(post, request.getTaggedUserIds());
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

    List<Integer> taggedUserIds = extractTaggedUserIds(post);
    postRepository.delete(post);

    try {
      newsfeedService.removePost(postId, actorId, taggedUserIds);
    } catch (Exception e) {
      log.warn("Failed to remove post {} from feeds: {}", postId, e.getMessage());
    }
  }

  private void validateTags(
      PostVisibility visibility, List<Integer> taggedUserIds, String content) {
    if (PostVisibility.PRIVATE.equals(visibility) && !CollectionUtils.isEmpty(taggedUserIds)) {
      throw new ValidationException("Private posts cannot tag other users");
    }

    if (!Strings.hasText(content) && !CollectionUtils.isEmpty(taggedUserIds)) {
      throw new ValidationException("Content is required if want to tag users");
    }

    if (CollectionUtils.isEmpty(taggedUserIds)) {
      return;
    }

    if (taggedUserIds.size() > MAX_TAGS) {
      throw new ValidationException("Cannot tag more than " + MAX_TAGS + " users");
    }

    Set<Integer> uniqueUsers = new HashSet<>(taggedUserIds);
    if (uniqueUsers.size() != taggedUserIds.size()) {
      throw new ValidationException("Duplicate users in tag list");
    }

    Set<Integer> placeholderIndices = new HashSet<>();
    Matcher matcher = TAG_PLACEHOLDER.matcher(content);
    while (matcher.find()) {
      placeholderIndices.add(Integer.parseInt(matcher.group(1)));
    }

    for (int i = 0; i < taggedUserIds.size(); i++) {
      if (!placeholderIndices.contains(i)) {
        throw new ValidationException(
            "Missing placeholder @[" + i + "] in content for tagged user at position " + i);
      }
    }
  }

  private void setTags(PostEntity post, List<Integer> taggedUserIds) {
    for (int i = 0; i < taggedUserIds.size(); i++) {
      PostTagEntity tag = new PostTagEntity(new PostTagId(post.getId(), i), taggedUserIds.get(i));
      post.getTags().add(tag);
    }
  }

  private List<Integer> extractTaggedUserIds(PostEntity post) {
    if (CollectionUtils.isEmpty(post.getTags())) {
      return List.of();
    }
    return post.getTags().stream()
        .sorted(Comparator.comparing(a -> a.getId().getPosition()))
        .map(PostTagEntity::getTaggedUserId)
        .toList();
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
