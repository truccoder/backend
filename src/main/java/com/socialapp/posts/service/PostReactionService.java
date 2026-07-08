package com.socialapp.posts.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialapp.common.exception.NotFoundException;
import com.socialapp.moderation.exception.UserBannedException;
import com.socialapp.moderation.service.UserBanService;
import com.socialapp.notifications.dto.SendNotificationRequest;
import com.socialapp.notifications.entity.enums.NotificationType;
import com.socialapp.notifications.services.NotificationService;
import com.socialapp.posts.dto.UpsertPostReactionRequestDto;
import com.socialapp.posts.entity.PostEntity;
import com.socialapp.posts.entity.PostReactionEntity;
import com.socialapp.posts.entity.PostReactionId;
import com.socialapp.posts.repository.PostReactionRepository;
import com.socialapp.posts.repository.PostRepository;
import com.socialapp.security.entity.UserEntity;
import com.socialapp.security.repository.UserRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class PostReactionService {
  private final PostReactionRepository postReactionRepository;
  private final PostRepository postRepository;
  private final UserBanService userBanService;
  private final UserRepository userRepository;
  private final NotificationService notificationService;

  @Transactional
  public void upsertReaction(Integer userId, Integer postId, UpsertPostReactionRequestDto request) {
    checkBanStatus(userId);
    PostEntity post = findPostOrThrow(postId);

    PostReactionId reactionId = new PostReactionId(userId, postId);
    boolean isNewReaction = !postReactionRepository.existsById(reactionId);
    PostReactionEntity reaction =
        postReactionRepository
            .findById(reactionId)
            .orElseGet(() -> new PostReactionEntity(reactionId, null, null));
    reaction.setReactionType(request.getReactionType());

    postReactionRepository.save(reaction);

    if (isNewReaction) {
      notifyPostAuthor(post, userId);
    }
  }

  @Transactional
  public void removeReaction(Integer userId, Integer postId) {
    verifyPostExists(postId);
    PostReactionId reactionId = new PostReactionId(userId, postId);
    if (!postReactionRepository.existsById(reactionId)) {
      throw new NotFoundException("Reaction not found for this post");
    }
    postReactionRepository.deleteById(reactionId);
  }

  private void verifyPostExists(Integer postId) {
    if (!postRepository.existsById(postId)) {
      throw new NotFoundException("Post not found with ID: " + postId);
    }
  }

  private PostEntity findPostOrThrow(Integer postId) {
    return postRepository
        .findById(postId)
        .orElseThrow(() -> new NotFoundException("Post not found with ID: " + postId));
  }

  private void checkBanStatus(Integer userId) {
    if (userBanService.isUserBanned(userId)) {
      throw new UserBannedException(userBanService.getBanExpiry(userId));
    }
  }

  private void notifyPostAuthor(PostEntity post, Integer reactorId) {
    if (post.getAuthorId().equals(reactorId)) {
      return;
    }
    notificationService.send(
        SendNotificationRequest.builder()
            .recipientId(post.getAuthorId())
            .actorId(reactorId)
            .type(NotificationType.POST_LIKED)
            .title("New reaction on your post")
            .body(actorName(reactorId) + " reacted to your post")
            .referenceId(post.getId())
            .referenceType("POST")
            .build());
  }

  private String actorName(Integer userId) {
    return userRepository
        .findById(userId)
        .map(UserEntity::getFullName)
        .filter(name -> name != null && !name.isBlank())
        .orElse("Someone");
  }
}
