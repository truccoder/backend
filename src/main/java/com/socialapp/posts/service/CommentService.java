package com.socialapp.posts.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.socialapp.common.exception.ForbiddenException;
import com.socialapp.common.exception.NotFoundException;
import com.socialapp.common.exception.ValidationException;
import com.socialapp.moderation.exception.UserBannedException;
import com.socialapp.moderation.service.UserBanService;
import com.socialapp.posts.dto.CreateCommentRequestDto;
import com.socialapp.posts.dto.UpdateCommentRequestDto;
import com.socialapp.posts.entity.CommentEntity;
import com.socialapp.posts.repository.CommentRepository;
import com.socialapp.posts.repository.PostRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class CommentService {
  private final CommentRepository commentRepository;
  private final PostRepository postRepository;
  private final UserBanService userBanService;

  @Transactional
  public void createComment(Integer authorId, Integer postId, CreateCommentRequestDto request) {
    checkBanStatus(authorId);
    validateContent(request.getContent());
    verifyPostExists(postId);

    if (request.getParentId() != null) {
      validateParentComment(request.getParentId(), postId);
    }

    CommentEntity comment = new CommentEntity();
    comment.setPostId(postId);
    comment.setAuthorId(authorId);
    comment.setContent(request.getContent());
    comment.setParentId(request.getParentId());
    commentRepository.save(comment);
  }

  @Transactional
  public void updateComment(
      Integer actorId, Integer postId, Integer commentId, UpdateCommentRequestDto request) {
    checkBanStatus(actorId);
    validateContent(request.getContent());
    verifyPostExists(postId);

    CommentEntity comment = findCommentOrThrow(commentId);
    verifyBelongsToPost(comment, postId);
    verifyAuthor(actorId, comment);

    comment.setContent(request.getContent());
    commentRepository.save(comment);
  }

  @Transactional
  public void deleteComment(Integer actorId, Integer postId, Integer commentId) {
    verifyPostExists(postId);

    CommentEntity comment = findCommentOrThrow(commentId);
    verifyBelongsToPost(comment, postId);
    verifyAuthor(actorId, comment);

    commentRepository.delete(comment);
  }

  private void validateParentComment(Integer parentId, Integer postId) {
    CommentEntity parent = findCommentOrThrow(parentId);
    verifyBelongsToPost(parent, postId);

    if (parent.getParentId() != null) {
      throw new ValidationException("Replies can only be made to top-level comments");
    }
  }

  private void validateContent(String content) {
    if (!StringUtils.hasText(content)) {
      throw new ValidationException("Comment content must not be blank");
    }
  }

  private CommentEntity findCommentOrThrow(Integer commentId) {
    return commentRepository
        .findById(commentId)
        .orElseThrow(() -> new NotFoundException("Comment not found with ID: " + commentId));
  }

  private void verifyBelongsToPost(CommentEntity comment, Integer postId) {
    if (!comment.getPostId().equals(postId)) {
      throw new NotFoundException("Comment not found with ID: " + comment.getId());
    }
  }

  private void verifyAuthor(Integer actorId, CommentEntity comment) {
    if (!comment.getAuthorId().equals(actorId)) {
      throw new ForbiddenException("Only the author can modify this comment");
    }
  }

  private void verifyPostExists(Integer postId) {
    if (!postRepository.existsById(postId)) {
      throw new NotFoundException("Post not found with ID: " + postId);
    }
  }

  private void checkBanStatus(Integer userId) {
    if (userBanService.isUserBanned(userId)) {
      throw new UserBannedException(userBanService.getBanExpiry(userId));
    }
  }
}
