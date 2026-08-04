package com.socialapp.newsfeed.service;

import static com.socialapp.newsfeed.service.PostScoringService.FEED_KEY_PREFIX;
import static com.socialapp.newsfeed.service.PostScoringService.POST_CACHE_KEY_PREFIX;

import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socialapp.blocks.service.BlockQueryService;
import com.socialapp.common.exception.NotFoundException;
import com.socialapp.newsfeed.dto.FeedPostDataDto;
import com.socialapp.newsfeed.dto.FeedResponseDto;
import com.socialapp.newsfeed.entity.UserInteractionEntity;
import com.socialapp.newsfeed.entity.enums.InteractionType;
import com.socialapp.newsfeed.repository.UserInteractionRepository;
import com.socialapp.notifications.dto.SendNotificationRequest;
import com.socialapp.notifications.entity.enums.NotificationType;
import com.socialapp.notifications.services.NotificationService;
import com.socialapp.posts.entity.PostEntity;
import com.socialapp.posts.entity.QnaDetails;
import com.socialapp.posts.entity.enums.PostVisibility;
import com.socialapp.posts.repository.PostRepository;
import com.socialapp.search.service.FriendshipQueryService;
import com.socialapp.security.entity.UserEntity;
import com.socialapp.security.repository.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class NewsfeedService {
  private final StringRedisTemplate redisTemplate;
  private final ObjectMapper objectMapper;
  private final FriendshipQueryService friendshipQueryService;
  private final UserInteractionRepository userInteractionRepository;
  private final PostRepository postRepository;
  private final UserRepository userRepository;
  private final NotificationService notificationService;
  private final FeedPostDataMapper feedPostDataMapper;
  private final BlockQueryService blockQueryService;

  private static final int MAX_FEED_SIZE = 1000;
  private static final Duration POST_CACHE_TTL = Duration.ofDays(7);

  /**
   * How much wider than {@code size} the feed window is read when the caller has blocks, so that
   * removing blocked authors afterwards still usually fills the page. Three is a guess with a
   * bounded cost (one Redis range read and up to 3× the cached-post deserialisation), not a
   * measured optimum.
   */
  private static final int BLOCK_OVERFETCH_FACTOR = 3;

  // Joins the caller's transaction, or opens one when there isn't any. Both post.getTags() and
  // post.getHashtags(), read inside FeedPostDataMapper, are LAZY collections, and with
  // spring.jpa.open-in-view off there is no session left over from the request to initialize them.
  // Every caller today happens to be @Transactional, so this only makes an existing unwritten
  // requirement explicit — but it is the difference between a future non-transactional caller
  // failing here and failing in production.
  @Transactional
  public void fanOutPost(Integer postId) {
    PostEntity post =
        postRepository
            .findById(postId)
            .orElseThrow(() -> new NotFoundException("Post not found: " + postId));

    UserEntity author =
        userRepository
            .findById(post.getAuthorId())
            .orElseThrow(() -> new NotFoundException("Author not found: " + post.getAuthorId()));

    // The payload itself is built by FeedPostDataMapper, which the read-from-Postgres endpoints
    // (permalink, an author's posts, the discovery feed) share — see that class for why this is
    // not inlined here any more.
    FeedPostDataDto postData = feedPostDataMapper.toFeedPostData(post, author);
    List<Integer> taggedUserIds = postData.getTaggedUserIds();

    fanOutPost(postData, taggedUserIds);
    notifyTaggedUsers(post, author, taggedUserIds);
  }

  public void fanOutPost(FeedPostDataDto postData, List<Integer> taggedUserIds) {
    String postId = String.valueOf(postData.getPostId());
    double score =
        postData.getCreatedAt() != null
            ? postData.getCreatedAt().toInstant().toEpochMilli()
            : java.time.Instant.now().toEpochMilli();

    cachePostData(postId, postData);
    addToFeed(postData.getAuthorId(), postId, score);

    if (!PostVisibility.PRIVATE.equals(postData.getVisibility())) {
      List<Integer> friendIds = friendshipQueryService.getFriendIds(postData.getAuthorId());
      for (Integer friendId : friendIds) {
        addToFeed(friendId, postId, score);
      }
    }

    if (Objects.nonNull(taggedUserIds)) {
      for (Integer taggedUserId : taggedUserIds) {
        addToFeed(taggedUserId, postId, score);
      }
    }

    log.debug("Fan-out post {} (visibility={})", postData.getPostId(), postData.getVisibility());
  }

  private void notifyTaggedUsers(PostEntity post, UserEntity author, List<Integer> taggedUserIds) {
    if (CollectionUtils.isEmpty(taggedUserIds)) {
      return;
    }
    for (Integer taggedUserId : taggedUserIds) {
      if (taggedUserId.equals(post.getAuthorId())) {
        continue;
      }
      notificationService.send(
          SendNotificationRequest.builder()
              .recipientId(taggedUserId)
              .actorId(post.getAuthorId())
              .type(NotificationType.POST_TAGGED)
              .title("You were tagged in a post")
              .body(displayName(author) + " tagged you in a post")
              .referenceId(post.getId())
              .referenceType("POST")
              .build());
    }
  }

  private String displayName(UserEntity user) {
    return user.getFullName() != null && !user.getFullName().isBlank()
        ? user.getFullName()
        : "Someone";
  }

  public void updatePostCache(FeedPostDataDto postData) {
    cachePostData(String.valueOf(postData.getPostId()), postData);
  }

  /**
   * Rewrites the cached like count for one post.
   *
   * <p>The feed never falls back to Postgres, so a counter that is only correct in the database
   * is a counter the user never sees. Callers pass a count they have just read from their own
   * repository rather than a delta: read-modify-write against Redis is not atomic, and under
   * concurrent reactions a delta would drift permanently, whereas an absolute value taken from
   * the authoritative table self-corrects on the very next interaction.
   */
  public void updateCachedLikeCount(Integer postId, int likeCount) {
    mutateCachedPost(postId, post -> post.setLikeCount(likeCount));
  }

  /** Rewrites the cached comment count for one post — see {@link #updateCachedLikeCount}. */
  public void updateCachedCommentCount(Integer postId, int commentCount) {
    mutateCachedPost(postId, post -> post.setCommentCount(commentCount));
  }

  /**
   * Rewrites the cached QNA block for one post — see {@link #updateCachedLikeCount}. Accepting an
   * answer changes {@code isResolved}/{@code acceptedAnswerId}, and the feed would otherwise keep
   * serving the pre-accept copy.
   */
  public void updateCachedQnaDetails(Integer postId, QnaDetails qnaDetails) {
    mutateCachedPost(postId, post -> post.setQnaDetails(qnaDetails));
  }

  private void mutateCachedPost(Integer postId, Consumer<FeedPostDataDto> mutation) {
    String key = POST_CACHE_KEY_PREFIX + postId;
    String json = redisTemplate.opsForValue().get(key);

    // A miss is normal, not an error: private posts and posts still in moderation never reach
    // the cache, and reacting to one of those must not resurrect it into anybody's feed.
    if (Objects.isNull(json)) {
      return;
    }

    try {
      FeedPostDataDto postData = objectMapper.readValue(json, FeedPostDataDto.class);
      mutation.accept(postData);
      cachePostData(String.valueOf(postId), postData);
    } catch (Exception e) {
      log.warn("Failed to update cached counters for post {}", postId, e);
    }
  }

  public void removePost(Integer postId, Integer authorId, List<Integer> taggedUserIds) {
    String postIdStr = String.valueOf(postId);

    redisTemplate.delete(POST_CACHE_KEY_PREFIX + postIdStr);
    removeFromFeed(authorId, postIdStr);

    List<Integer> friendIds = friendshipQueryService.getFriendIds(authorId);
    for (Integer friendId : friendIds) {
      removeFromFeed(friendId, postIdStr);
    }

    if (!CollectionUtils.isEmpty(taggedUserIds)) {
      for (Integer taggedUserId : taggedUserIds) {
        removeFromFeed(taggedUserId, postIdStr);
      }
    }
  }

  /**
   * The caller's own feed.
   *
   * <p>Blocked authors are removed <b>after</b> the page is read, unlike everywhere else, and that
   * is forced by the storage: this feed is a Redis sorted set of post ids per user, so there is no
   * query to add a predicate to. A post already fanned out to somebody's feed before the block was
   * placed is still sitting in that set, and blocking does not walk every follower's list to prune
   * it — that would be a write over an unbounded number of keys for something the read can filter.
   *
   * <p>The cost of filtering after the fact is that a page can come back short. To keep that from
   * being visible for the ordinary case, the window read from Redis is widened only when the
   * caller actually has blocks; a user with none — nearly everyone — reads exactly what they did
   * before. It can still return fewer than {@code size} items for a user who has blocked heavily
   * and whose feed is dense with those authors; that is a thin page, not a leak, and the next page
   * still advances.
   */
  public FeedResponseDto getFeed(Integer userId, int page, int size) {
    Set<Integer> blockedIds = blockQueryService.blockedPairIds(userId);

    String feedKey = FEED_KEY_PREFIX + userId;
    long start = (long) (page - 1) * size;
    long end = start + (blockedIds.isEmpty() ? size : (long) size * BLOCK_OVERFETCH_FACTOR);

    Set<String> postIds = redisTemplate.opsForZSet().reverseRange(feedKey, start, end);

    if (CollectionUtils.isEmpty(postIds)) {
      return FeedResponseDto.builder()
          .posts(List.of())
          .page(page)
          .size(size)
          .hasMore(false)
          .build();
    }

    List<FeedPostDataDto> posts = loadPostsFromCache(postIds);
    if (!blockedIds.isEmpty()) {
      posts = posts.stream().filter(post -> !blockedIds.contains(post.getAuthorId())).toList();
    }
    signBookCovers(posts);

    boolean hasMore = posts.size() > size;
    if (hasMore) {
      posts = posts.subList(0, size);
    }

    return FeedResponseDto.builder().posts(posts).page(page).size(size).hasMore(hasMore).build();
  }

  /**
   * Records that {@code userId} engaged with a post by {@code authorId}.
   *
   * <p>This is the write half of feed affinity: {@code PostScoringService.loadAffinityMap} counts
   * these rows per author over the last 30 days and boosts that author's posts by up to six hours
   * of apparent freshness. It had no caller anywhere in production — only tests — so {@code
   * t_user_interactions} was permanently empty, {@code loadAffinityMap} always returned an empty
   * map, and the affinity term of the ranking formula was always exactly zero. The feed was
   * ordered by recency and engagement alone while looking, from the code, like it personalised.
   *
   * <p>Callers must skip self-interaction: affinity with yourself would boost your own posts in
   * your own feed, which is noise, and every other per-post side effect here (author notification,
   * reputation award) already skips it.
   *
   * <p>Deliberately append-only — nothing deletes a row when a reaction is removed, unlike the
   * reputation award it sits next to. Having clicked like is attention paid to that author whether
   * or not the click was taken back, and the 30-day window in {@code loadAffinityMap} already ages
   * the signal out. The cost is that repeated like/unlike on one post adds a row each time; it only
   * skews the reordering of that user's own feed, so it is not worth a dedup index to prevent.
   */
  public void trackInteraction(
      Integer userId, Integer postId, Integer authorId, InteractionType type) {
    UserInteractionEntity entity = new UserInteractionEntity();
    entity.setUserId(userId);
    entity.setPostId(postId);
    entity.setAuthorId(authorId);
    entity.setType(type);
    userInteractionRepository.save(entity);
  }

  /** Signs each cached cover key as the feed is served — see {@code FeedPostDataMapper}. */
  private void signBookCovers(List<FeedPostDataDto> posts) {
    posts.forEach(feedPostDataMapper::signBookCover);
  }

  private List<FeedPostDataDto> loadPostsFromCache(Collection<String> postIds) {
    List<String> keys = postIds.stream().map(id -> POST_CACHE_KEY_PREFIX + id).toList();
    List<String> values = redisTemplate.opsForValue().multiGet(keys);

    if (Objects.isNull(values)) {
      return List.of();
    }

    List<FeedPostDataDto> posts = new ArrayList<>(values.size());
    for (String json : values) {
      try {
        posts.add(objectMapper.readValue(json, FeedPostDataDto.class));
      } catch (Exception e) {
        log.warn("Failed to deserialize cached post", e);
      }
    }
    return posts;
  }

  private void cachePostData(String postId, FeedPostDataDto data) {
    try {
      String json = objectMapper.writeValueAsString(data);
      redisTemplate.opsForValue().set(POST_CACHE_KEY_PREFIX + postId, json, POST_CACHE_TTL);
    } catch (Exception e) {
      log.error("Failed to cache post data for {}", postId, e);
    }
  }

  private void addToFeed(Integer userId, String postId, double score) {
    String feedKey = FEED_KEY_PREFIX + userId;
    redisTemplate.opsForZSet().add(feedKey, postId, score);
    redisTemplate.opsForZSet().removeRange(feedKey, 0, -(MAX_FEED_SIZE + 1));
  }

  private void removeFromFeed(Integer userId, String postId) {
    redisTemplate.opsForZSet().remove(FEED_KEY_PREFIX + userId, postId);
  }
}
