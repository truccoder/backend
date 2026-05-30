package com.socialapp.posts.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialapp.common.exception.NotFoundException;
import com.socialapp.common.exception.ValidationException;
import com.socialapp.moderation.exception.UserBannedException;
import com.socialapp.moderation.service.UserBanService;
import com.socialapp.posts.dto.UpsertPostReactionRequestDto;
import com.socialapp.posts.entity.PostReactionEntity;
import com.socialapp.posts.entity.PostReactionId;
import com.socialapp.posts.repository.PostReactionRepository;
import com.socialapp.posts.repository.PostRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class PostReactionService {
  private final PostReactionRepository postReactionRepository;
  private final PostRepository postRepository;
  private final UserBanService userBanService;

  @Transactional
  public void upsertReaction(Integer userId, Integer postId, UpsertPostReactionRequestDto request) {
    checkBanStatus(userId);
    if (request.getReactionType() == null) {
      throw new ValidationException("reactionType must not be null");
    }
    verifyPostExists(postId);

    PostReactionId reactionId = new PostReactionId(userId, postId);
    PostReactionEntity reaction =
        postReactionRepository
            .findById(reactionId)
            .orElseGet(() -> new PostReactionEntity(reactionId, null, null));
    reaction.setReactionType(request.getReactionType());

    postReactionRepository.save(reaction);
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

  private void checkBanStatus(Integer userId) {
    // Lưu ý: Cập nhật hàm getActiveBan thành isUserBanned/getBanExpiry theo UserBanService của bạn
    if (userBanService.isUserBanned(userId)) {
      throw new UserBannedException(userBanService.getBanExpiry(userId));
    }
  }
}
