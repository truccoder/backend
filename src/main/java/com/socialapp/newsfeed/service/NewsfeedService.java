package com.socialapp.newsfeed.service;

import static com.socialapp.newsfeed.service.PostScoringService.FEED_KEY_PREFIX;
import static com.socialapp.newsfeed.service.PostScoringService.POST_CACHE_KEY_PREFIX;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.redis.connection.StringRedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socialapp.blocks.service.BlockQueryService;
import com.socialapp.common.exception.NotFoundException;
import com.socialapp.friendships.service.FriendshipService;
import com.socialapp.moderation.enums.ModerationStatus;
import com.socialapp.newsfeed.dto.FeedPostDataDto;
import com.socialapp.newsfeed.dto.FeedRebuildResultDto;
import com.socialapp.newsfeed.dto.FeedResponseDto;
import com.socialapp.newsfeed.dto.FeedScope;
import com.socialapp.newsfeed.entity.UserInteractionEntity;
import com.socialapp.newsfeed.entity.enums.InteractionType;
import com.socialapp.newsfeed.repository.UserInteractionRepository;
import com.socialapp.notifications.dto.SendNotificationRequest;
import com.socialapp.notifications.entity.enums.NotificationType;
import com.socialapp.notifications.services.NotificationService;
import com.socialapp.posts.entity.PostEntity;
import com.socialapp.posts.entity.QnaDetails;
import com.socialapp.posts.entity.enums.PostVisibility;
import com.socialapp.posts.entity.enums.ReactionType;
import com.socialapp.posts.repository.PostRepository;
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
  private final FriendshipService friendshipService;
  private final UserInteractionRepository userInteractionRepository;
  private final PostRepository postRepository;
  private final UserRepository userRepository;
  private final NotificationService notificationService;
  private final FeedPostDataMapper feedPostDataMapper;
  private final BlockQueryService blockQueryService;
  private final SkillTagResolver skillTagResolver;

  private static final int MAX_FEED_SIZE = 1000;
  private static final Duration POST_CACHE_TTL = Duration.ofDays(7);

  /**
   * How long a user's feed index survives without being written to.
   *
   * <p>It used to survive forever. The post payloads expire after seven days but the {@code
   * feed:<userId>} sorted sets never did, so Redis grew with the number of accounts that had ever
   * existed rather than with the number in use — every dormant account keeping up to
   * {@link #MAX_FEED_SIZE} ids alive, on a box that also hosts Postgres pooling, Neo4j and MinIO.
   * After the seventh day those ids point at payloads that are gone, so the rescoring job spent
   * most of its work on feeds with nothing left in them.
   *
   * <p>Thirty days is comfortably longer than the payload TTL, so an active reader never notices.
   * A returning user whose key has expired gets an empty feed until the next fan-out reaches them;
   * {@code AdminNewsfeedController} can rebuild one on demand.
   */
  private static final Duration FEED_TTL = Duration.ofDays(30);

  /**
   * How often the trim actually runs, as a divisor of writes.
   *
   * <p>{@code ZREMRANGEBYRANK} on every single fan-out write doubled the command count for a cap
   * that only matters once a feed passes {@link #MAX_FEED_SIZE}. Trimming on roughly one write in
   * twenty keeps the ceiling honest — a feed can drift a little over it between trims, which costs
   * a few kilobytes and nothing else.
   */
  private static final int TRIM_EVERY_N_WRITES = 20;

  /**
   * How much wider than {@code size} the feed window is read when the caller has blocks, so that
   * removing blocked authors afterwards still usually fills the page. Three is a guess with a
   * bounded cost (one Redis range read and up to 3× the cached-post deserialisation), not a
   * measured optimum.
   */
  private static final int BLOCK_OVERFETCH_FACTOR = 3;

  /**
   * How far back into the caller's feed the {@link FeedScope#SKILLS} tab looks.
   *
   * <p>The block filter can get away with a small overfetch because almost nobody blocks anybody, so
   * almost every post read survives it. A skill filter is the opposite: most posts in a feed are
   * about something other than the handful of skills one person has verified, so the same trick
   * would return a page of two or three items and call it a page. Scanning a fixed window and
   * paginating what survives gives full pages instead.
   *
   * <p>Bounded rather than unbounded because {@code MAX_FEED_SIZE} is 1000 and a whole-feed scan is
   * one MGET of a thousand JSON documents on every tab switch. 300 covers the recent history a
   * reader actually pages through; older matches fall off the end, which is the same thing that
   * happens to the feed itself at 1000.
   */
  private static final int SKILL_SCAN_LIMIT = 300;

  /**
   * How many posts one page of {@link #rebuildAll()} loads. Small enough that a database with far
   * more posts than the seed still rebuilds inside a bounded amount of memory.
   */
  private static final int REBUILD_PAGE_SIZE = 200;

  /**
   * Fans out every approved post again, rebuilding the feed of every user from Postgres.
   *
   * <p>Exists because the feed has exactly one source of truth — Redis — and only one way in:
   * {@code fanOutPost}, called when somebody publishes a post through the API. Two consequences
   * followed from that, and this method is the answer to both. Seeded posts were written straight
   * into Postgres and therefore never reached any feed, so a freshly seeded database showed every
   * account an empty {@code /v1/api/feed} while the discovery feed was full. And in production,
   * losing Redis meant losing every feed permanently, with no way back short of asking users to
   * repost.
   *
   * <p>Calls the two-argument {@code fanOutPost} rather than the one-argument form on purpose: the
   * latter also runs {@code notifyTaggedUsers}, and a rebuild must not tell somebody they were
   * tagged in a post from three months ago. Reputation and interaction tracking are likewise not
   * involved — this writes cache entries and nothing else.
   *
   * <p>Synchronous, and page by page. The caller is an administrator who needs to know when it has
   * finished before demonstrating anything, and paging keeps a whole-table read off the heap. One
   * post that fails is counted and stepped over rather than aborting the run, because a single
   * post whose author row is gone should not cost the other few hundred their fan-out.
   */
  @Transactional
  public FeedRebuildResultDto rebuildAll() {
    log.info("rebuildAll: starting feed rebuild for all APPROVED posts");

    int processed = 0;
    int skipped = 0;
    int pageNumber = 0;
    Page<PostEntity> page;

    do {
      page =
          postRepository.findByModerationStatus(
              ModerationStatus.APPROVED,
              PageRequest.of(pageNumber, REBUILD_PAGE_SIZE, Sort.by(Sort.Direction.ASC, "id")));

      for (PostEntity post : page.getContent()) {
        try {
          UserEntity author =
              userRepository
                  .findById(post.getAuthorId())
                  .orElseThrow(
                      () -> new NotFoundException("Author not found: " + post.getAuthorId()));
          FeedPostDataDto postData = feedPostDataMapper.toFeedPostData(post, author);
          fanOutPost(postData, postData.getTaggedUserIds());
          processed++;
        } catch (RuntimeException e) {
          skipped++;
          log.warn("rebuildAll: skipped postId={} — {}", post.getId(), e.toString());
        }
      }
      pageNumber++;
    } while (page.hasNext());

    log.info("rebuildAll: finished, processed={}, skipped={}", processed, skipped);
    return new FeedRebuildResultDto(processed, skipped);
  }

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

    // One pipelined batch for the whole audience rather than two commands per recipient.
    Set<Integer> recipients = new LinkedHashSet<>();
    if (!PostVisibility.PRIVATE.equals(postData.getVisibility())) {
      recipients.addAll(friendshipService.getFriendIds(postData.getAuthorId()));
    }
    if (Objects.nonNull(taggedUserIds)) {
      recipients.addAll(taggedUserIds);
    }
    recipients.remove(postData.getAuthorId());
    addToFeeds(recipients, postId, score);

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
   * Rewrites the cached reaction total <b>and</b> its per-type breakdown for one post.
   *
   * <p>The feed never falls back to Postgres, so a counter that is only correct in the database is
   * a counter the user never sees. Callers pass values they have just read from their own
   * repository rather than a delta: read-modify-write against Redis is not atomic, and under
   * concurrent reactions a delta would drift permanently, whereas absolute values taken from the
   * authoritative table self-correct on the very next interaction.
   *
   * <p><b>One method for both, and not two.</b> The total and the breakdown are two views of the
   * same rows, so writing them separately means two read-modify-write cycles over the same cache
   * entry and a window in which the chips visibly disagree with the number beside them. Splitting
   * them would also make it possible to add a caller that updates one and forgets the other —
   * which is precisely how the breakdown would rot into something worse than not sending one.
   */
  public void updateCachedReactions(
      Integer postId, int likeCount, Map<ReactionType, Long> reactionSummary) {
    mutateCachedPost(
        postId,
        post -> {
          post.setLikeCount(likeCount);
          post.setReactionSummary(reactionSummary);
        });
  }

  /** Rewrites the cached comment count for one post — see {@link #updateCachedReactions}. */
  public void updateCachedCommentCount(Integer postId, int commentCount) {
    mutateCachedPost(postId, post -> post.setCommentCount(commentCount));
  }

  /**
   * Rewrites the cached QNA block for one post — see {@link #updateCachedReactions}. Accepting an
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

    List<Integer> friendIds = friendshipService.getFriendIds(authorId);
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
    return getFeed(userId, page, size, FeedScope.ALL);
  }

  /**
   * The caller's own feed, narrowed to one scope.
   *
   * <p>{@link FeedScope#SKILLS} is answered from the same Redis fan-out list as {@link
   * FeedScope#ALL} rather than from a Postgres query, and that is a deliberate choice about what
   * the tab means: it is <i>your feed, filtered</i> — the same posts from the same people, minus
   * the ones that are not about anything you have been verified in. A query over all posts carrying
   * those hashtags would be a different product (a topic feed), would ignore who you follow, and
   * would put strangers' posts in a list the other tab promises is yours.
   *
   * <p>What a post is "about" is its hashtags, matched against the caller's verified skills by
   * {@link SkillTagResolver}. Hashtags are already in the cached payload, so the filter costs no
   * extra read per post — only the one skill lookup per request.
   *
   * <p>Unlike the block filter, this one paginates <i>after</i> filtering: see {@link
   * #SKILL_SCAN_LIMIT}. The consequence is that {@code hasMore} is honest about the scanned window
   * and not about the whole feed — a caller who pages past the end of the window sees the list stop
   * even though older matching posts exist further down. That is the same horizon the feed itself
   * has at {@code MAX_FEED_SIZE}, one order of magnitude closer.
   */
  public FeedResponseDto getFeed(Integer userId, int page, int size, FeedScope scope) {
    return FeedScope.SKILLS.equals(scope)
        ? getSkillFeed(userId, page, size)
        : getAllFeed(userId, page, size);
  }

  private FeedResponseDto getAllFeed(Integer userId, int page, int size) {
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

  private FeedResponseDto getSkillFeed(Integer userId, int page, int size) {
    Set<String> skillTags = skillTagResolver.resolveTagsFor(userId);

    // No verified skills, or none that any hashtag corresponds to: the tab is empty, and says so.
    // Falling back to the unfiltered feed here would show the reader somebody else's answer to the
    // question they asked.
    if (skillTags.isEmpty()) {
      return emptyPage(page, size);
    }

    Set<String> postIds =
        redisTemplate.opsForZSet().reverseRange(FEED_KEY_PREFIX + userId, 0, SKILL_SCAN_LIMIT - 1L);

    if (CollectionUtils.isEmpty(postIds)) {
      return emptyPage(page, size);
    }

    Set<Integer> blockedIds = blockQueryService.blockedPairIds(userId);
    List<FeedPostDataDto> matches =
        loadPostsFromCache(postIds).stream()
            .filter(post -> !blockedIds.contains(post.getAuthorId()))
            .filter(post -> touchesAnyTag(post, skillTags))
            .toList();

    // long, for the same reason getAllFeed casts: page and size are only @Positive, so
    // (page - 1) * size overflows int for a large page and wraps negative — which sails past the
    // >= check below and reaches subList(negative, ...) as an IndexOutOfBoundsException, i.e. a
    // 500 from a query string. Compare against the list size in long space, then narrow.
    long start = (long) (page - 1) * size;
    if (start >= matches.size()) {
      return emptyPage(page, size);
    }

    int from = (int) start;
    int end = Math.min(from + size, matches.size());
    List<FeedPostDataDto> pageItems = new ArrayList<>(matches.subList(from, end));
    signBookCovers(pageItems);

    return FeedResponseDto.builder()
        .posts(pageItems)
        .page(page)
        .size(size)
        .hasMore(end < matches.size())
        .build();
  }

  /**
   * Whether a post carries at least one of the caller's skill hashtags.
   *
   * <p>Any-match rather than all-match: a post tagged {@code java} and {@code kubernetes} is about
   * both, and a reader verified in only one of them still wants to see it.
   */
  private boolean touchesAnyTag(FeedPostDataDto post, Set<String> tags) {
    List<String> hashtags = post.getHashtags();
    return !CollectionUtils.isEmpty(hashtags) && hashtags.stream().anyMatch(tags::contains);
  }

  private FeedResponseDto emptyPage(int page, int size) {
    return FeedResponseDto.builder().posts(List.of()).page(page).size(size).hasMore(false).build();
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

  /**
   * Adds one post to one user's feed.
   *
   * <p>Called once per recipient during fan-out, so what it costs per call is multiplied by the
   * author's follower count — see {@link #addToFeeds} for the batched form the fan-out path uses.
   */
  private void addToFeed(Integer userId, String postId, double score) {
    String feedKey = FEED_KEY_PREFIX + userId;
    redisTemplate.opsForZSet().add(feedKey, postId, score);
    redisTemplate.expire(feedKey, FEED_TTL);
    maybeTrim(feedKey);
  }

  /**
   * Adds one post to many feeds in a single round trip.
   *
   * <p>Fan-out used to loop {@link #addToFeed}, which is two Redis commands each, issued serially:
   * a post by someone with five hundred friends cost over a thousand round trips — and it ran
   * inside {@code ModerationEventListener}'s transaction, holding a database connection open for
   * all of them. Pipelining sends the batch and reads the replies once.
   */
  private void addToFeeds(Collection<Integer> userIds, String postId, double score) {
    if (userIds.isEmpty()) {
      return;
    }

    redisTemplate.executePipelined(
        (RedisCallback<Object>)
            connection -> {
              StringRedisConnection stringConnection = (StringRedisConnection) connection;
              for (Integer userId : userIds) {
                String feedKey = FEED_KEY_PREFIX + userId;
                stringConnection.zAdd(feedKey, score, postId);
                stringConnection.expire(feedKey, FEED_TTL.toSeconds());
              }
              return null;
            });

    // Trimming is deliberately outside the pipeline and sampled: it is a cap, not an invariant.
    userIds.forEach(userId -> maybeTrim(FEED_KEY_PREFIX + userId));
  }

  /**
   * Trims a feed back to {@link #MAX_FEED_SIZE}, most of the time.
   *
   * <p>Sampled rather than unconditional — see {@link #TRIM_EVERY_N_WRITES}. Uses
   * {@code ThreadLocalRandom} rather than a counter so it needs no shared state and stays correct
   * across instances.
   */
  private void maybeTrim(String feedKey) {
    if (ThreadLocalRandom.current().nextInt(TRIM_EVERY_N_WRITES) != 0) {
      return;
    }
    redisTemplate.opsForZSet().removeRange(feedKey, 0, -(MAX_FEED_SIZE + 1));
  }

  private void removeFromFeed(Integer userId, String postId) {
    redisTemplate.opsForZSet().remove(FEED_KEY_PREFIX + userId, postId);
  }
}
