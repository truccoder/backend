package com.socialapp.posts.service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.socialapp.blocks.service.BlockQueryService;
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
import com.socialapp.posts.entity.enums.ReactionType;
import com.socialapp.posts.repository.CommentReactionRepository;
import com.socialapp.posts.repository.CommentRepository;
import com.socialapp.posts.repository.PostRepository;
import com.socialapp.security.entity.UserEntity;
import com.socialapp.security.repository.UserRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class CommentService {
  private final CommentRepository commentRepository;
  private final CommentReactionRepository commentReactionRepository;
  private final PostRepository postRepository;
  private final UserBanService userBanService;
  private final UserRepository userRepository;
  private final NotificationService notificationService;
  private final NewsfeedService newsfeedService;
  private final BlockQueryService blockQueryService;
  private final PostVisibilityService postVisibilityService;

  /**
   * The comments on a post, as {@code viewerId} is allowed to see them.
   *
   * <p>Takes a viewer — it used to take only the post id — because comments by a blocked user have
   * to be hidden. A comment thread is the one place where somebody who has been blocked can still
   * talk directly at the person who blocked them, so leaving this unfiltered would undo much of
   * what the block is for.
   *
   * <p>Hidden, not deleted, and hidden only for this reader: the comment stays visible to everyone
   * else, including its author, who is not told. That asymmetry is deliberate — a block that
   * announced itself would invite retaliation.
   */
  @Transactional(readOnly = true)
  public List<CommentResponseDto> getComments(Integer viewerId, Integer postId) {
    requireVisiblePost(viewerId, postId);

    Set<Integer> blockedIds = blockQueryService.blockedPairIds(viewerId);
    List<CommentEntity> comments =
        commentRepository.findByPostIdOrderByCreatedAtAsc(postId).stream()
            .filter(comment -> !blockedIds.contains(comment.getAuthorId()))
            .toList();
    Set<Integer> authorIds =
        comments.stream().map(CommentEntity::getAuthorId).collect(Collectors.toSet());
    Map<Integer, UserEntity> authorsById =
        userRepository.findAllById(authorIds).stream()
            .collect(Collectors.toMap(UserEntity::getId, Function.identity()));

    // Two batch queries for the whole thread, not one pair per comment. A thread has no upper
    // bound, so counting inside the map below would be an N+1 on exactly the endpoint a busy post
    // hits hardest.
    List<Integer> commentIds = comments.stream().map(CommentEntity::getId).toList();
    Map<Integer, Long> likeCounts = commentReactionRepository.countByCommentIds(commentIds);
    Map<Integer, ReactionType> myReactions =
        commentReactionRepository.findMyReactions(viewerId, commentIds);

    return comments.stream()
        .map(comment -> toResponseDto(comment, authorsById, likeCounts, myReactions))
        .toList();
  }

  private CommentResponseDto toResponseDto(
      CommentEntity comment,
      Map<Integer, UserEntity> authorsById,
      Map<Integer, Long> likeCounts,
      Map<Integer, ReactionType> myReactions) {
    UserEntity author = authorsById.get(comment.getAuthorId());
    return CommentResponseDto.builder()
        .id(comment.getId())
        .postId(comment.getPostId())
        .authorId(comment.getAuthorId())
        .authorUsername(author != null ? author.getUsername() : null)
        .authorFullName(author != null ? author.getFullName() : null)
        .authorProfilePictureUrl(author != null ? author.getProfilePictureUrl() : null)
        .content(comment.getContent())
        .parentId(comment.getParentId())
        .createdAt(comment.getCreatedAt())
        .updatedAt(comment.getUpdatedAt())
        .likeCount(likeCounts.getOrDefault(comment.getId(), 0L).intValue())
        .myReaction(myReactions.get(comment.getId()))
        .build();
  }

  @Transactional
  public void createComment(Integer authorId, Integer postId, CreateCommentRequestDto request) {
    checkBanStatus(authorId);
    validateContent(request.getContent());
    PostEntity post = requireVisiblePost(authorId, postId);

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

  /**
   * The post, but only if {@code viewerId} is allowed to read it.
   *
   * <p>Existence used to be the only check here, which left a comment thread readable — and
   * writable — on a PRIVATE post by anyone who guessed its id. That is a worse leak than it first
   * looks: the thread carries comment bodies plus every commenter's name and avatar, and posting
   * into it fires a notification at an author who never shared the post with that reader.
   *
   * <p>404, not 403, matching {@code PostReactionService#requireVisiblePost}: a post you may not
   * read must not be distinguishable from one that does not exist.
   */
  private PostEntity requireVisiblePost(Integer viewerId, Integer postId) {
    PostEntity post = findPostOrThrow(postId);
    if (!postVisibilityService.isVisibleTo(post, viewerId)) {
      throw new NotFoundException("Post not found with ID: " + postId);
    }
    return post;
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
