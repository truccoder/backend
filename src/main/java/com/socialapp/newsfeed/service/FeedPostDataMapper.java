package com.socialapp.newsfeed.service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.socialapp.bookstore.dto.RatingBreakdownDto;
import com.socialapp.bookstore.entity.BookEntity;
import com.socialapp.bookstore.repository.BookRepository;
import com.socialapp.bookstore.service.BookReviewService;
import com.socialapp.bookstore.service.BookStorageService;
import com.socialapp.common.exception.NotFoundException;
import com.socialapp.common.utils.GoogleMapsUrlBuilder;
import com.socialapp.newsfeed.dto.FeedBookSummaryDto;
import com.socialapp.newsfeed.dto.FeedPostDataDto;
import com.socialapp.posts.dto.PublicQuizDetailsDto;
import com.socialapp.posts.entity.HashtagEntity;
import com.socialapp.posts.entity.PostEntity;
import com.socialapp.posts.entity.PostTagEntity;
import com.socialapp.posts.entity.enums.PostType;
import com.socialapp.posts.entity.enums.ReactionType;
import com.socialapp.posts.repository.CommentRepository;
import com.socialapp.posts.repository.PostReactionRepository;
import com.socialapp.reputation.RepLevel;
import com.socialapp.security.entity.UserEntity;
import com.socialapp.security.repository.UserRepository;

import lombok.RequiredArgsConstructor;

/**
 * Turns a stored post into the payload the clients already know how to render.
 *
 * <p>{@link FeedPostDataDto} used to be built in exactly one place — inside {@code
 * NewsfeedService.fanOutPost} — because the feed was the only way to read a post: fan-out wrote
 * the payload into Redis and {@code getFeed} handed it back. The permalink, an author's post list
 * and the discovery feed all read from Postgres instead, so they never pass through that code. A
 * second builder for the same shape is how the two drift, and a field added to one and not the
 * other is a field the client sees on a post in the feed and loses when it opens the same post by
 * its own URL. Hence one mapper, used by every path.
 *
 * <p><b>Callers must be transactional.</b> {@code post.getTags()} and {@code post.getHashtags()}
 * are LAZY and {@code spring.jpa.open-in-view} is off, so there is no session left over from the
 * request to initialise them.
 */
@Component
@RequiredArgsConstructor
public class FeedPostDataMapper {

  private final UserRepository userRepository;
  private final BookRepository bookRepository;
  private final BookReviewService bookReviewService;
  private final BookStorageService bookStorageService;
  private final PostReactionRepository postReactionRepository;
  private final CommentRepository commentRepository;

  public FeedPostDataDto toFeedPostData(PostEntity post) {
    UserEntity author =
        userRepository
            .findById(post.getAuthorId())
            .orElseThrow(() -> new NotFoundException("Author not found: " + post.getAuthorId()));
    return toFeedPostData(post, author);
  }

  /**
   * Overload for callers that have already loaded the author (a page of posts by one person, where
   * looking the same user up once per row would be a needless query per post).
   */
  public FeedPostDataDto toFeedPostData(PostEntity post, UserEntity author) {
    // Re-read rather than assumed zero: this also runs for an edit and for a moderation approval
    // that lands long after the post was written, by which time it can already carry reactions
    // and comments.
    return build(
        post,
        author,
        (int) postReactionRepository.countByIdPostId(post.getId()),
        (int) commentRepository.countByPostId(post.getId()),
        postReactionRepository.countByType(post.getId()));
  }

  private FeedPostDataDto build(
      PostEntity post,
      UserEntity author,
      int likeCount,
      int commentCount,
      Map<ReactionType, Long> reactionSummary) {
    List<Integer> taggedUserIds =
        Objects.isNull(post.getTags())
            ? List.of()
            : post.getTags().stream().map(PostTagEntity::getTaggedUserId).toList();

    return FeedPostDataDto.builder()
        .postId(post.getId())
        .authorId(post.getAuthorId())
        .authorUsername(author.getUsername())
        .authorFullName(author.getFullName())
        .authorProfilePictureUrl(author.getProfilePictureUrl())
        .authorEliteScore(author.getEliteScore())
        .authorLevelName(RepLevel.displayNameForScore(author.getEliteScore()))
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
        // Every publish path runs through here (createPost, updatePost, ModerationEventListener,
        // AdminModerationService.reviewPost), so a block missing from this builder is a block that
        // no post in the feed can ever have. That is not only a display bug: updatePost applies
        // UpdatePostRequest with BeanUtils.copyProperties, which copies nulls, and a client can
        // only send back what the feed handed it — so a block absent here gets wiped from Postgres
        // on the first edit. Keep this list in step with PostEntity's detail columns.
        .quizDetails(PublicQuizDetailsDto.from(post.getQuizDetails()))
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
        .likeCount(likeCount)
        .commentCount(commentCount)
        // An empty map, never null: a post nobody has reacted to has a known breakdown. Null is
        // reserved for cache entries written before this field existed, where the client genuinely
        // does not know — see FeedPostDataDto#reactionSummary.
        .reactionSummary(reactionSummary)
        .createdAt(post.getCreatedAt())
        .updatedAt(editedAt(post))
        .build();
  }

  /**
   * How long after creation a write has to land before it counts as an edit.
   *
   * <p>{@code @CreationTimestamp} and {@code @UpdateTimestamp} are two separate generators and
   * both fire on the same INSERT, so a post nobody has ever touched still stores an {@code
   * updated_at} — a few microseconds after {@code created_at}, never exactly equal to it. A plain
   * {@code isAfter} comparison would therefore mark every post in the feed as edited. A second is
   * far longer than the gap between two generator calls in one insert and far shorter than any
   * real edit, which always needs a second request.
   */
  private static final Duration EDIT_THRESHOLD = Duration.ofSeconds(1);

  /**
   * {@code updatedAt} for a post that has actually been edited, null for one that has not — see
   * {@link #EDIT_THRESHOLD} for why this is not a straight null-check on the column.
   *
   * <p>Null rather than the creation time, because the client reads the presence of this field as
   * "this post was edited". Rows written before {@code updated_at} existed carry NULL and land on
   * the same answer.
   */
  private OffsetDateTime editedAt(PostEntity post) {
    if (Objects.isNull(post.getUpdatedAt()) || Objects.isNull(post.getCreatedAt())) {
      return null;
    }
    return Duration.between(post.getCreatedAt(), post.getUpdatedAt()).compareTo(EDIT_THRESHOLD) > 0
        ? post.getUpdatedAt()
        : null;
  }

  /**
   * Maps a whole page of posts, with the author lookups and the three counters done in one query
   * each instead of one per row.
   *
   * <p>The single-post overloads re-read {@code likeCount}/{@code commentCount}/{@code
   * reactionSummary} per call, which is right for fan-out (one post at a time) and wrong for a
   * page: at twenty posts that is sixty extra round trips for three aggregates. Book summaries are still loaded per book post — only that
   * post type pays for it, and the rating breakdown behind it is its own aggregate.
   *
   * <p>Covers are signed here, because every caller of this method is serving a response.
   */
  public List<FeedPostDataDto> toFeedPostDataPage(List<PostEntity> posts) {
    if (posts.isEmpty()) {
      return List.of();
    }

    List<Integer> postIds = posts.stream().map(PostEntity::getId).toList();
    Map<Integer, Long> likeCounts = postReactionRepository.countByPostIds(postIds);
    Map<Integer, Map<ReactionType, Long>> reactionSummaries =
        postReactionRepository.countByTypeForPostIds(postIds);
    Map<Integer, Long> commentCounts = commentRepository.countByPostIds(postIds);
    Map<Integer, UserEntity> authorsById =
        userRepository
            .findAllById(posts.stream().map(PostEntity::getAuthorId).distinct().toList())
            .stream()
            .collect(Collectors.toMap(UserEntity::getId, Function.identity()));

    List<FeedPostDataDto> page = new ArrayList<>(posts.size());
    for (PostEntity post : posts) {
      UserEntity author = authorsById.get(post.getAuthorId());
      if (Objects.isNull(author)) {
        // The author row is gone but the post is not. Skipping is the only honest option: the
        // payload has no shape for an unknown author, and inventing a placeholder would make a
        // deleted account look like a live one.
        continue;
      }
      FeedPostDataDto data =
          build(
              post,
              author,
              likeCounts.getOrDefault(post.getId(), 0L).intValue(),
              commentCounts.getOrDefault(post.getId(), 0L).intValue(),
              reactionSummaries.getOrDefault(post.getId(), Map.of()));
      signBookCover(data);
      page.add(data);
    }
    return page;
  }

  /**
   * Fills in the cover URL for a post that carries a book.
   *
   * <p>Kept out of {@link #toFeedPostData} and applied when the payload is served: a MinIO
   * signature lasts 24h while a fan-out cache entry lasts 7 days, so a URL signed at write time is
   * dead for most of its life. Signing is a local HMAC, not a call to MinIO, so doing it per
   * response is cheap.
   */
  public void signBookCover(FeedPostDataDto postData) {
    FeedBookSummaryDto book = postData.getBook();
    if (Objects.nonNull(book)) {
      book.setCoverImageUrl(bookStorageService.getCoverUrl(book.getCoverImageKey()));
    }
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
        .coverImageKey(book.getCoverImageKey())
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
}
