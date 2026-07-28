package com.socialapp.newsfeed.service;

import static com.socialapp.newsfeed.service.PostScoringService.FEED_KEY_PREFIX;
import static com.socialapp.newsfeed.service.PostScoringService.POST_CACHE_KEY_PREFIX;

import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socialapp.bookstore.dto.RatingBreakdownDto;
import com.socialapp.bookstore.entity.BookEntity;
import com.socialapp.bookstore.repository.BookRepository;
import com.socialapp.bookstore.service.BookReviewService;
import com.socialapp.common.exception.NotFoundException;
import com.socialapp.common.utils.GoogleMapsUrlBuilder;
import com.socialapp.newsfeed.dto.FeedBookSummaryDto;
import com.socialapp.newsfeed.dto.FeedPostDataDto;
import com.socialapp.newsfeed.dto.FeedResponseDto;
import com.socialapp.newsfeed.entity.UserInteractionEntity;
import com.socialapp.newsfeed.entity.enums.InteractionType;
import com.socialapp.newsfeed.repository.UserInteractionRepository;
import com.socialapp.notifications.dto.SendNotificationRequest;
import com.socialapp.notifications.entity.enums.NotificationType;
import com.socialapp.notifications.services.NotificationService;
import com.socialapp.posts.entity.HashtagEntity;
import com.socialapp.posts.entity.PostEntity;
import com.socialapp.posts.entity.PostTagEntity;
import com.socialapp.posts.entity.enums.PostType;
import com.socialapp.posts.entity.enums.PostVisibility;
import com.socialapp.posts.repository.CommentRepository;
import com.socialapp.posts.repository.PostReactionRepository;
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
  private final BookRepository bookRepository;
  private final BookReviewService bookReviewService;
  private final PostReactionRepository postReactionRepository;
  private final CommentRepository commentRepository;

  private static final int MAX_FEED_SIZE = 1000;
  private static final Duration POST_CACHE_TTL = Duration.ofDays(7);

  public void fanOutPost(Integer postId) {
    PostEntity post =
        postRepository
            .findById(postId)
            .orElseThrow(() -> new NotFoundException("Post not found: " + postId));

    UserEntity author =
        userRepository
            .findById(post.getAuthorId())
            .orElseThrow(() -> new NotFoundException("Author not found: " + post.getAuthorId()));

    List<Integer> taggedUserIds =
        Objects.isNull(post.getTags())
            ? List.of()
            : post.getTags().stream().map(PostTagEntity::getTaggedUserId).toList();

    FeedPostDataDto postData =
        FeedPostDataDto.builder()
            .postId(post.getId())
            .authorId(post.getAuthorId())
            .authorFullName(author.getFullName())
            .authorProfilePictureUrl(author.getProfilePictureUrl())
            .authorEliteScore(author.getEliteScore())
            .content(post.getContent())
            .visibility(post.getVisibility())
            .googlePlaceId(post.getGooglePlaceId())
            .locationType(post.getLocationType())
            .locationDetails(post.getLocationDetails())
            .googleMapsUrl(
                post.getLocationDetails() != null
                    ? GoogleMapsUrlBuilder.build(
                        post.getLocationDetails().getLatitude(),
                        post.getLocationDetails().getLongitude())
                    : null)
            .postType(post.getPostType())
            .eventDetails(post.getEventDetails())
            // Every publish path runs through here (createPost, updatePost,
            // ModerationEventListener, AdminModerationService.reviewPost), so a block missing
            // from this builder is a block that no post in the feed can ever have. That is not
            // only a display bug: updatePost applies UpdatePostRequest with
            // BeanUtils.copyProperties, which copies nulls, and a client can only send back what
            // the feed handed it — so a block absent here gets wiped from Postgres on the first
            // edit. Keep this list in step with PostEntity's detail columns.
            .quizDetails(post.getQuizDetails())
            .codeSnippetDetails(post.getCodeSnippetDetails())
            .articleDetails(post.getArticleDetails())
            .qnaDetails(post.getQnaDetails())
            .pollDetails(post.getPollDetails())
            .linkDetails(post.getLinkDetails())
            .images(post.getImages())
            .taggedUserIds(taggedUserIds)
            .book(loadBookSummary(post))
            .hashtags(
                post.getHashtags() != null
                    ? post.getHashtags().stream().map(HashtagEntity::getName).toList()
                    : null)
            // Re-read on every fan-out rather than assumed zero: this method also runs for an
            // edit and for a moderation approval that lands long after the post was written, by
            // which time it can already carry reactions and comments.
            .likeCount((int) postReactionRepository.countByIdPostId(post.getId()))
            .commentCount((int) commentRepository.countByPostId(post.getId()))
            .createdAt(post.getCreatedAt())
            .build();

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

  private FeedBookSummaryDto loadBookSummary(PostEntity post) {
    if (!PostType.BOOK.equals(post.getPostType())) {
      return null;
    }

    return bookRepository.findByPostId(post.getId()).stream()
        .findFirst()
        .map(this::toBookSummary)
        .orElse(null);
  }

  private FeedBookSummaryDto toBookSummary(BookEntity book) {
    RatingBreakdownDto ratings = bookReviewService.getRatingBreakdown(book.getId());

    return FeedBookSummaryDto.builder()
        .bookId(book.getId())
        .title(book.getTitle())
        .description(book.getDescription())
        .coverImageUrl(book.getCoverImageUrl())
        .fileFormat(book.getFileFormat())
        .fileSizeBytes(book.getFileSizeBytes())
        .totalPages(book.getTotalPages())
        .previewPages(book.getPreviewPages())
        .price(book.getPrice())
        .currency(book.getCurrency())
        .isFree(book.getIsFree())
        .avgRating(book.getAvgRating())
        .reviewCount(book.getReviewCount())
        .oneStarCount(ratings.oneStarCount())
        .twoStarsCount(ratings.twoStarsCount())
        .threeStarsCount(ratings.threeStarsCount())
        .fourStarsCount(ratings.fourStarsCount())
        .fiveStarsCount(ratings.fiveStarsCount())
        .totalRatings(ratings.totalRatings())
        .build();
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

  public FeedResponseDto getFeed(Integer userId, int page, int size) {
    String feedKey = FEED_KEY_PREFIX + userId;
    long start = (long) (page - 1) * size;
    long end = start + size;

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

    boolean hasMore = posts.size() > size;
    if (hasMore) {
      posts = posts.subList(0, size);
    }

    return FeedResponseDto.builder().posts(posts).page(page).size(size).hasMore(hasMore).build();
  }

  public void trackInteraction(
      Integer userId, Integer postId, Integer authorId, InteractionType type) {
    UserInteractionEntity entity = new UserInteractionEntity();
    entity.setUserId(userId);
    entity.setPostId(postId);
    entity.setAuthorId(authorId);
    entity.setType(type);
    userInteractionRepository.save(entity);
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
