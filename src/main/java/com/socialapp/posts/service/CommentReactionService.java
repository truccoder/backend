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
import com.socialapp.posts.entity.CommentEntity;
import com.socialapp.posts.entity.CommentReactionEntity;
import com.socialapp.posts.entity.CommentReactionId;
import com.socialapp.posts.entity.PostEntity;
import com.socialapp.posts.repository.CommentReactionRepository;
import com.socialapp.posts.repository.CommentRepository;
import com.socialapp.posts.repository.PostRepository;
import com.socialapp.security.entity.UserEntity;
import com.socialapp.security.repository.UserRepository;

import lombok.RequiredArgsConstructor;

/**
 * Reactions on comments — the mirror of {@link PostReactionService}, one level down.
 *
 * <p>Deliberately does NOT award reputation. A post reaction mints {@code REACTION_RECEIVED} for
 * the author, and copying that here would quietly change what an Elite Score measures: comments
 * are far cheaper to produce than posts, so the same award on both would make a thread of replies
 * worth more than the article it hangs under. Making comments count towards reputation is a
 * product decision with its own weighting, not a side effect of adding a button.
 */
@Service
@RequiredArgsConstructor
public class CommentReactionService {
  private final CommentReactionRepository commentReactionRepository;
  private final CommentRepository commentRepository;
  private final PostRepository postRepository;
  private final PostVisibilityService postVisibilityService;
  private final UserBanService userBanService;
  private final NotificationService notificationService;
  private final UserRepository userRepository;

  @Transactional
  public void upsertReaction(
      Integer userId, Integer postId, Integer commentId, UpsertPostReactionRequestDto request) {
    checkBanStatus(userId);
    CommentEntity comment = requireVisibleComment(userId, postId, commentId);

    CommentReactionId reactionId = new CommentReactionId(userId, commentId);
    boolean isNewReaction = !commentReactionRepository.existsById(reactionId);
    CommentReactionEntity reaction =
        commentReactionRepository
            .findById(reactionId)
            .orElseGet(() -> new CommentReactionEntity(reactionId, null, null));
    reaction.setReactionType(request.getReactionType());
    commentReactionRepository.save(reaction);

    // Guarded by isNewReaction exactly as the post path is: swapping LIKE for INSIGHT on a comment
    // already reacted to is the same single act, and notifying again would let one reader ring
    // somebody's bell as often as they liked.
    if (isNewReaction) {
      notifyCommentAuthor(comment, userId);
    }
  }

  @Transactional
  public void removeReaction(Integer userId, Integer postId, Integer commentId) {
    requireVisibleComment(userId, postId, commentId);

    CommentReactionId reactionId = new CommentReactionId(userId, commentId);
    if (!commentReactionRepository.existsById(reactionId)) {
      throw new NotFoundException("Reaction not found for this comment");
    }
    commentReactionRepository.deleteById(reactionId);
  }

  /**
   * The comment, but only if {@code viewerId} may read the post it hangs under.
   *
   * <p>Both checks matter and for different reasons. The visibility rule is the same one {@code
   * CommentService#requireVisiblePost} applies — without it, reacting to a comment on a PRIVATE
   * post would confirm the post exists and fire a notification at somebody who never shared it.
   * The belongs-to-post check stops {@code /posts/1/comments/999/reactions} from reaching a
   * comment on a post the caller cannot see by borrowing the id of one they can.
   *
   * <p>404 in both cases, matching every other read path: a thing you may not see must not be
   * distinguishable from one that does not exist.
   */
  private CommentEntity requireVisibleComment(Integer viewerId, Integer postId, Integer commentId) {
    PostEntity post =
        postRepository
            .findById(postId)
            .orElseThrow(() -> new NotFoundException("Post not found with ID: " + postId));
    if (!postVisibilityService.isVisibleTo(post, viewerId)) {
      throw new NotFoundException("Post not found with ID: " + postId);
    }

    CommentEntity comment =
        commentRepository
            .findById(commentId)
            .orElseThrow(() -> new NotFoundException("Comment not found with ID: " + commentId));
    if (!comment.getPostId().equals(postId)) {
      throw new NotFoundException("Comment not found with ID: " + commentId);
    }
    return comment;
  }

  private void checkBanStatus(Integer userId) {
    if (userBanService.isUserBanned(userId)) {
      throw new UserBannedException(userBanService.getBanExpiry(userId));
    }
  }

  /**
   * Tells the comment's author, unless they reacted to their own comment.
   *
   * <p>{@code referenceId} is the comment id and {@code referenceType} is "COMMENT": the client
   * has to open the thread at the reply that was reacted to, and a post id would only get it to
   * the top of a page that may hold hundreds of comments.
   */
  private void notifyCommentAuthor(CommentEntity comment, Integer reactorId) {
    if (comment.getAuthorId().equals(reactorId)) {
      return;
    }
    notificationService.send(
        SendNotificationRequest.builder()
            .recipientId(comment.getAuthorId())
            .actorId(reactorId)
            .type(NotificationType.COMMENT_LIKED)
            .title("New reaction on your comment")
            .body(actorName(reactorId) + " reacted to your comment")
            .referenceId(comment.getId())
            .referenceType("COMMENT")
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
