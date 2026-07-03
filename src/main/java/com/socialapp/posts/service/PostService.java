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
import org.springframework.web.multipart.MultipartFile;

import com.socialapp.bookstore.dto.CreateBookRequestDto;
import com.socialapp.bookstore.service.BookService;
import com.socialapp.common.exception.ForbiddenException;
import com.socialapp.common.exception.NotFoundException;
import com.socialapp.common.exception.ValidationException;
import com.socialapp.moderation.config.ModerationProperties;
import com.socialapp.moderation.dto.ModerationResult;
import com.socialapp.moderation.enums.ModerationStatus;
import com.socialapp.moderation.event.ModerationEventPublisher;
import com.socialapp.moderation.exception.ContentViolationException;
import com.socialapp.moderation.exception.UserBannedException;
import com.socialapp.moderation.rule.ModerationRuleEngine;
import com.socialapp.moderation.service.UserBanService;
import com.socialapp.newsfeed.service.NewsfeedService;
import com.socialapp.posts.dto.CreatePostRequestDto;
import com.socialapp.posts.dto.UpdatePostRequestDto;
import com.socialapp.posts.entity.PostEntity;
import com.socialapp.posts.entity.PostTagEntity;
import com.socialapp.posts.entity.PostTagId;
import com.socialapp.posts.entity.enums.PostType;
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
  private final ModerationRuleEngine moderationRuleEngine;
  private final ModerationEventPublisher moderationEventPublisher;
  private final ModerationProperties moderationProperties;
  private final UserBanService userBanService;
  private final BookService bookService;

  private static final int MAX_TAGS = 20;
  private static final Pattern TAG_PLACEHOLDER = Pattern.compile("@\\[(\\d+)]");

  @Transactional
  public void createPost(Integer authorId, CreatePostRequestDto request) {
    buildAndSavePost(authorId, request);
  }

  /**
   * Creates a post with an attached book in one call, so callers don't need to create a post
   * first just to obtain a postId to pass into book creation.
   */
  @Transactional
  public void createBookPost(
      Integer authorId,
      CreatePostRequestDto request,
      MultipartFile bookFile,
      MultipartFile coverFile) {
    request.setPostType(PostType.BOOK);
    validateBookDetails(request.getBookDetails());

    PostEntity post = buildAndSavePost(authorId, request);

    log.info(
        "[postId={}, authorId={}] createBookPost: post saved, uploading attached book",
        post.getId(),
        authorId);
    bookService.createBookForPost(
        authorId, post.getId(), request.getBookDetails(), bookFile, coverFile);
  }

  private PostEntity buildAndSavePost(Integer authorId, CreatePostRequestDto request) {
    log.info(
        "[authorId={}] createPost started: postType={}, visibility={}",
        authorId,
        request.getPostType(),
        request.getVisibility());

    checkBanStatus(authorId);
    findUserOrThrow(authorId);
    validateTags(request.getVisibility(), request.getTaggedUserIds(), request.getContent());
    log.info("[authorId={}] createPost: ban check and tag validation passed", authorId);

    if (moderationProperties.isEnabled()) {
      ModerationResult ruleResult = moderationRuleEngine.evaluate(authorId, request.getContent());
      log.info(
          "[authorId={}] createPost: rule engine result status={}, violations={}",
          authorId,
          ruleResult.getStatus(),
          ruleResult.getViolations());
      if (ruleResult.isRejected()) {
        throw new ContentViolationException(ruleResult.getViolations());
      }
    }

    if (PostType.EVENT.equals(request.getPostType())) {
      validateEventDetails(request);
    }

    PostEntity post = new PostEntity();
    BeanUtils.copyProperties(request, post);
    post.setAuthorId(authorId);
    if (request.getPostType() == null) {
      post.setPostType(PostType.REGULAR);
    }
    setTags(post, request.getTaggedUserIds());

    if (moderationProperties.isEnabled()) {
      post.setModerationStatus(ModerationStatus.PENDING_MODERATION);
      postRepository.save(post);
      log.info(
          "[postId={}, authorId={}] createPost: saved as PENDING_MODERATION, publishing async"
              + " moderation event",
          post.getId(),
          authorId);
      moderationEventPublisher.publishForReview(post, request.getTaggedUserIds());
    } else {
      post.setModerationStatus(ModerationStatus.APPROVED);
      postRepository.save(post);
      log.info(
          "[postId={}, authorId={}] createPost: moderation disabled, saved as APPROVED and fanning"
              + " out immediately",
          post.getId(),
          authorId);
      newsfeedService.fanOutPost(post.getId());
    }

    return post;
  }

  @Transactional
  public void updatePost(Integer actorId, Integer postId, UpdatePostRequestDto request) {
    checkBanStatus(actorId);
    PostEntity post = findPostOrThrow(postId);
    verifyAuthor(actorId, post);
    findUserOrThrow(actorId);
    validateTags(request.getVisibility(), request.getTaggedUserIds(), request.getContent());

    if (moderationProperties.isEnabled()) {
      ModerationResult ruleResult = moderationRuleEngine.evaluate(actorId, request.getContent());
      if (ruleResult.isRejected()) {
        throw new ContentViolationException(ruleResult.getViolations());
      }
    }

    BeanUtils.copyProperties(request, post);
    post.getTags().clear();
    postRepository.flush();
    setTags(post, request.getTaggedUserIds());

    if (moderationProperties.isEnabled()) {
      post.setModerationStatus(ModerationStatus.PENDING_MODERATION);
      postRepository.save(post);
      newsfeedService.removePost(postId, actorId, extractTaggedUserIds(post));
      moderationEventPublisher.publishForReview(post, request.getTaggedUserIds());
    } else {
      postRepository.save(post);
      newsfeedService.fanOutPost(post.getId());
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
    if (CollectionUtils.isEmpty(taggedUserIds)) {
      return;
    }
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

  private PostEntity findPostOrThrow(Integer postId) {
    return postRepository
        .findById(postId)
        .orElseThrow(() -> new NotFoundException("Post not found with ID: " + postId));
  }

  private UserEntity findUserOrThrow(Integer userId) {
    return userRepository
        .findById(userId)
        .orElseThrow(() -> new NotFoundException("User not found with ID: " + userId));
  }

  private void verifyAuthor(Integer actorId, PostEntity post) {
    if (!post.getAuthorId().equals(actorId)) {
      throw new ForbiddenException("Only the author can modify this post");
    }
  }

  private void checkBanStatus(Integer userId) {
    if (userBanService.isUserBanned(userId)) {
      throw new UserBannedException(userBanService.getBanExpiry(userId));
    }
  }

  private void validateEventDetails(CreatePostRequestDto request) {
    if (request.getEventDetails() == null) {
      throw new ValidationException("Event details are required for event posts");
    }
    if (request.getEventDetails().getEventTitle() == null
        || request.getEventDetails().getEventTitle().isBlank()) {
      throw new ValidationException("Event title is required");
    }
    if (request.getEventDetails().getStartTime() == null) {
      throw new ValidationException("Event start time is required");
    }
    if (request.getEventDetails().getEndTime() == null) {
      throw new ValidationException("Event end time is required");
    }
    if (request.getEventDetails().getEndTime().isBefore(request.getEventDetails().getStartTime())) {
      throw new ValidationException("Event end time must be after start time");
    }
  }

  private void validateBookDetails(CreateBookRequestDto bookDetails) {
    if (bookDetails == null) {
      throw new ValidationException("Book details are required for book posts");
    }
    if (bookDetails.getTitle() == null || bookDetails.getTitle().isBlank()) {
      throw new ValidationException("Book title is required");
    }
  }
}
