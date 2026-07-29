package com.socialapp.posts.service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.socialapp.common.exception.ForbiddenException;
import com.socialapp.common.exception.NotFoundException;
import com.socialapp.common.exception.ValidationException;
import com.socialapp.moderation.exception.UserBannedException;
import com.socialapp.moderation.service.UserBanService;
import com.socialapp.newsfeed.entity.enums.InteractionType;
import com.socialapp.newsfeed.service.NewsfeedService;
import com.socialapp.notifications.dto.SendNotificationRequest;
import com.socialapp.notifications.entity.enums.NotificationType;
import com.socialapp.notifications.services.NotificationService;
import com.socialapp.posts.dto.CommentResponseDto;
import com.socialapp.posts.dto.CreateCommentRequestDto;
import com.socialapp.posts.dto.UpdateCommentRequestDto;
import com.socialapp.posts.entity.CommentEntity;
import com.socialapp.posts.entity.PostEntity;
import com.socialapp.posts.repository.CommentRepository;
import com.socialapp.posts.repository.PostRepository;
import com.socialapp.security.entity.UserEntity;
import com.socialapp.security.repository.UserRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class CommentService {
  private final CommentRepository commentRepository;
  private final PostRepository postRepository;
  private final UserBanService userBanService;
  private final UserRepository userRepository;
  private final NotificationService notificationService;
  private final NewsfeedService newsfeedService;

  @Transactional(readOnly = true)
  public List<CommentResponseDto> getComments(Integer postId) {
    verifyPostExists(postId);

    List<CommentEntity> comments = commentRepository.findByPostIdOrderByCreatedAtAsc(postId);
    Set<Integer> authorIds =
        comments.stream().map(CommentEntity::getAuthorId).collect(Collectors.toSet());
    Map<Integer, UserEntity> authorsById =
        userRepository.findAllById(authorIds).stream()
            .collect(Collectors.toMap(UserEntity::getId, Function.identity()));

    return comments.stream().map(comment -> toResponseDto(comment, authorsById)).toList();
  }

  private CommentResponseDto toResponseDto(
      CommentEntity comment, Map<Integer, UserEntity> authorsById) {
    UserEntity author = authorsById.get(comment.getAuthorId());
    return CommentResponseDto.builder()
        .id(comment.getId())
        .postId(comment.getPostId())
        .authorId(comment.getAuthorId())
        .authorFullName(author != null ? author.getFullName() : null)
        .authorProfilePictureUrl(author != null ? author.getProfilePictureUrl() : null)
        .content(comment.getContent())
        .parentId(comment.getParentId())
        .createdAt(comment.getCreatedAt())
        .updatedAt(comment.getUpdatedAt())
        .build();
  }

  @Transactional
  public void createComment(Integer authorId, Integer postId, CreateCommentRequestDto request) {
    checkBanStatus(authorId);
    validateContent(request.getContent());
    PostEntity post = findPostOrThrow(postId);

    if (request.getParentId() != null) {
      validateParentComment(request.getParentId(), postId);
    }

    CommentEntity comment = new CommentEntity();
    comment.setPostId(postId);
    comment.setAuthorId(authorId);
    comment.setContent(request.getContent());
    comment.setParentId(request.getParentId());
    commentRepository.save(comment);
    refreshCachedCommentCount(postId);

    notifyPostAuthor(post, authorId);
    trackCommentInteraction(post, authorId);
  }

  /**
   * Feeds the comment into feed affinity — see {@code NewsfeedService#trackInteraction}, which
   * until now had no caller at all. Skips commenting on your own post, as the notification does.
   *
   * <p>Every comment counts, including replies and repeat comments on the same post: unlike a
   * reaction there is no "already engaged" state to compare against, and somebody arguing in a
   * thread all afternoon genuinely is more engaged with that author than somebody who commented
   * once.
   */
  private void trackCommentInteraction(PostEntity post, Integer authorId) {
    if (post.getAuthorId().equals(authorId)) {
      return;
    }
    newsfeedService.trackInteraction(
        authorId, post.getId(), post.getAuthorId(), InteractionType.COMMENT);
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
    refreshCachedCommentCount(postId);
  }

  /**
   * Pushes the new comment total into the feed cache.
   *
   * <p>The feed reads only from Redis and never falls back to Postgres, so a count left alone
   * here is a count the user never sees change — it sat at 0 for every post in the app.
   */
  private void refreshCachedCommentCount(Integer postId) {
    newsfeedService.updateCachedCommentCount(postId, (int) commentRepository.countByPostId(postId));
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

  private void notifyPostAuthor(PostEntity post, Integer commenterId) {
    if (post.getAuthorId().equals(commenterId)) {
      return;
    }
    notificationService.send(
        SendNotificationRequest.builder()
            .recipientId(post.getAuthorId())
            .actorId(commenterId)
            .type(NotificationType.POST_COMMENTED)
            .title("New comment on your post")
            .body(actorName(commenterId) + " commented on your post")
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
