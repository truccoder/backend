package com.socialapp.posts.service;

import java.util.List;

import org.springframework.stereotype.Service;

import com.socialapp.blocks.service.BlockQueryService;
import com.socialapp.friendships.service.FriendshipService;
import com.socialapp.moderation.enums.ModerationStatus;
import com.socialapp.posts.entity.PostEntity;
import com.socialapp.posts.entity.enums.PostVisibility;

import lombok.RequiredArgsConstructor;

/**
 * The one place that answers "may this viewer see this post?".
 *
 * <p>The app is moving from closed (only friends ever saw anything) to open, which means several
 * new endpoints — the permalink, an author's post list, the discovery feed, search — all serve
 * content written by people the caller may not know. Each of them needs the same three-way rule:
 *
 * <pre>
 *   stranger → PUBLIC only
 *   friend   → PUBLIC + FRIENDS
 *   author   → everything, including PRIVATE and posts still in moderation
 * </pre>
 *
 * <p>Written once and injected rather than repeated per endpoint on purpose: a copy of this rule
 * that drifts does not fail loudly, it leaks. {@code SearchService.isVisibleToViewer} is the
 * older, narrower copy of the same idea and is left alone here only because it works off a
 * pre-fetched friend-id list inside a paged query.
 *
 * <p>Friendship is read through {@link FriendshipService}, never by querying Neo4j from this
 * module: the graph is the friendships module's private storage and posts has no business knowing
 * it exists.
 */
@Service
@RequiredArgsConstructor
public class PostVisibilityService {

  private final FriendshipService friendshipService;
  private final BlockQueryService blockQueryService;

  /**
   * Whether a block stands between the two, in either direction. Exposed so paged endpoints can
   * ask once per request instead of once per post.
   */
  public boolean isBlocked(Integer viewerId, Integer otherUserId) {
    // A guest has no identity to have blocked or been blocked by.
    return viewerId != null && blockQueryService.isBlockedEitherWay(viewerId, otherUserId);
  }

  /**
   * Whether {@code viewerId} may read {@code post}.
   *
   * <p>Moderation is checked before visibility, not after: a post that is PENDING or REJECTED is
   * invisible to everyone except its author no matter how public its visibility says it is,
   * because it has not been cleared for an audience yet.
   */
  public boolean isVisibleTo(PostEntity post, Integer viewerId) {
    if (post.getAuthorId().equals(viewerId)) {
      return true;
    }
    if (!ModerationStatus.APPROVED.equals(post.getModerationStatus())) {
      return false;
    }
    // A guest is a stranger: they see PUBLIC and nothing else. Answered here, before any lookup,
    // rather than letting null flow into areFriends()/isBlockedEitherWay() — a null user id in a
    // Cypher or JPQL parameter is not an error, it is a query that quietly matches nothing, which
    // would give the right answer today by accident and the wrong one after any refactor.
    if (viewerId == null) {
      return PostVisibility.PUBLIC.equals(post.getVisibility());
    }
    // Checked here rather than in each of the read endpoints so that the permalink, the reaction
    // summary and the reactor list all inherit it: a block that only hid posts from the feed would
    // still let the blocked user open any of them by id.
    if (blockQueryService.isBlockedEitherWay(viewerId, post.getAuthorId())) {
      return false;
    }
    return switch (post.getVisibility()) {
      case PUBLIC -> true;
      case FRIENDS -> friendshipService.areFriends(viewerId, post.getAuthorId());
      case PRIVATE -> false;
    };
  }

  /**
   * The visibilities {@code viewerId} is allowed to see from {@code authorId}, for use as an
   * {@code IN (...)} parameter in a paged query.
   *
   * <p>The single-post path uses {@link #isVisibleTo} instead; this exists because filtering a
   * page in Java after reading it from Postgres would make {@code limit} mean "up to N", and the
   * cursor would then skip rows the caller never received.
   */
  public List<PostVisibility> visibleVisibilities(Integer viewerId, Integer authorId) {
    if (authorId.equals(viewerId)) {
      return List.of(PostVisibility.PUBLIC, PostVisibility.FRIENDS, PostVisibility.PRIVATE);
    }
    // Guest: stranger level, and no friendship lookup — see isVisibleTo.
    if (viewerId == null) {
      return List.of(PostVisibility.PUBLIC);
    }
    if (friendshipService.areFriends(viewerId, authorId)) {
      return List.of(PostVisibility.PUBLIC, PostVisibility.FRIENDS);
    }
    return List.of(PostVisibility.PUBLIC);
  }
}
