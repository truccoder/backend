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
import org.springframework.data.redis.core.ZSetOperations;
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
import com.socialapp.notifications.NotificationMessages;
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
  private final SeenPostTracker seenPostTracker;

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
   * How much apparent freshness a post loses once the reader has scrolled past it.
   *
   * <p><b>Subtracted, never multiplied.</b> The obvious formulation — {@code score *= 0.1}, as the
   * literature on news feed ranking states it — is wrong for this scorer, and destructively so. A
   * score here is not a magnitude with a meaningful zero: it is a point on a timeline, an epoch
   * millisecond shifted forward by {@code ENGAGEMENT_BOOST_MILLIS} and {@code AFFINITY_BOOST_MILLIS}
   * worth of borrowed freshness. Multiplying such a number by a tenth does not make a post "worth ten
   * percent"; it moves it to 1975, below every unseen post permanently and beyond any possibility of
   * recovery. That is a hard filter wearing the costume of a soft one, and a hard filter is precisely
   * what a platform with this little content cannot afford.
   *
   * <p><b>Seven days, and the number is measured rather than guessed.</b> Two constraints bound it
   * from below. The first is the boosts: it has to comfortably exceed {@code
   * ENGAGEMENT_BOOST_MILLIS + AFFINITY_BOOST_MILLIS} (ten hours), or a popular post by a close friend
   * climbs straight back to where the reader already saw it. The second is the one that actually
   * decides the value, and it only shows up against real data: <b>the penalty has to outweigh the
   * typical gap between consecutive posts in a feed, or the demotion is invisible.</b>
   *
   * <p>Measured on the seeded database, one reader's feed of 49 posts spanning 93 days has a median
   * gap of 24 hours between neighbours. A 24-hour penalty therefore cleared only 44% of those gaps
   * and moved a post the reader had just scrolled past down by about one position — arithmetically
   * correct, and useless. Seven days clears 96% of them. If the content ever gets denser this can
   * come down again; the relationship to look at is penalty versus median gap, not the raw number.
   *
   * <p>Large as that is, it is still not a filter and the difference is not cosmetic: nothing is
   * removed, {@code hasMore} counts the demoted posts, paging reaches them, and a post seen an hour
   * ago still outranks an unseen one from a fortnight back. What it buys is the behaviour the feature
   * exists for — scroll past something, refresh, and it is no longer sitting at the top.
   */
  static final long SEEN_PENALTY_MILLIS = 7 * 24 * 3600 * 1000L;

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
    String actor = displayName(author);
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
              .body(actor + " tagged you in a post")
              .messageKey(NotificationMessages.POST_TAGGED)
              .messageArgs(NotificationMessages.args("actor", actor))
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

  /**
   * The {@link FeedScope#ALL} feed, demoting what the reader has already scrolled past.
   *
   * <p>Three paths, and which one runs is decided by a single {@code ZCARD}:
   *
   * <ol>
   *   <li><b>Nothing seen yet</b> — the original code, unchanged, down to the same two Redis calls.
   *       This is the first request of every session and it must not get slower; the same reasoning
   *       keeps {@link #BLOCK_OVERFETCH_FACTOR} off the common path.
   *   <li><b>First page of a scroll-through</b> — read the whole feed index with its scores, subtract
   *       {@link #SEEN_PENALTY_MILLIS} from everything in the seen set, re-sort, and <b>freeze the
   *       result</b> into {@code feedorder:<userId>} before serving a page out of it.
   *   <li><b>A later page</b> — served from that frozen list, not re-ranked.
   * </ol>
   *
   * <p><b>The freezing is not an optimisation; without it this feature eats itself.</b> The client
   * reports a card as seen when it leaves the viewport, which means the posts on page one are marked
   * seen in the moments before page two is requested. Re-ranking on page two would then push exactly
   * those posts down — into the offset window page two is about to read — and the reader would be
   * served page one again, forever, never reaching the posts underneath. Re-ranking against a set
   * that grows as a side effect of paging is pathological for offset pagination in a way that
   * re-ranking against a fixed set is not, so the order is computed once per scroll-through and held
   * still while the reader walks down it — see {@link SeenPostTracker#ORDER_TTL}.
   *
   * <p>A snapshot that has expired mid-scroll falls back to the unranked window rather than reporting
   * the end of the feed: a short page or a repeated one is a blemish, an empty one looks like data
   * loss.
   *
   * <p>Blocked authors are still removed after the page is read, for the reason given on {@link
   * #getFeed(Integer, int, int)} — none of this changes where the block filter can run, only which
   * ids reach it.
   */
  private FeedResponseDto getAllFeed(Integer userId, int page, int size) {
    Set<Integer> blockedIds = blockQueryService.blockedPairIds(userId);
    String feedKey = FEED_KEY_PREFIX + userId;
    long start = (long) (page - 1) * size;
    int want = windowSize(size, blockedIds);

    if (!seenPostTracker.hasSeenAnything(userId)) {
      return pageFromIds(
          redisTemplate.opsForZSet().reverseRange(feedKey, start, start + want - 1L),
          blockedIds,
          page,
          size);
    }

    String orderKey = seenPostTracker.orderKey(userId, null);

    if (page > 1) {
      List<String> fromSnapshot = seenPostTracker.readOrder(orderKey, start, want);
      if (!fromSnapshot.isEmpty()) {
        return pageFromIds(fromSnapshot, blockedIds, page, size);
      }
      // The snapshot aged out while the reader was still scrolling. Fall through to the plain
      // window rather than to an empty page.
      return pageFromIds(
          redisTemplate.opsForZSet().reverseRange(feedKey, start, start + want - 1L),
          blockedIds,
          page,
          size);
    }

    List<String> ranked =
        rankBySeenness(
            userId,
            redisTemplate.opsForZSet().reverseRangeWithScores(feedKey, 0, MAX_FEED_SIZE - 1L));
    seenPostTracker.saveOrder(orderKey, ranked);

    return pageFromIds(slice(ranked, start, want), blockedIds, page, size);
  }

  /**
   * How many ids to read for one page.
   *
   * <p>The overfetch is the caller's only defence against a page that comes back short once blocked
   * authors are filtered out of it, so every path that slices ids has to apply it — an exact-size
   * slice would leave the filter nothing to eat into. One extra beyond that is what {@code hasMore}
   * is read from.
   */
  private int windowSize(int size, Set<Integer> blockedIds) {
    return size * (blockedIds.isEmpty() ? 1 : BLOCK_OVERFETCH_FACTOR) + 1;
  }

  /**
   * Re-orders one window of the feed index so that posts the reader has already scrolled past sink.
   *
   * <p>This is the half of the demotion that is about ranking rather than storage: what a seen post
   * is worth, in the same "hours of apparent freshness" the engagement and affinity boosts are
   * denominated in. Where the seen set lives and when it expires belongs to {@link SeenPostTracker}.
   *
   * <p>Ties keep the order Redis returned them in, so posts nobody has seen stay in score order and
   * the sort adds no arbitrariness of its own.
   */
  private List<String> rankBySeenness(
      Integer userId, Set<ZSetOperations.TypedTuple<String>> window) {
    if (CollectionUtils.isEmpty(window)) {
      return List.of();
    }

    List<ZSetOperations.TypedTuple<String>> tuples = List.copyOf(window);
    List<String> ids = tuples.stream().map(ZSetOperations.TypedTuple::getValue).toList();
    List<Double> seenAt = seenPostTracker.seenAt(userId, ids);

    record Ranked(String id, double score) {}
    List<Ranked> ranked = new ArrayList<>(tuples.size());
    for (int i = 0; i < tuples.size(); i++) {
      // A tuple's score is nullable, and a member without one sorts as if it were the oldest
      // possible post rather than throwing out of the comparator.
      Double raw = tuples.get(i).getScore();
      double score = Objects.isNull(raw) ? 0d : raw;
      boolean seen = i < seenAt.size() && Objects.nonNull(seenAt.get(i));
      ranked.add(new Ranked(ids.get(i), seen ? score - SEEN_PENALTY_MILLIS : score));
    }

    // Stable, so equal scores come out in the order Redis gave them.
    ranked.sort(Comparator.comparingDouble(Ranked::score).reversed());
    return ranked.stream().map(Ranked::id).toList();
  }

  /**
   * One page out of an in-memory list of ids.
   *
   * <p>{@code long}, for the reason spelled out in {@link #getSkillFeed}: {@code page} is only
   * {@code @Positive} and carries no upper bound, so {@code (page - 1) * size} overflows {@code int}
   * for a large page and wraps negative — which sails past a naive bounds check and reaches {@code
   * subList} as an exception, i.e. a 500 from a query string.
   */
  private List<String> slice(List<String> ids, long start, int want) {
    if (start >= ids.size()) {
      return List.of();
    }
    int from = (int) start;
    return ids.subList(from, Math.min(from + want, ids.size()));
  }

  /**
   * Loads, filters and trims one page's worth of ids into a response.
   *
   * <p><b>{@code hasMore} is counted from the ids, not from the posts.</b> A post whose payload has
   * fallen out of the seven-day cache while its id survives in the thirty-day feed index is dropped
   * by {@code loadPostsFromCache}, so counting what survived deserialisation reports "no more posts"
   * to a reader whose feed simply has a gap in it, and truncates the feed early. The ids are what the
   * index actually holds.
   */
  private FeedResponseDto pageFromIds(
      Collection<String> ids, Set<Integer> blockedIds, int page, int size) {
    if (CollectionUtils.isEmpty(ids)) {
      return emptyPage(page, size);
    }

    boolean hasMore = ids.size() > size;

    List<FeedPostDataDto> posts = loadPostsFromCache(ids);
    if (!blockedIds.isEmpty()) {
      posts = posts.stream().filter(post -> !blockedIds.contains(post.getAuthorId())).toList();
    }
    if (posts.size() > size) {
      posts = posts.subList(0, size);
    }
    signBookCovers(posts);

    return FeedResponseDto.builder().posts(posts).page(page).size(size).hasMore(hasMore).build();
  }

  /**
   * The posts the skills tab will consider, in the order it should consider them.
   *
   * <p><b>The window is chosen before the demotion is applied, deliberately.</b> The obvious
   * rearrangement — subtract the penalty first, then take the top {@link #SKILL_SCAN_LIMIT} — would
   * push seen posts out of the candidate set entirely once a reader had scrolled through enough of
   * it, and a tab that stops showing a post because you read it is a filter, which is the one thing
   * this feature must not become. Ranking strictly inside a fixed window can reorder the tab; it can
   * never empty it.
   *
   * <p>The order is frozen for the same reason {@link #getAllFeed} freezes its own, and under its own
   * key: this tab and the {@code ALL} tab page through different lists, and sharing one snapshot
   * would let switching tabs scramble the other one's pagination.
   */
  private Collection<String> skillCandidateIds(Integer userId, int page) {
    String feedKey = FEED_KEY_PREFIX + userId;

    if (!seenPostTracker.hasSeenAnything(userId)) {
      Set<String> unranked =
          redisTemplate.opsForZSet().reverseRange(feedKey, 0, SKILL_SCAN_LIMIT - 1L);
      return Objects.isNull(unranked) ? List.of() : unranked;
    }

    String orderKey = seenPostTracker.orderKey(userId, FeedScope.SKILLS);

    if (page > 1) {
      List<String> fromSnapshot = seenPostTracker.readOrder(orderKey, 0, SKILL_SCAN_LIMIT);
      if (!fromSnapshot.isEmpty()) {
        return fromSnapshot;
      }
      Set<String> unranked =
          redisTemplate.opsForZSet().reverseRange(feedKey, 0, SKILL_SCAN_LIMIT - 1L);
      return Objects.isNull(unranked) ? List.of() : unranked;
    }

    List<String> ranked =
        rankBySeenness(
            userId,
            redisTemplate.opsForZSet().reverseRangeWithScores(feedKey, 0, SKILL_SCAN_LIMIT - 1L));
    seenPostTracker.saveOrder(orderKey, ranked);
    return ranked;
  }

  private FeedResponseDto getSkillFeed(Integer userId, int page, int size) {
    Set<String> skillTags = skillTagResolver.resolveTagsFor(userId);

    // No verified skills, or none that any hashtag corresponds to: the tab is empty, and says so.
    // Falling back to the unfiltered feed here would show the reader somebody else's answer to the
    // question they asked.
    if (skillTags.isEmpty()) {
      return emptyPage(page, size);
    }

    Collection<String> postIds = skillCandidateIds(userId, page);

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

  /**
   * Records that {@code userId} has scrolled past these posts, for the rest of their session.
   *
   * <p>This is the write half of the seen-post demotion; {@link #getAllFeed} is the read half and
   * {@link #SEEN_PENALTY_MILLIS} explains what the two of them add up to. Nothing here reaches
   * Postgres, and that is the point: {@link com.socialapp.newsfeed.entity.enums.InteractionType}
   * rejected a {@code VIEW} constant because a row per post per page load would feed the ranking job
   * a signal derived from its own output. A per-session Redis key that expires on its own is not that
   * signal — it never touches affinity, never outlives the sitting it was written in, and is read
   * only by the request that is about to render a page.
   *
   * <p><b>Fire and forget.</b> A client reporting what it has displayed is telling the server
   * something, not asking it for anything, so a Redis failure is logged and swallowed: the reader
   * loses the demotion, not the scroll they were in the middle of.
   */
  public void markSeen(Integer userId, Collection<Integer> postIds) {
    seenPostTracker.markSeen(userId, postIds);
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
