package com.socialapp.posts.service;

import java.util.Collection;
import java.util.List;
import java.util.Set;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialapp.blocks.service.BlockQueryService;
import com.socialapp.common.exception.NotFoundException;
import com.socialapp.hashtags.HashtagNormalizer;
import com.socialapp.moderation.enums.ModerationStatus;
import com.socialapp.newsfeed.dto.FeedPostDataDto;
import com.socialapp.newsfeed.service.FeedPostDataMapper;
import com.socialapp.posts.dto.PostPageResponseDto;
import com.socialapp.posts.entity.PostEntity;
import com.socialapp.posts.entity.enums.PostVisibility;
import com.socialapp.posts.repository.PostRepository;
import com.socialapp.security.repository.UserRepository;

import lombok.RequiredArgsConstructor;

/**
 * Reading posts out of Postgres: one post, one author's posts, everybody's public posts.
 *
 * <p>Separate from {@link PostService}, which is entirely write-side (create, edit, delete,
 * moderation hand-off) and already large enough that PMD calls it a God class. Splitting reads out
 * rather than appending to it keeps the visibility rules of this file in one readable place.
 *
 * <p>Every method here goes through {@link PostVisibilityService}. Nothing in this class decides
 * for itself who may see what.
 */
@Service
@RequiredArgsConstructor
public class PostQueryService {

  private final PostRepository postRepository;
  private final UserRepository userRepository;
  private final PostVisibilityService postVisibilityService;
  private final BlockQueryService blockQueryService;
  private final FeedPostDataMapper feedPostDataMapper;

  /**
   * Moderation statuses an author sees on their own posts — all of them. Someone still waiting on
   * moderation has to be able to see that the post exists, or it looks like it was silently lost.
   */
  private static final List<ModerationStatus> ALL_STATUSES = List.of(ModerationStatus.values());

  private static final List<ModerationStatus> APPROVED_ONLY = List.of(ModerationStatus.APPROVED);

  /** Stands in for "nobody" in a NOT IN list — ids come from a sequence and are always positive. */
  private static final int NO_SUCH_USER_ID = -1;

  /**
   * One post, by id — the permalink.
   *
   * <p>A post the caller may not see comes back as <b>404, not 403</b>. 403 would confirm that a
   * post with that id exists and that somebody they cannot see wrote it, which is exactly the fact
   * being withheld; ids are sequential, so a client could otherwise walk the range and map out who
   * posts and how often.
   */
  @Transactional(readOnly = true)
  public FeedPostDataDto getPost(Integer viewerId, Integer postId) {
    PostEntity post =
        postRepository
            .findById(postId)
            .orElseThrow(() -> new NotFoundException("Post not found with ID: " + postId));

    if (!postVisibilityService.isVisibleTo(post, viewerId)) {
      throw new NotFoundException("Post not found with ID: " + postId);
    }

    FeedPostDataDto data = feedPostDataMapper.toFeedPostData(post);
    feedPostDataMapper.signBookCover(data);
    return data;
  }

  /** One page of {@code authorId}'s posts, filtered to what {@code viewerId} may see. */
  @Transactional(readOnly = true)
  public PostPageResponseDto getPostsByAuthor(
      Integer viewerId, Integer authorId, Integer cursor, int limit) {
    if (!userRepository.existsById(authorId)) {
      throw new NotFoundException("User not found with ID: " + authorId);
    }

    // An empty page rather than a 404: the profile itself is still reachable (the block hides
    // content, it does not erase the person), and answering "no posts" is also the honest answer
    // to "show me what I am allowed to read from them", which is nothing.
    if (!authorId.equals(viewerId) && postVisibilityService.isBlocked(viewerId, authorId)) {
      return new PostPageResponseDto(List.of(), null, false);
    }

    List<PostVisibility> visibilities =
        postVisibilityService.visibleVisibilities(viewerId, authorId);
    List<ModerationStatus> statuses = authorId.equals(viewerId) ? ALL_STATUSES : APPROVED_ONLY;

    // limit + 1: the extra row is how hasMore is answered without a second COUNT query, and it is
    // dropped before the page is returned.
    List<PostEntity> rows =
        postRepository.findByAuthorForViewer(
            authorId, visibilities, statuses, cursor, PageRequest.of(0, limit + 1));

    return toPage(rows, limit);
  }

  /**
   * One page of the discovery feed: everyone's PUBLIC, approved posts, newest first, minus anyone
   * the viewer has blocked or been blocked by.
   *
   * <p>The block set is applied in the query rather than to the page after it is read, so {@code
   * limit} keeps meaning "this many posts" and the cursor cannot skip past rows the caller never
   * received.
   *
   * <p>{@code hashtag} is optional — it is what makes a hashtag badge on a post clickable (B31).
   * It is folded through {@link HashtagNormalizer} so the caller can pass {@code #ReactHooks} or
   * the bare stored name and reach the same rows; a value that folds to nothing is treated as no
   * filter.
   */
  @Transactional(readOnly = true)
  public PostPageResponseDto getPublicFeed(
      Integer viewerId, Integer cursor, String hashtag, int limit) {
    return toPage(
        postRepository.findPublicFeed(
            excludedAuthorIds(viewerId),
            cursor,
            HashtagNormalizer.normalize(hashtag),
            PageRequest.of(0, limit + 1)),
        limit);
  }

  /**
   * The viewer's block set, never empty.
   *
   * <p>{@code NOT IN ()} is a syntax error, and most users have blocked nobody, so the empty case
   * is the common one rather than an edge case. The sentinel is an id no user can ever have, which
   * makes the predicate a no-op instead of a special-cased second query.
   */
  private Collection<Integer> excludedAuthorIds(Integer viewerId) {
    Set<Integer> blocked = blockQueryService.blockedPairIds(viewerId);
    return blocked.isEmpty() ? List.of(NO_SUCH_USER_ID) : blocked;
  }

  private PostPageResponseDto toPage(List<PostEntity> rows, int limit) {
    boolean hasMore = rows.size() > limit;
    List<PostEntity> pageRows = hasMore ? rows.subList(0, limit) : rows;

    // The cursor is taken from the last row read, not from the last item mapped:
    // toFeedPostDataPage drops posts whose author row is gone, and a cursor taken after that would
    // rewind to an earlier post and serve the same page forever.
    Integer nextCursor = hasMore ? pageRows.get(pageRows.size() - 1).getId() : null;

    return new PostPageResponseDto(
        feedPostDataMapper.toFeedPostDataPage(pageRows), nextCursor, hasMore);
  }
}
