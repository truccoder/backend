package com.socialapp.posts.service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialapp.common.exception.NotFoundException;
import com.socialapp.moderation.exception.UserBannedException;
import com.socialapp.moderation.service.UserBanService;
import com.socialapp.newsfeed.entity.enums.InteractionType;
import com.socialapp.newsfeed.service.NewsfeedService;
import com.socialapp.notifications.dto.SendNotificationRequest;
import com.socialapp.notifications.entity.enums.NotificationType;
import com.socialapp.notifications.services.NotificationService;
import com.socialapp.posts.dto.MyReactionResponseDto;
import com.socialapp.posts.dto.ReactorPageResponseDto;
import com.socialapp.posts.dto.UpsertPostReactionRequestDto;
import com.socialapp.posts.entity.PostEntity;
import com.socialapp.posts.entity.PostReactionEntity;
import com.socialapp.posts.entity.PostReactionId;
import com.socialapp.posts.entity.enums.ReactionType;
import com.socialapp.posts.repository.PostReactionRepository;
import com.socialapp.posts.repository.PostRepository;
import com.socialapp.reputation.entity.enums.RepSourceType;
import com.socialapp.reputation.event.ReputationEventPublisher;
import com.socialapp.security.dto.PublicUserResponse;
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
  private final ReputationEventPublisher reputationEventPublisher;
  private final NewsfeedService newsfeedService;
  private final PostVisibilityService postVisibilityService;

  @Transactional(readOnly = true)
  public MyReactionResponseDto getMyReaction(Integer userId, Integer postId) {
    requireVisiblePost(userId, postId);
    // A missing reaction is a normal state (not an error), so it maps to a null
    // reactionType instead of a 404 — the frontend polls this on every post card.
    return postReactionRepository
        .findById(new PostReactionId(userId, postId))
        .map(reaction -> new MyReactionResponseDto(reaction.getReactionType()))
        .orElseGet(() -> new MyReactionResponseDto(null));
  }

  /**
   * How many reactions of each type a post has, e.g. {@code {"LIKE": 4, "LOVE": 2}}.
   *
   * <p>Types nobody chose are left out rather than reported as zero: the client renders one chip
   * per entry, and a zero chip is noise it would have to filter anyway.
   */
  @Transactional(readOnly = true)
  public Map<ReactionType, Long> getReactionSummary(Integer viewerId, Integer postId) {
    requireVisiblePost(viewerId, postId);
    return postReactionRepository.countByType(postId);
  }

  /**
   * Who reacted, one page at a time, optionally narrowed to one reaction type.
   *
   * <p>Returns {@code PublicUserResponse} — never {@code UserResponse}, which would hand the
   * reader the email address of everyone who ever tapped like on a public post.
   */
  @Transactional(readOnly = true)
  public ReactorPageResponseDto getReactors(
      Integer viewerId, Integer postId, ReactionType type, Integer cursor, int limit) {
    requireVisiblePost(viewerId, postId);

    // limit + 1 to detect a further page without a second count query over the same rows.
    List<Integer> reactorIds =
        postReactionRepository.findReactorIds(postId, type, cursor, PageRequest.of(0, limit + 1));

    boolean hasMore = reactorIds.size() > limit;
    List<Integer> pageIds = hasMore ? reactorIds.subList(0, limit) : reactorIds;
    Integer nextCursor = hasMore ? pageIds.get(pageIds.size() - 1) : null;

    Map<Integer, UserEntity> usersById =
        userRepository.findAllById(pageIds).stream()
            .collect(Collectors.toMap(UserEntity::getId, Function.identity()));

    // Ordered by the id list, not by whatever order findAllById returned; a reactor whose user row
    // is gone is skipped rather than rendered as a blank row.
    List<PublicUserResponse> reactors =
        pageIds.stream()
            .map(usersById::get)
            .filter(Objects::nonNull)
            .map(PublicUserResponse::from)
            .toList();

    long totalCount =
        type == null
            ? postReactionRepository.countByIdPostId(postId)
            : postReactionRepository.countByIdPostIdAndReactionType(postId, type);

    return new ReactorPageResponseDto(reactors, nextCursor, hasMore, totalCount);
  }

  /**
   * Loads a post only if {@code viewerId} is allowed to read it, and reports it as missing rather
   * than forbidden otherwise — see {@code PostQueryService#getPost} for why 404 and not 403.
   *
   * <p>Both read endpoints need this: without it, a stranger who guessed a post id could count and
   * enumerate the reactions on a FRIENDS-only post, which is the post's audience list in all but
   * name.
   */
  private PostEntity requireVisiblePost(Integer viewerId, Integer postId) {
    PostEntity post = findPostOrThrow(postId);
    if (!postVisibilityService.isVisibleTo(post, viewerId)) {
      throw new NotFoundException("Post not found with ID: " + postId);
    }
    return post;
  }

  @Transactional
  public void upsertReaction(Integer userId, Integer postId, UpsertPostReactionRequestDto request) {
    checkBanStatus(userId);
    // The read paths above have always called this; the write paths did not, so a stranger could
    // react to a PRIVATE post — confirming it exists, notifying its author, and minting
    // reputation for them — on a post that was never shared with them.
    PostEntity post = requireVisiblePost(userId, postId);

    PostReactionId reactionId = new PostReactionId(userId, postId);
    boolean isNewReaction = !postReactionRepository.existsById(reactionId);
    PostReactionEntity reaction =
        postReactionRepository
            .findById(reactionId)
            .orElseGet(() -> new PostReactionEntity(reactionId, null, null));
    reaction.setReactionType(request.getReactionType());

    postReactionRepository.save(reaction);
    refreshCachedReactions(postId);

    // Guarded by isNewReaction along with the notification and the rep award: swapping LIKE for
    // LOVE on a post you already reacted to is the same single act of engagement, not a second one.
    if (isNewReaction) {
      notifyPostAuthor(post, userId);
      awardReactionRep(post, userId);
      trackReactionInteraction(post, userId);
    }
  }

  @Transactional
  public void removeReaction(Integer userId, Integer postId) {
    PostEntity post = requireVisiblePost(userId, postId);
    PostReactionId reactionId = new PostReactionId(userId, postId);
    if (!postReactionRepository.existsById(reactionId)) {
      throw new NotFoundException("Reaction not found for this post");
    }
    postReactionRepository.deleteById(reactionId);
    refreshCachedReactions(postId);
    revokeReactionRep(post, userId);
  }

  /**
   * Pushes the new reaction total and its per-type breakdown into the feed cache.
   *
   * <p>The feed reads only from Redis and never falls back to Postgres, so without this the
   * counter stays at whatever it was when the post was fanned out — which is why it read 0
   * forever. {@code updatePostCache} existed for this and simply had no caller.
   *
   * <p>Both numbers go in one call. The feed now carries the breakdown beside the total, and a
   * refresh that updated only the total would leave the chips describing the previous state — a
   * disagreement the reader can see, on the very card they just tapped.
   */
  private void refreshCachedReactions(Integer postId) {
    newsfeedService.updateCachedReactions(
        postId,
        (int) postReactionRepository.countByIdPostId(postId),
        postReactionRepository.countByType(postId));
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

  private void awardReactionRep(PostEntity post, Integer reactorId) {
    if (post.getAuthorId().equals(reactorId)) {
      return;
    }
    reputationEventPublisher.award(
        post.getAuthorId(),
        RepSourceType.REACTION_RECEIVED,
        reactionSourceId(post.getId(), reactorId));
  }

  private void revokeReactionRep(PostEntity post, Integer reactorId) {
    if (post.getAuthorId().equals(reactorId)) {
      return;
    }
    reputationEventPublisher.revoke(
        post.getAuthorId(),
        RepSourceType.REACTION_RECEIVED,
        reactionSourceId(post.getId(), reactorId));
  }

  private String reactionSourceId(Integer postId, Integer reactorId) {
    return postId + ":" + reactorId;
  }

  private void checkBanStatus(Integer userId) {
    if (userBanService.isUserBanned(userId)) {
      throw new UserBannedException(userBanService.getBanExpiry(userId));
    }
  }

  /**
   * Feeds the reaction into feed affinity — see {@code NewsfeedService#trackInteraction}, which
   * until now had no caller at all. Skips self-reaction for the same reason the notification and
   * the reputation award do.
   */
  private void trackReactionInteraction(PostEntity post, Integer reactorId) {
    if (post.getAuthorId().equals(reactorId)) {
      return;
    }
    newsfeedService.trackInteraction(
        reactorId, post.getId(), post.getAuthorId(), InteractionType.LIKE);
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
