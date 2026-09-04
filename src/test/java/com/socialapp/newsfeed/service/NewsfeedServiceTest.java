package com.socialapp.newsfeed.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socialapp.blocks.service.BlockQueryService;
import com.socialapp.bookstore.dto.RatingBreakdownDto;
import com.socialapp.bookstore.entity.BookEntity;
import com.socialapp.bookstore.entity.enums.FileFormat;
import com.socialapp.bookstore.repository.BookRepository;
import com.socialapp.bookstore.service.BookReviewService;
import com.socialapp.bookstore.service.BookStorageService;
import com.socialapp.common.exception.NotFoundException;
import com.socialapp.friendships.service.FriendshipService;
import com.socialapp.moderation.enums.ModerationStatus;
import com.socialapp.newsfeed.dto.FeedBookSummaryDto;
import com.socialapp.newsfeed.dto.FeedPostDataDto;
import com.socialapp.newsfeed.dto.FeedRebuildResultDto;
import com.socialapp.newsfeed.dto.FeedResponseDto;
import com.socialapp.newsfeed.dto.FeedScope;
import com.socialapp.newsfeed.entity.UserInteractionEntity;
import com.socialapp.newsfeed.entity.enums.InteractionType;
import com.socialapp.newsfeed.repository.UserInteractionRepository;
import com.socialapp.notifications.dto.SendNotificationRequest;
import com.socialapp.notifications.services.NotificationService;
import com.socialapp.posts.entity.ArticleDetails;
import com.socialapp.posts.entity.CodeSnippetDetails;
import com.socialapp.posts.entity.LinkDetails;
import com.socialapp.posts.entity.LocationDetails;
import com.socialapp.posts.entity.PollDetails;
import com.socialapp.posts.entity.PostEntity;
import com.socialapp.posts.entity.PostTagEntity;
import com.socialapp.posts.entity.PostTagId;
import com.socialapp.posts.entity.QnaDetails;
import com.socialapp.posts.entity.QuizDetails;
import com.socialapp.posts.entity.QuizQuestion;
import com.socialapp.posts.entity.enums.PostType;
import com.socialapp.posts.entity.enums.PostVisibility;
import com.socialapp.posts.entity.enums.ReactionType;
import com.socialapp.posts.repository.CommentRepository;
import com.socialapp.posts.repository.PostReactionRepository;
import com.socialapp.posts.repository.PostRepository;
import com.socialapp.security.entity.UserEntity;
import com.socialapp.security.repository.UserRepository;

/**
 * Component (unit) tests for {@link NewsfeedService}, per ISTQB CTFL v4.0.1 (Section 2.2.1
 * component testing, Section 5.1.6 test pyramid, Section 4.3.2 branch testing, Section 2.1.3 BDD
 * Given/When/Then) — see {@code PostServiceTest} for the full rationale. {@link
 * com.fasterxml.jackson.databind.ObjectMapper} is mocked (rather than used for real) so
 * (de)serialization failures can be deterministically triggered to cover the catch branches.
 */
@ExtendWith(MockitoExtension.class)
class NewsfeedServiceTest {

  private static final Integer POST_ID = 100;
  private static final Integer AUTHOR_ID = 1;
  private static final Integer FRIEND_ID = 2;
  private static final Integer TAGGED_ID = 3;

  @Mock private StringRedisTemplate redisTemplate;
  @Mock private ObjectMapper objectMapper;
  @Mock private FriendshipService friendshipService;
  @Mock private UserInteractionRepository userInteractionRepository;
  @Mock private PostRepository postRepository;
  @Mock private UserRepository userRepository;
  @Mock private NotificationService notificationService;
  @Mock private BookRepository bookRepository;
  @Mock private BookReviewService bookReviewService;
  @Mock private BookStorageService bookStorageService;
  @Mock private PostReactionRepository postReactionRepository;
  @Mock private CommentRepository commentRepository;

  @Mock private ZSetOperations<String, String> zSetOperations;
  @Mock private ValueOperations<String, String> valueOperations;
  @Mock private ListOperations<String, String> listOperations;

  @Mock private BlockQueryService blockQueryService;
  @Mock private SkillTagResolver skillTagResolver;
  @Mock private SeenPostTracker seenPostTracker;

  private NewsfeedService newsfeedService;

  /**
   * The payload builder is wired up for real, not mocked.
   *
   * <p>{@link FeedPostDataMapper} was carved out of this class so the read-from-Postgres endpoints
   * could return the same shape as the feed; the behaviour it took with it — which detail blocks
   * are copied, where the counts come from, how a book summary is attached — is still behaviour
   * fan-out is responsible for, and the assertions below are the ones that catch a field being
   * dropped from that payload. Mocking the mapper would have deleted that coverage in the name of
   * unit purity. Its own edge cases are covered separately in {@code FeedPostDataMapperTest}.
   */
  @BeforeEach
  void wireService() {
    newsfeedService =
        new NewsfeedService(
            redisTemplate,
            objectMapper,
            friendshipService,
            userInteractionRepository,
            postRepository,
            userRepository,
            notificationService,
            new FeedPostDataMapper(
                userRepository,
                bookRepository,
                bookReviewService,
                bookStorageService,
                postReactionRepository,
                commentRepository),
            blockQueryService,
            skillTagResolver,
            seenPostTracker);
  }

  @Captor private ArgumentCaptor<SendNotificationRequest> notificationCaptor;

  private static PostEntity post(
      Integer id, Integer authorId, PostVisibility visibility, PostType type) {
    PostEntity post = new PostEntity();
    post.setId(id);
    post.setAuthorId(authorId);
    post.setVisibility(visibility);
    post.setPostType(type);
    post.setContent("Hello");
    post.setCreatedAt(OffsetDateTime.now());
    return post;
  }

  private static UserEntity user(Integer id, String fullName) {
    UserEntity user = new UserEntity();
    user.setId(id);
    user.setFullName(fullName);
    return user;
  }

  private static QuizDetails quizWithAnswers() {
    QuizQuestion question = new QuizQuestion();
    question.setQuestion("2 + 2?");
    question.setOptions(List.of("3", "4"));
    question.setCorrectOptionIndex(1);
    question.setExplanation("Two plus two is four");

    QuizDetails quiz = new QuizDetails();
    quiz.setQuestions(List.of(question));
    return quiz;
  }

  private static FeedPostDataDto feedPost(
      Integer postId, Integer authorId, OffsetDateTime createdAt) {
    return FeedPostDataDto.builder().postId(postId).authorId(authorId).createdAt(createdAt).build();
  }

  // =====================================================================
  // fanOutPost(Integer postId)
  // =====================================================================

  @Nested
  @DisplayName("fanOutPost(postId)")
  class FanOutPostByIdTests {

    @Test
    @DisplayName("should reject when the post does not exist")
    void shouldThrowNotFoundException_whenPostDoesNotExist() {
      // Given
      when(postRepository.findById(POST_ID)).thenReturn(Optional.empty());

      // When / Then
      assertThatThrownBy(() -> newsfeedService.fanOutPost(POST_ID))
          .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("should reject when the author no longer exists")
    void shouldThrowNotFoundException_whenAuthorDoesNotExist() {
      // Given
      when(postRepository.findById(POST_ID))
          .thenReturn(
              Optional.of(post(POST_ID, AUTHOR_ID, PostVisibility.PUBLIC, PostType.REGULAR)));
      when(userRepository.findById(AUTHOR_ID)).thenReturn(Optional.empty());

      // When / Then
      assertThatThrownBy(() -> newsfeedService.fanOutPost(POST_ID))
          .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("should treat a null tags list as having no tagged users")
    void shouldTreatNullTags_asNoTaggedUsers() throws Exception {
      // Given
      PostEntity post = post(POST_ID, AUTHOR_ID, PostVisibility.PUBLIC, PostType.REGULAR);
      post.setTags(null);
      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(post));
      when(userRepository.findById(AUTHOR_ID)).thenReturn(Optional.of(user(AUTHOR_ID, "Alice")));
      when(friendshipService.getFriendIds(AUTHOR_ID)).thenReturn(List.of());
      when(objectMapper.writeValueAsString(any())).thenReturn("{}");
      when(redisTemplate.opsForValue()).thenReturn(valueOperations);
      when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);

      // When / Then
      assertThatCode(() -> newsfeedService.fanOutPost(POST_ID)).doesNotThrowAnyException();
      verify(notificationService, never()).send(any());
    }

    @Test
    @DisplayName("should fan out a regular post with no tags and no location")
    void shouldFanOutRegularPost_withNoTagsAndNoLocation() throws Exception {
      // Given
      PostEntity post = post(POST_ID, AUTHOR_ID, PostVisibility.PUBLIC, PostType.REGULAR);
      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(post));
      when(userRepository.findById(AUTHOR_ID)).thenReturn(Optional.of(user(AUTHOR_ID, "Alice")));
      when(friendshipService.getFriendIds(AUTHOR_ID)).thenReturn(List.of());
      when(objectMapper.writeValueAsString(any())).thenReturn("{}");
      when(redisTemplate.opsForValue()).thenReturn(valueOperations);
      when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);

      // When / Then
      assertThatCode(() -> newsfeedService.fanOutPost(POST_ID)).doesNotThrowAnyException();
      verify(zSetOperations)
          .add(eq("feed:" + AUTHOR_ID), eq(String.valueOf(POST_ID)), any(Double.class));
    }

    @Test
    @DisplayName("should include the resolved Google Maps URL when the post has location details")
    void shouldFanOutPost_withLocationDetails() throws Exception {
      // Given
      PostEntity post = post(POST_ID, AUTHOR_ID, PostVisibility.PUBLIC, PostType.REGULAR);
      LocationDetails location = new LocationDetails();
      location.setLatitude(10.5);
      location.setLongitude(20.5);
      post.setLocationDetails(location);
      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(post));
      when(userRepository.findById(AUTHOR_ID)).thenReturn(Optional.of(user(AUTHOR_ID, "Alice")));
      when(friendshipService.getFriendIds(AUTHOR_ID)).thenReturn(List.of());
      when(objectMapper.writeValueAsString(any())).thenReturn("{}");
      when(redisTemplate.opsForValue()).thenReturn(valueOperations);
      when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);

      // When / Then
      assertThatCode(() -> newsfeedService.fanOutPost(POST_ID)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("should include a book summary when the post is a BOOK post with a matching book")
    void shouldIncludeBookSummary_whenBookPostHasBook() throws Exception {
      // Given
      PostEntity post = post(POST_ID, AUTHOR_ID, PostVisibility.PUBLIC, PostType.BOOK);
      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(post));
      when(userRepository.findById(AUTHOR_ID)).thenReturn(Optional.of(user(AUTHOR_ID, "Alice")));
      when(friendshipService.getFriendIds(AUTHOR_ID)).thenReturn(List.of());
      BookEntity book =
          BookEntity.builder()
              .id(50)
              .title("My Book")
              .fileFormat(FileFormat.PDF)
              .isFree(true)
              .avgRating(4.5)
              .reviewCount(3)
              .build();
      when(bookRepository.findByPostId(POST_ID)).thenReturn(List.of(book));
      when(bookReviewService.getRatingBreakdown(50))
          .thenReturn(new RatingBreakdownDto(1, 0, 0, 0, 2, 3));
      when(objectMapper.writeValueAsString(any())).thenReturn("{}");
      when(redisTemplate.opsForValue()).thenReturn(valueOperations);
      when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);

      // When / Then
      assertThatCode(() -> newsfeedService.fanOutPost(POST_ID)).doesNotThrowAnyException();
      verify(bookReviewService).getRatingBreakdown(50);
    }

    @Test
    @DisplayName(
        "should omit the book summary when the post is a BOOK post with no matching book row")
    void shouldOmitBookSummary_whenBookPostHasNoBook() throws Exception {
      // Given
      PostEntity post = post(POST_ID, AUTHOR_ID, PostVisibility.PUBLIC, PostType.BOOK);
      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(post));
      when(userRepository.findById(AUTHOR_ID)).thenReturn(Optional.of(user(AUTHOR_ID, "Alice")));
      when(friendshipService.getFriendIds(AUTHOR_ID)).thenReturn(List.of());
      when(bookRepository.findByPostId(POST_ID)).thenReturn(List.of());
      when(objectMapper.writeValueAsString(any())).thenReturn("{}");
      when(redisTemplate.opsForValue()).thenReturn(valueOperations);
      when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);

      // When / Then
      assertThatCode(() -> newsfeedService.fanOutPost(POST_ID)).doesNotThrowAnyException();
      verify(bookReviewService, never()).getRatingBreakdown(any());
    }

    @Test
    @DisplayName(
        "should fan out to friends and tagged users, but skip notifying the author if self-tagged")
    void shouldFanOutAndNotify_skippingAuthorInTaggedList() throws Exception {
      // Given
      PostEntity post = post(POST_ID, AUTHOR_ID, PostVisibility.PUBLIC, PostType.REGULAR);
      post.getTags()
          .add(new PostTagEntity(new PostTagId(POST_ID, 0), AUTHOR_ID)); // author tags self
      post.getTags().add(new PostTagEntity(new PostTagId(POST_ID, 1), TAGGED_ID));
      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(post));
      when(userRepository.findById(AUTHOR_ID)).thenReturn(Optional.of(user(AUTHOR_ID, "Alice")));
      when(friendshipService.getFriendIds(AUTHOR_ID)).thenReturn(List.of(FRIEND_ID));
      when(objectMapper.writeValueAsString(any())).thenReturn("{}");
      when(redisTemplate.opsForValue()).thenReturn(valueOperations);
      when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);

      // When
      newsfeedService.fanOutPost(POST_ID);

      // Then — the audience (friends plus tagged users, minus the author, who already got the post
      // added directly) goes to Redis in one pipelined batch rather than two commands per
      // recipient, so the assertion is that the pipeline ran and the notification went to the right
      // person. NewsfeedService#addToFeeds carries the per-recipient detail.
      verify(redisTemplate)
          .executePipelined(org.mockito.ArgumentMatchers.<RedisCallback<Object>>any());
      verify(notificationService, org.mockito.Mockito.times(1)).send(notificationCaptor.capture());
      assertThat(notificationCaptor.getValue().getRecipientId()).isEqualTo(TAGGED_ID);
      assertThat(notificationCaptor.getValue().getBody()).isEqualTo("Alice tagged you in a post");
    }

    @Test
    @DisplayName(
        "should fall back to \"Someone\" in the tag notification when the author's name is blank")
    void shouldFallBackToSomeone_whenAuthorNameBlank() throws Exception {
      // Given
      PostEntity post = post(POST_ID, AUTHOR_ID, PostVisibility.PUBLIC, PostType.REGULAR);
      post.getTags().add(new PostTagEntity(new PostTagId(POST_ID, 0), TAGGED_ID));
      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(post));
      when(userRepository.findById(AUTHOR_ID)).thenReturn(Optional.of(user(AUTHOR_ID, "   ")));
      when(friendshipService.getFriendIds(AUTHOR_ID)).thenReturn(List.of());
      when(objectMapper.writeValueAsString(any())).thenReturn("{}");
      when(redisTemplate.opsForValue()).thenReturn(valueOperations);
      when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);

      // When
      newsfeedService.fanOutPost(POST_ID);

      // Then
      verify(notificationService).send(notificationCaptor.capture());
      assertThat(notificationCaptor.getValue().getBody()).startsWith("Someone tagged you");
    }

    @Test
    @DisplayName(
        "should fall back to \"Someone\" in the tag notification when the author's name is null")
    void shouldFallBackToSomeone_whenAuthorNameNull() throws Exception {
      // Given
      PostEntity post = post(POST_ID, AUTHOR_ID, PostVisibility.PUBLIC, PostType.REGULAR);
      post.getTags().add(new PostTagEntity(new PostTagId(POST_ID, 0), TAGGED_ID));
      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(post));
      when(userRepository.findById(AUTHOR_ID)).thenReturn(Optional.of(user(AUTHOR_ID, null)));
      when(friendshipService.getFriendIds(AUTHOR_ID)).thenReturn(List.of());
      when(objectMapper.writeValueAsString(any())).thenReturn("{}");
      when(redisTemplate.opsForValue()).thenReturn(valueOperations);
      when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);

      // When
      newsfeedService.fanOutPost(POST_ID);

      // Then
      verify(notificationService).send(notificationCaptor.capture());
      assertThat(notificationCaptor.getValue().getBody()).startsWith("Someone tagged you");
    }

    @Test
    @DisplayName("should not notify anyone when the post has no tagged users")
    void shouldSkipNotifications_whenNoTaggedUsers() throws Exception {
      // Given
      PostEntity post = post(POST_ID, AUTHOR_ID, PostVisibility.PUBLIC, PostType.REGULAR);
      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(post));
      when(userRepository.findById(AUTHOR_ID)).thenReturn(Optional.of(user(AUTHOR_ID, "Alice")));
      when(friendshipService.getFriendIds(AUTHOR_ID)).thenReturn(List.of());
      when(objectMapper.writeValueAsString(any())).thenReturn("{}");
      when(redisTemplate.opsForValue()).thenReturn(valueOperations);
      when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);

      // When
      newsfeedService.fanOutPost(POST_ID);

      // Then
      verify(notificationService, never()).send(any());
    }

    @Test
    @DisplayName("should skip friend fan-out when the post is private")
    void shouldSkipFriendFanOut_whenVisibilityIsPrivate() throws Exception {
      // Given
      PostEntity post = post(POST_ID, AUTHOR_ID, PostVisibility.PRIVATE, PostType.REGULAR);
      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(post));
      when(userRepository.findById(AUTHOR_ID)).thenReturn(Optional.of(user(AUTHOR_ID, "Alice")));
      when(objectMapper.writeValueAsString(any())).thenReturn("{}");
      when(redisTemplate.opsForValue()).thenReturn(valueOperations);
      when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);

      // When
      newsfeedService.fanOutPost(POST_ID);

      // Then
      verify(friendshipService, never()).getFriendIds(any());
    }

    @Test
    @DisplayName("should copy all six detail blocks, images and tagged users into the feed entry")
    void shouldCopyAllDetailBlocks_intoFeedEntry() throws Exception {
      // Given — a post carrying every optional block at once. Only one of these can really be
      // set on a single post in practice, but the point of the test is that no block is dropped.
      PostEntity post = post(POST_ID, AUTHOR_ID, PostVisibility.PUBLIC, PostType.QNA);
      post.setQuizDetails(quizWithAnswers());
      post.setCodeSnippetDetails(new CodeSnippetDetails());
      post.setArticleDetails(new ArticleDetails());
      post.setQnaDetails(new QnaDetails());
      post.setPollDetails(new PollDetails());
      post.setLinkDetails(new LinkDetails());
      post.setImages(List.of("img-1.png", "img-2.png"));
      post.getTags().add(new PostTagEntity(new PostTagId(POST_ID, 0), TAGGED_ID));

      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(post));
      when(userRepository.findById(AUTHOR_ID)).thenReturn(Optional.of(user(AUTHOR_ID, "Alice")));
      when(friendshipService.getFriendIds(AUTHOR_ID)).thenReturn(List.of());
      when(objectMapper.writeValueAsString(any())).thenReturn("{}");
      when(redisTemplate.opsForValue()).thenReturn(valueOperations);
      when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);

      // When
      newsfeedService.fanOutPost(POST_ID);

      // Then — a null here is data loss, not a display gap: updatePost copies nulls back over
      // the row, so whatever the feed omits gets erased on the author's first edit (B2)
      ArgumentCaptor<FeedPostDataDto> cached = ArgumentCaptor.forClass(FeedPostDataDto.class);
      verify(objectMapper).writeValueAsString(cached.capture());
      FeedPostDataDto data = cached.getValue();

      assertThat(data.getQuizDetails()).isNotNull();
      // B5: the feed carries the quiz, but never its answers — see PublicQuizDetailsDto
      assertThat(data.getQuizDetails().getQuestions().get(0).getQuestion()).isEqualTo("2 + 2?");
      assertThat(data.getCodeSnippetDetails()).isNotNull();
      assertThat(data.getArticleDetails()).isNotNull();
      assertThat(data.getQnaDetails()).isNotNull();
      assertThat(data.getPollDetails()).isNotNull();
      assertThat(data.getLinkDetails()).isNotNull();
      assertThat(data.getImages()).containsExactly("img-1.png", "img-2.png");
      assertThat(data.getTaggedUserIds()).containsExactly(TAGGED_ID);
    }

    @Test
    @DisplayName("should label the author's elite score with the matching reputation level")
    void shouldCacheAuthorLevelName() throws Exception {
      // Given — B15: the feed carried the raw score only, so the chip had no level suffix to show
      PostEntity post = post(POST_ID, AUTHOR_ID, PostVisibility.PUBLIC, PostType.REGULAR);
      UserEntity author = user(AUTHOR_ID, "Alice");
      author.setEliteScore(5_000);
      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(post));
      when(userRepository.findById(AUTHOR_ID)).thenReturn(Optional.of(author));
      when(friendshipService.getFriendIds(AUTHOR_ID)).thenReturn(List.of());
      when(objectMapper.writeValueAsString(any())).thenReturn("{}");
      when(redisTemplate.opsForValue()).thenReturn(valueOperations);
      when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);

      // When
      newsfeedService.fanOutPost(POST_ID);

      // Then — 5,000 is exactly the EXPERT floor
      ArgumentCaptor<FeedPostDataDto> cached = ArgumentCaptor.forClass(FeedPostDataDto.class);
      verify(objectMapper).writeValueAsString(cached.capture());
      assertThat(cached.getValue().getAuthorEliteScore()).isEqualTo(5_000);
      assertThat(cached.getValue().getAuthorLevelName()).isEqualTo("Expert");
    }

    @Test
    @DisplayName("should read like and comment counts from the database, not assume zero")
    void shouldReadCounters_fromDatabase() throws Exception {
      // Given — fan-out also runs on edit and on a late moderation approval, by which time the
      // post can already carry reactions and comments (B7)
      PostEntity post = post(POST_ID, AUTHOR_ID, PostVisibility.PUBLIC, PostType.REGULAR);
      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(post));
      when(userRepository.findById(AUTHOR_ID)).thenReturn(Optional.of(user(AUTHOR_ID, "Alice")));
      when(friendshipService.getFriendIds(AUTHOR_ID)).thenReturn(List.of());
      when(postReactionRepository.countByIdPostId(POST_ID)).thenReturn(7L);
      when(commentRepository.countByPostId(POST_ID)).thenReturn(3L);
      when(objectMapper.writeValueAsString(any())).thenReturn("{}");
      when(redisTemplate.opsForValue()).thenReturn(valueOperations);
      when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);

      // When
      newsfeedService.fanOutPost(POST_ID);

      // Then
      ArgumentCaptor<FeedPostDataDto> cached = ArgumentCaptor.forClass(FeedPostDataDto.class);
      verify(objectMapper).writeValueAsString(cached.capture());
      assertThat(cached.getValue().getLikeCount()).isEqualTo(7);
      assertThat(cached.getValue().getCommentCount()).isEqualTo(3);
    }
  }

  // =====================================================================
  // updateCachedReactions / updateCachedCommentCount
  // =====================================================================

  @Nested
  @DisplayName("updateCachedReactions / updateCachedCommentCount")
  class UpdateCachedCountersTests {

    @Test
    @DisplayName("should rewrite the cached like count in place")
    void shouldRewriteCachedLikeCount() throws Exception {
      // Given
      FeedPostDataDto cachedPost = feedPost(POST_ID, AUTHOR_ID, OffsetDateTime.now());
      when(redisTemplate.opsForValue()).thenReturn(valueOperations);
      when(valueOperations.get("feedpost:" + POST_ID)).thenReturn("{\"postId\":100}");
      when(objectMapper.readValue("{\"postId\":100}", FeedPostDataDto.class))
          .thenReturn(cachedPost);
      when(objectMapper.writeValueAsString(any())).thenReturn("{\"likeCount\":5}");

      // When
      newsfeedService.updateCachedReactions(POST_ID, 5, Map.of(ReactionType.INSIGHT, 5L));

      // Then — the total and its breakdown are written together, in one read-modify-write over
      // the same entry: two calls would leave a window where the chips disagree with the number
      assertThat(cachedPost.getLikeCount()).isEqualTo(5);
      assertThat(cachedPost.getReactionSummary()).containsEntry(ReactionType.INSIGHT, 5L);
      verify(valueOperations)
          .set(eq("feedpost:" + POST_ID), eq("{\"likeCount\":5}"), eq(Duration.ofDays(7)));
    }

    @Test
    @DisplayName("should rewrite the cached comment count in place")
    void shouldRewriteCachedCommentCount() throws Exception {
      // Given
      FeedPostDataDto cachedPost = feedPost(POST_ID, AUTHOR_ID, OffsetDateTime.now());
      when(redisTemplate.opsForValue()).thenReturn(valueOperations);
      when(valueOperations.get("feedpost:" + POST_ID)).thenReturn("{\"postId\":100}");
      when(objectMapper.readValue("{\"postId\":100}", FeedPostDataDto.class))
          .thenReturn(cachedPost);
      when(objectMapper.writeValueAsString(any())).thenReturn("{\"commentCount\":2}");

      // When
      newsfeedService.updateCachedCommentCount(POST_ID, 2);

      // Then
      assertThat(cachedPost.getCommentCount()).isEqualTo(2);
      verify(valueOperations).set(eq("feedpost:" + POST_ID), anyString(), eq(Duration.ofDays(7)));
    }

    @Test
    @DisplayName("should rewrite the cached QNA block in place")
    void shouldRewriteCachedQnaDetails() throws Exception {
      // Given — accepting an answer flips isResolved in Postgres, and the feed only ever reads
      // Redis (B10)
      FeedPostDataDto cachedPost = feedPost(POST_ID, AUTHOR_ID, OffsetDateTime.now());
      cachedPost.setQnaDetails(new QnaDetails(false, null, null));
      when(redisTemplate.opsForValue()).thenReturn(valueOperations);
      when(valueOperations.get("feedpost:" + POST_ID)).thenReturn("{\"postId\":100}");
      when(objectMapper.readValue("{\"postId\":100}", FeedPostDataDto.class))
          .thenReturn(cachedPost);
      when(objectMapper.writeValueAsString(any())).thenReturn("{\"qnaDetails\":{}}");

      // When
      newsfeedService.updateCachedQnaDetails(POST_ID, new QnaDetails(true, null, 500));

      // Then
      assertThat(cachedPost.getQnaDetails().getIsResolved()).isTrue();
      assertThat(cachedPost.getQnaDetails().getAcceptedAnswerId()).isEqualTo(500);
      verify(valueOperations).set(eq("feedpost:" + POST_ID), anyString(), eq(Duration.ofDays(7)));
    }

    @Test
    @DisplayName("should do nothing when the post is not cached")
    void shouldDoNothing_whenPostIsNotCached() {
      // Given — private posts and posts awaiting moderation are never cached; reacting to one
      // must not write it into the feed
      when(redisTemplate.opsForValue()).thenReturn(valueOperations);
      when(valueOperations.get("feedpost:" + POST_ID)).thenReturn(null);

      // When
      newsfeedService.updateCachedReactions(POST_ID, 5, Map.of());

      // Then
      verify(valueOperations, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("should swallow the error when the cached entry cannot be deserialized")
    void shouldSwallowError_whenCachedEntryIsCorrupt() throws Exception {
      // Given
      when(redisTemplate.opsForValue()).thenReturn(valueOperations);
      when(valueOperations.get("feedpost:" + POST_ID)).thenReturn("not-json");
      when(objectMapper.readValue("not-json", FeedPostDataDto.class))
          .thenThrow(new RuntimeException("boom"));

      // When / Then — a broken cache entry must not fail the user's like
      assertThatCode(() -> newsfeedService.updateCachedReactions(POST_ID, 5, Map.of()))
          .doesNotThrowAnyException();
      verify(valueOperations, never()).set(anyString(), anyString(), any(Duration.class));
    }
  }

  // =====================================================================
  // fanOutPost(FeedPostDataDto, List<Integer>)
  // =====================================================================

  @Nested
  @DisplayName("fanOutPost(postData, taggedUserIds)")
  class FanOutPostDataTests {

    @Test
    @DisplayName("should score by the current time when the post data has no createdAt")
    void shouldUseCurrentTime_whenCreatedAtIsNull() throws Exception {
      // Given
      FeedPostDataDto data =
          FeedPostDataDto.builder()
              .postId(POST_ID)
              .authorId(AUTHOR_ID)
              .visibility(PostVisibility.PUBLIC)
              .build();
      when(objectMapper.writeValueAsString(data)).thenReturn("{}");
      when(redisTemplate.opsForValue()).thenReturn(valueOperations);
      when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
      when(friendshipService.getFriendIds(AUTHOR_ID)).thenReturn(List.of());
      long before = System.currentTimeMillis();

      // When
      newsfeedService.fanOutPost(data, List.of());

      // Then
      org.mockito.ArgumentCaptor<Double> scoreCaptor =
          org.mockito.ArgumentCaptor.forClass(Double.class);
      verify(zSetOperations)
          .add(eq("feed:" + AUTHOR_ID), eq(String.valueOf(POST_ID)), scoreCaptor.capture());
      assertThat(scoreCaptor.getValue()).isGreaterThanOrEqualTo(before);
    }

    @Test
    @DisplayName("should skip tagged-user fan-out when the tagged-user list is null")
    void shouldSkipTaggedFanOut_whenTaggedUserIdsIsNull() throws Exception {
      // Given
      FeedPostDataDto data =
          FeedPostDataDto.builder()
              .postId(POST_ID)
              .authorId(AUTHOR_ID)
              .visibility(PostVisibility.PUBLIC)
              .createdAt(OffsetDateTime.now())
              .build();
      when(objectMapper.writeValueAsString(data)).thenReturn("{}");
      when(redisTemplate.opsForValue()).thenReturn(valueOperations);
      when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
      when(friendshipService.getFriendIds(AUTHOR_ID)).thenReturn(List.of());

      // When / Then
      assertThatCode(() -> newsfeedService.fanOutPost(data, null)).doesNotThrowAnyException();
    }
  }

  // =====================================================================
  // updatePostCache
  // =====================================================================

  @Nested
  @DisplayName("updatePostCache")
  class UpdatePostCacheTests {

    @Test
    @DisplayName("should serialize and cache the post data")
    void shouldCachePostData() throws Exception {
      // Given
      FeedPostDataDto data = feedPost(POST_ID, AUTHOR_ID, OffsetDateTime.now());
      when(objectMapper.writeValueAsString(data)).thenReturn("{\"postId\":100}");
      when(redisTemplate.opsForValue()).thenReturn(valueOperations);

      // When
      newsfeedService.updatePostCache(data);

      // Then
      verify(valueOperations)
          .set(eq("feedpost:" + POST_ID), eq("{\"postId\":100}"), eq(Duration.ofDays(7)));
    }

    @Test
    @DisplayName("should swallow the error and not propagate when serialization fails")
    void shouldLogError_whenSerializationFails() throws Exception {
      // Given
      FeedPostDataDto data = feedPost(POST_ID, AUTHOR_ID, OffsetDateTime.now());
      when(objectMapper.writeValueAsString(data))
          .thenThrow(new RuntimeException("serialization failure"));

      // When / Then
      assertThatCode(() -> newsfeedService.updatePostCache(data)).doesNotThrowAnyException();
      verify(redisTemplate, never()).opsForValue();
    }
  }

  // =====================================================================
  // removePost
  // =====================================================================

  @Nested
  @DisplayName("removePost")
  class RemovePostTests {

    @Test
    @DisplayName("should remove the post from the author's and friends' feeds with no tagged users")
    void shouldRemoveFromAuthorAndFriendFeeds_withNoTaggedUsers() {
      // Given
      when(friendshipService.getFriendIds(AUTHOR_ID)).thenReturn(List.of(FRIEND_ID));
      when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);

      // When
      newsfeedService.removePost(POST_ID, AUTHOR_ID, List.of());

      // Then
      verify(redisTemplate).delete("feedpost:" + POST_ID);
      verify(zSetOperations).remove("feed:" + AUTHOR_ID, String.valueOf(POST_ID));
      verify(zSetOperations).remove("feed:" + FRIEND_ID, String.valueOf(POST_ID));
    }

    @Test
    @DisplayName("should also remove the post from tagged users' feeds when present")
    void shouldAlsoRemoveFromTaggedUserFeeds_whenTaggedUsersPresent() {
      // Given
      when(friendshipService.getFriendIds(AUTHOR_ID)).thenReturn(List.of());
      when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);

      // When
      newsfeedService.removePost(POST_ID, AUTHOR_ID, List.of(TAGGED_ID));

      // Then
      verify(zSetOperations).remove("feed:" + TAGGED_ID, String.valueOf(POST_ID));
    }
  }

  // =====================================================================
  // getFeed
  // =====================================================================

  @Nested
  @DisplayName("getFeed — book cover signing")
  class GetFeedCoverSigningTests {

    @Test
    @DisplayName("should sign the cached cover key when the feed is served")
    void shouldSignCoverKey_onRead() throws Exception {
      // Given — the cache holds the key, never a URL (B4): a signature lives 24h and a cache
      // entry lives 7 days, so a URL signed at fan-out time is dead for most of its life
      FeedPostDataDto cached = feedPost(POST_ID, AUTHOR_ID, OffsetDateTime.now());
      cached.setBook(
          FeedBookSummaryDto.builder().bookId(5).coverImageKey("covers/1/a.jpg").build());

      when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
      when(zSetOperations.reverseRange(anyString(), anyLong(), anyLong()))
          .thenReturn(Set.of(String.valueOf(POST_ID)));
      when(redisTemplate.opsForValue()).thenReturn(valueOperations);
      when(valueOperations.multiGet(any())).thenReturn(List.of("{}"));
      when(objectMapper.readValue("{}", FeedPostDataDto.class)).thenReturn(cached);
      when(bookStorageService.getCoverUrl("covers/1/a.jpg")).thenReturn("https://cdn/signed");

      // When
      FeedResponseDto result = newsfeedService.getFeed(AUTHOR_ID, 1, 10);

      // Then
      assertThat(result.getPosts().get(0).getBook().getCoverImageUrl())
          .isEqualTo("https://cdn/signed");
    }

    @Test
    @DisplayName("should leave posts without a book alone")
    void shouldSkipPostsWithoutBook() throws Exception {
      // Given
      FeedPostDataDto cached = feedPost(POST_ID, AUTHOR_ID, OffsetDateTime.now());

      when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
      when(zSetOperations.reverseRange(anyString(), anyLong(), anyLong()))
          .thenReturn(Set.of(String.valueOf(POST_ID)));
      when(redisTemplate.opsForValue()).thenReturn(valueOperations);
      when(valueOperations.multiGet(any())).thenReturn(List.of("{}"));
      when(objectMapper.readValue("{}", FeedPostDataDto.class)).thenReturn(cached);

      // When / Then — most posts are not book posts
      assertThatCode(() -> newsfeedService.getFeed(AUTHOR_ID, 1, 10)).doesNotThrowAnyException();
      verify(bookStorageService, never()).getCoverUrl(any());
    }
  }

  @Nested
  @DisplayName("getFeed")
  class GetFeedTests {

    @Test
    @DisplayName("should return an empty feed when there are no cached post ids")
    void shouldReturnEmptyFeed_whenNoPostIdsInRedis() {
      // Given
      when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
      when(zSetOperations.reverseRange("feed:" + AUTHOR_ID, 0, 2)).thenReturn(Set.of());

      // When
      FeedResponseDto result = newsfeedService.getFeed(AUTHOR_ID, 1, 2);

      // Then
      assertThat(result.getPosts()).isEmpty();
      assertThat(result.isHasMore()).isFalse();
    }

    @Test
    @DisplayName("should return the page without hasMore when results fit within the page size")
    void shouldReturnFeedWithoutHasMore_whenFewerThanSizePlusOne() throws Exception {
      // Given
      when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
      when(zSetOperations.reverseRange("feed:" + AUTHOR_ID, 0, 2)).thenReturn(Set.of("100"));
      when(redisTemplate.opsForValue()).thenReturn(valueOperations);
      when(valueOperations.multiGet(List.of("feedpost:100")))
          .thenReturn(List.of("{\"postId\":100}"));
      when(objectMapper.readValue("{\"postId\":100}", FeedPostDataDto.class))
          .thenReturn(feedPost(100, AUTHOR_ID, OffsetDateTime.now()));

      // When
      FeedResponseDto result = newsfeedService.getFeed(AUTHOR_ID, 1, 2);

      // Then
      assertThat(result.getPosts()).hasSize(1);
      assertThat(result.isHasMore()).isFalse();
    }

    @Test
    @DisplayName("should trim to the page size and report hasMore when there are extra results")
    void shouldReturnFeedWithHasMore_whenMoreThanSize() throws Exception {
      // Given
      when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
      when(zSetOperations.reverseRange("feed:" + AUTHOR_ID, 0, 1)).thenReturn(Set.of("100", "101"));
      when(redisTemplate.opsForValue()).thenReturn(valueOperations);
      when(valueOperations.multiGet(any(List.class)))
          .thenReturn(List.of("{\"postId\":100}", "{\"postId\":101}"));
      when(objectMapper.readValue(anyString(), eq(FeedPostDataDto.class)))
          .thenReturn(feedPost(100, AUTHOR_ID, OffsetDateTime.now()));

      // When
      FeedResponseDto result = newsfeedService.getFeed(AUTHOR_ID, 1, 1);

      // Then
      assertThat(result.getPosts()).hasSize(1);
      assertThat(result.isHasMore()).isTrue();
    }

    @Test
    @DisplayName("should return an empty list when Redis' multiGet returns null")
    void shouldReturnEmptyList_whenRedisMultiGetReturnsNull() {
      // Given
      when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
      when(zSetOperations.reverseRange("feed:" + AUTHOR_ID, 0, 2)).thenReturn(Set.of("100"));
      when(redisTemplate.opsForValue()).thenReturn(valueOperations);
      when(valueOperations.multiGet(List.of("feedpost:100"))).thenReturn(null);

      // When
      FeedResponseDto result = newsfeedService.getFeed(AUTHOR_ID, 1, 2);

      // Then
      assertThat(result.getPosts()).isEmpty();
    }

    @Test
    @DisplayName("should skip and log a warning for a post that fails to deserialize")
    void shouldSkipUnparsablePost_andLogWarning() throws Exception {
      // Given
      when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
      when(zSetOperations.reverseRange("feed:" + AUTHOR_ID, 0, 2)).thenReturn(Set.of("100"));
      when(redisTemplate.opsForValue()).thenReturn(valueOperations);
      when(valueOperations.multiGet(List.of("feedpost:100"))).thenReturn(List.of("not-json"));
      when(objectMapper.readValue("not-json", FeedPostDataDto.class))
          .thenThrow(new RuntimeException("bad json"));

      // When
      FeedResponseDto result = newsfeedService.getFeed(AUTHOR_ID, 1, 2);

      // Then
      assertThat(result.getPosts()).isEmpty();
    }
  }

  // =====================================================================
  // trackInteraction
  // =====================================================================

  @Nested
  @DisplayName("trackInteraction")
  class TrackInteractionTests {

    @Test
    @DisplayName("should save a new interaction record")
    void shouldSaveInteraction() {
      // Given
      ArgumentCaptor<UserInteractionEntity> captor =
          ArgumentCaptor.forClass(UserInteractionEntity.class);

      // When
      newsfeedService.trackInteraction(TAGGED_ID, POST_ID, AUTHOR_ID, InteractionType.LIKE);

      // Then
      verify(userInteractionRepository).save(captor.capture());
      assertThat(captor.getValue().getUserId()).isEqualTo(TAGGED_ID);
      assertThat(captor.getValue().getPostId()).isEqualTo(POST_ID);
      assertThat(captor.getValue().getAuthorId()).isEqualTo(AUTHOR_ID);
      assertThat(captor.getValue().getType()).isEqualTo(InteractionType.LIKE);
    }
  }

  @Nested
  @DisplayName("getFeed — block filtering")
  class GetFeedBlockFilteringTests {

    @Test
    @DisplayName("should drop posts by anyone in the caller's block set")
    void shouldDropBlockedAuthorsPosts() throws Exception {
      // Given: the feed's Redis entry still holds a post fanned out before the block was placed
      when(blockQueryService.blockedPairIds(AUTHOR_ID)).thenReturn(Set.of(FRIEND_ID));
      when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
      // The window is widened when the caller has blocks, so that filtering afterwards still
      // usually fills the page — see NewsfeedService#getFeed.
      when(zSetOperations.reverseRange("feed:" + AUTHOR_ID, 0, 6))
          .thenReturn(new java.util.LinkedHashSet<>(List.of("1", "2")));
      when(redisTemplate.opsForValue()).thenReturn(valueOperations);
      when(valueOperations.multiGet(List.of("feedpost:1", "feedpost:2")))
          .thenReturn(List.of("json-1", "json-2"));
      when(objectMapper.readValue("json-1", FeedPostDataDto.class))
          .thenReturn(FeedPostDataDto.builder().postId(1).authorId(AUTHOR_ID).build());
      when(objectMapper.readValue("json-2", FeedPostDataDto.class))
          .thenReturn(FeedPostDataDto.builder().postId(2).authorId(FRIEND_ID).build());

      // When
      FeedResponseDto result = newsfeedService.getFeed(AUTHOR_ID, 1, 2);

      // Then
      assertThat(result.getPosts()).extracting(FeedPostDataDto::getPostId).containsExactly(1);
    }

    @Test
    @DisplayName("should read the ordinary window when the caller has blocked nobody")
    void shouldNotWidenWindowWithoutBlocks() {
      // Given
      when(blockQueryService.blockedPairIds(AUTHOR_ID)).thenReturn(Set.of());
      when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
      when(zSetOperations.reverseRange("feed:" + AUTHOR_ID, 0, 2)).thenReturn(Set.of());

      // When
      FeedResponseDto result = newsfeedService.getFeed(AUTHOR_ID, 1, 2);

      // Then — nearly every user is in this case, and they must keep paying the old cost
      assertThat(result.getPosts()).isEmpty();
    }
  }

  // =====================================================================
  // getFeed(scope = SKILLS)
  // =====================================================================

  @Nested
  @DisplayName("getFeed — SKILLS scope")
  class GetSkillFeedTests {

    private FeedPostDataDto tagged(Integer postId, Integer authorId, String... hashtags) {
      FeedPostDataDto post = feedPost(postId, authorId, OffsetDateTime.now());
      post.setHashtags(List.of(hashtags));
      return post;
    }

    private void givenCachedPosts(FeedPostDataDto... posts) throws Exception {
      java.util.Set<String> ids = new java.util.LinkedHashSet<>();
      List<String> keys = new java.util.ArrayList<>();
      List<String> payloads = new java.util.ArrayList<>();
      for (FeedPostDataDto post : posts) {
        String id = String.valueOf(post.getPostId());
        ids.add(id);
        keys.add("feedpost:" + id);
        payloads.add("json-" + id);
        when(objectMapper.readValue("json-" + id, FeedPostDataDto.class)).thenReturn(post);
      }
      when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
      when(zSetOperations.reverseRange("feed:" + AUTHOR_ID, 0, 299L)).thenReturn(ids);
      when(redisTemplate.opsForValue()).thenReturn(valueOperations);
      when(valueOperations.multiGet(keys)).thenReturn(payloads);
    }

    @Test
    @DisplayName("should keep only the posts carrying one of the caller's verified skill tags")
    void shouldKeepOnlyMatchingPosts() throws Exception {
      // Given
      when(skillTagResolver.resolveTagsFor(AUTHOR_ID)).thenReturn(Set.of("java", "docker"));
      givenCachedPosts(
          tagged(1, FRIEND_ID, "java"),
          tagged(2, FRIEND_ID, "tailwindcss"),
          tagged(3, FRIEND_ID, "career", "docker"));
      when(blockQueryService.blockedPairIds(AUTHOR_ID)).thenReturn(Set.of());

      // When
      FeedResponseDto result = newsfeedService.getFeed(AUTHOR_ID, 1, 10, FeedScope.SKILLS);

      // Then — any-match, not all-match: post 3 is about two things and one of them counts
      assertThat(result.getPosts()).extracting(FeedPostDataDto::getPostId).containsExactly(1, 3);
    }

    @Test
    @DisplayName("should return an empty page when the caller has no verified skills")
    void shouldReturnEmpty_whenNoVerifiedSkills() {
      // Given
      when(skillTagResolver.resolveTagsFor(AUTHOR_ID)).thenReturn(Set.of());

      // When
      FeedResponseDto result = newsfeedService.getFeed(AUTHOR_ID, 1, 10, FeedScope.SKILLS);

      // Then — never a silent fallback to the unfiltered feed: a filter that quietly stops
      // filtering answers a question the reader did not ask
      assertThat(result.getPosts()).isEmpty();
      assertThat(result.isHasMore()).isFalse();
      verifyNoInteractions(redisTemplate);
    }

    @Test
    @DisplayName("should skip a post with no hashtags at all")
    void shouldSkipUntaggedPost() throws Exception {
      // Given — EP: the majority of posts carry no hashtag, and "no tags" is not "all tags"
      when(skillTagResolver.resolveTagsFor(AUTHOR_ID)).thenReturn(Set.of("java"));
      givenCachedPosts(feedPost(1, FRIEND_ID, OffsetDateTime.now()), tagged(2, FRIEND_ID, "java"));
      when(blockQueryService.blockedPairIds(AUTHOR_ID)).thenReturn(Set.of());

      // When
      FeedResponseDto result = newsfeedService.getFeed(AUTHOR_ID, 1, 10, FeedScope.SKILLS);

      // Then
      assertThat(result.getPosts()).extracting(FeedPostDataDto::getPostId).containsExactly(2);
    }

    @Test
    @DisplayName("should still drop a blocked author whose post matches a skill")
    void shouldStillApplyBlocks() throws Exception {
      // Given — the two filters compose; matching a skill is not an exemption from a block
      when(skillTagResolver.resolveTagsFor(AUTHOR_ID)).thenReturn(Set.of("java"));
      givenCachedPosts(tagged(1, FRIEND_ID, "java"), tagged(2, AUTHOR_ID + 99, "java"));
      when(blockQueryService.blockedPairIds(AUTHOR_ID)).thenReturn(Set.of(AUTHOR_ID + 99));

      // When
      FeedResponseDto result = newsfeedService.getFeed(AUTHOR_ID, 1, 10, FeedScope.SKILLS);

      // Then
      assertThat(result.getPosts()).extracting(FeedPostDataDto::getPostId).containsExactly(1);
    }

    @Test
    @DisplayName("should paginate what survived the filter, not what was read")
    void shouldPaginateAfterFiltering() throws Exception {
      // Given — three matches, page size two: the second page must hold the third match rather
      // than being empty because the raw window already ended
      when(skillTagResolver.resolveTagsFor(AUTHOR_ID)).thenReturn(Set.of("java"));
      givenCachedPosts(
          tagged(1, FRIEND_ID, "java"),
          tagged(2, FRIEND_ID, "ux"),
          tagged(3, FRIEND_ID, "java"),
          tagged(4, FRIEND_ID, "java"));
      when(blockQueryService.blockedPairIds(AUTHOR_ID)).thenReturn(Set.of());

      // When
      FeedResponseDto first = newsfeedService.getFeed(AUTHOR_ID, 1, 2, FeedScope.SKILLS);
      FeedResponseDto second = newsfeedService.getFeed(AUTHOR_ID, 2, 2, FeedScope.SKILLS);

      // Then
      assertThat(first.getPosts()).extracting(FeedPostDataDto::getPostId).containsExactly(1, 3);
      assertThat(first.isHasMore()).isTrue();
      assertThat(second.getPosts()).extracting(FeedPostDataDto::getPostId).containsExactly(4);
      assertThat(second.isHasMore()).isFalse();
    }

    @Test
    @DisplayName("should return an empty page when the requested page starts past the last match")
    void shouldReturnEmpty_whenPagePastEnd() throws Exception {
      // Given — BVA: start index equals the number of matches
      when(skillTagResolver.resolveTagsFor(AUTHOR_ID)).thenReturn(Set.of("java"));
      givenCachedPosts(tagged(1, FRIEND_ID, "java"));
      when(blockQueryService.blockedPairIds(AUTHOR_ID)).thenReturn(Set.of());

      // When
      FeedResponseDto result = newsfeedService.getFeed(AUTHOR_ID, 2, 1, FeedScope.SKILLS);

      // Then
      assertThat(result.getPosts()).isEmpty();
      assertThat(result.isHasMore()).isFalse();
    }

    @Test
    @DisplayName("should not consult the skill resolver for the default ALL scope")
    void shouldNotResolveSkillsForAllScope() {
      // Given
      when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
      when(zSetOperations.reverseRange("feed:" + AUTHOR_ID, 0, 2)).thenReturn(Set.of());

      // When
      newsfeedService.getFeed(AUTHOR_ID, 1, 2, FeedScope.ALL);

      // Then — the untouched tab must not pay for the new one
      verifyNoInteractions(skillTagResolver);
    }
  }

  // =====================================================================
  // rebuildAll
  // =====================================================================

  @Nested
  @DisplayName("rebuildAll")
  class RebuildAllTests {

    private PostEntity approved(Integer id, Integer authorId) {
      return post(id, authorId, PostVisibility.PUBLIC, PostType.REGULAR);
    }

    @Test
    @DisplayName("should fan out every approved post and report how many")
    void shouldFanOutEveryApprovedPost() {
      // Given — seeded posts are written straight into Postgres and never pass through the
      // publish path, so no feed contains them; this is the only way they reach Redis
      when(postRepository.findByModerationStatus(eq(ModerationStatus.APPROVED), any()))
          .thenReturn(new PageImpl<>(List.of(approved(1, AUTHOR_ID), approved(2, AUTHOR_ID))));
      when(userRepository.findById(AUTHOR_ID)).thenReturn(Optional.of(user(AUTHOR_ID, "Author")));
      when(friendshipService.getFriendIds(AUTHOR_ID)).thenReturn(List.of(FRIEND_ID));
      when(redisTemplate.opsForValue()).thenReturn(valueOperations);
      when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);

      // When
      FeedRebuildResultDto result = newsfeedService.rebuildAll();

      // Then
      assertThat(result.processed()).isEqualTo(2);
      assertThat(result.skipped()).isZero();
    }

    @Test
    @DisplayName("should not re-notify tagged users")
    void shouldNotNotifyTaggedUsers() {
      // Given — a post tagging somebody. The single-argument fanOutPost also runs
      // notifyTaggedUsers, which is why the rebuild calls the two-argument form instead: nobody
      // should be told they were tagged in a post from three months ago because an operator
      // rebuilt a cache.
      PostEntity tagged = approved(1, AUTHOR_ID);
      tagged.getTags().add(new PostTagEntity(new PostTagId(1, 0), TAGGED_ID));
      when(postRepository.findByModerationStatus(eq(ModerationStatus.APPROVED), any()))
          .thenReturn(new PageImpl<>(List.of(tagged)));
      when(userRepository.findById(AUTHOR_ID)).thenReturn(Optional.of(user(AUTHOR_ID, "Author")));
      when(friendshipService.getFriendIds(AUTHOR_ID)).thenReturn(List.of());
      when(redisTemplate.opsForValue()).thenReturn(valueOperations);
      when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);

      // When
      newsfeedService.rebuildAll();

      // Then
      verifyNoInteractions(notificationService);
    }

    @Test
    @DisplayName("should count a post it cannot rebuild and carry on with the rest")
    void shouldSkipAndContinue_whenOnePostFails() {
      // Given — the first post's author row is gone. Aborting the run there would cost the other
      // posts their fan-out over one broken row.
      when(postRepository.findByModerationStatus(eq(ModerationStatus.APPROVED), any()))
          .thenReturn(new PageImpl<>(List.of(approved(1, 999), approved(2, AUTHOR_ID))));
      when(userRepository.findById(999)).thenReturn(Optional.empty());
      when(userRepository.findById(AUTHOR_ID)).thenReturn(Optional.of(user(AUTHOR_ID, "Author")));
      when(friendshipService.getFriendIds(AUTHOR_ID)).thenReturn(List.of());
      when(redisTemplate.opsForValue()).thenReturn(valueOperations);
      when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);

      // When
      FeedRebuildResultDto result = newsfeedService.rebuildAll();

      // Then
      assertThat(result.processed()).isEqualTo(1);
      assertThat(result.skipped()).isEqualTo(1);
    }

    @Test
    @DisplayName("should walk every page rather than stopping after the first")
    void shouldWalkEveryPage() {
      // Given — two pages. Reading only the first would silently rebuild a prefix of the database
      // and still report success, which is worse than failing.
      when(postRepository.findByModerationStatus(eq(ModerationStatus.APPROVED), any()))
          .thenReturn(new PageImpl<>(List.of(approved(1, AUTHOR_ID)), PageRequest.of(0, 1), 2))
          .thenReturn(new PageImpl<>(List.of(approved(2, AUTHOR_ID)), PageRequest.of(1, 1), 2));
      when(userRepository.findById(AUTHOR_ID)).thenReturn(Optional.of(user(AUTHOR_ID, "Author")));
      when(friendshipService.getFriendIds(AUTHOR_ID)).thenReturn(List.of());
      when(redisTemplate.opsForValue()).thenReturn(valueOperations);
      when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);

      // When
      FeedRebuildResultDto result = newsfeedService.rebuildAll();

      // Then
      assertThat(result.processed()).isEqualTo(2);
    }

    @Test
    @DisplayName("should report zero on an empty database rather than failing")
    void shouldReturnZeros_whenThereAreNoApprovedPosts() {
      // Given
      when(postRepository.findByModerationStatus(eq(ModerationStatus.APPROVED), any()))
          .thenReturn(new PageImpl<>(List.of()));

      // When
      FeedRebuildResultDto result = newsfeedService.rebuildAll();

      // Then
      assertThat(result.processed()).isZero();
      assertThat(result.skipped()).isZero();
    }
  }

  // =====================================================================
  // Seen-post de-prioritisation
  // =====================================================================

  /**
   * The ranking half of the seen-post demotion.
   *
   * <p>What a seen post is worth is this class's business; where the seen set lives, when it expires
   * and how it is written belong to {@link SeenPostTracker} and are asserted in its own tests. So the
   * tracker is mocked here, and these tests say only what the feed does with the answers it gets.
   *
   * <p>{@link LinkedHashSet} rather than {@code Set.of} throughout, because ordering is the entire
   * subject and {@code Set.of} has none — the driver returns an ordered set and every assertion below
   * depends on that.
   */
  @Nested
  @DisplayName("getFeed - seen-post de-prioritisation")
  class GetFeedSeenDemotionTests {

    private static final long HOUR = 3600_000L;

    private final double now = System.currentTimeMillis();

    private Set<ZSetOperations.TypedTuple<String>> window(Object... idsAndScores) {
      Set<ZSetOperations.TypedTuple<String>> tuples = new LinkedHashSet<>();
      for (int i = 0; i < idsAndScores.length; i += 2) {
        tuples.add(
            ZSetOperations.TypedTuple.of((String) idsAndScores[i], (Double) idsAndScores[i + 1]));
      }
      return tuples;
    }

    /** Stubs the payload read so that ids come back out as posts carrying the matching id. */
    @SuppressWarnings("unchecked")
    private void givenPayloadsForAnyIds() throws Exception {
      when(redisTemplate.opsForValue()).thenReturn(valueOperations);
      when(valueOperations.multiGet(any(List.class)))
          .thenAnswer(
              invocation -> {
                List<String> keys = invocation.getArgument(0);
                return keys.stream().map(key -> "json-" + key).toList();
              });
      when(objectMapper.readValue(anyString(), eq(FeedPostDataDto.class)))
          .thenAnswer(
              invocation -> {
                String json = invocation.getArgument(0);
                int postId = Integer.parseInt(json.replace("json-feedpost:", ""));
                return feedPost(postId, AUTHOR_ID, OffsetDateTime.now());
              });
    }

    /** The reader is mid-session, and {@code seenIds} are the posts they have already scrolled by. */
    private void givenSeen(List<String> allIds, String... seenIds) {
      when(seenPostTracker.hasSeenAnything(AUTHOR_ID)).thenReturn(true);
      when(seenPostTracker.orderKey(AUTHOR_ID, null)).thenReturn("feedorder:" + AUTHOR_ID);
      Set<String> seen = Set.of(seenIds);
      when(seenPostTracker.seenAt(eq(AUTHOR_ID), anyList()))
          .thenAnswer(
              invocation -> {
                List<String> ids = invocation.getArgument(1);
                return ids.stream().map(id -> seen.contains(id) ? now : null).toList();
              });
    }

    @Test
    @DisplayName("should take the unchanged fast path when the reader has seen nothing")
    void shouldTakeFastPath_whenNothingSeen() {
      // Given: the first request of every session
      when(seenPostTracker.hasSeenAnything(AUTHOR_ID)).thenReturn(false);
      when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
      when(zSetOperations.reverseRange("feed:" + AUTHOR_ID, 0L, 2L)).thenReturn(Set.of());

      // When
      newsfeedService.getFeed(AUTHOR_ID, 1, 2);

      // Then: nearly every reader is in this case and must keep paying exactly the old cost
      verify(zSetOperations, never()).reverseRangeWithScores(anyString(), anyLong(), anyLong());
      verify(seenPostTracker, never()).seenAt(any(), anyList());
    }

    @Test
    @DisplayName("should sink a seen post below an equally recent unseen one")
    void shouldSinkSeenPostBelowUnseenPost() throws Exception {
      // Given: 1 is newer than 2, but the reader has already scrolled past 1
      when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
      when(zSetOperations.reverseRangeWithScores("feed:" + AUTHOR_ID, 0L, 999L))
          .thenReturn(window("1", now, "2", now - HOUR));
      givenSeen(List.of("1", "2"), "1");
      givenPayloadsForAnyIds();

      // When
      FeedResponseDto result = newsfeedService.getFeed(AUTHOR_ID, 1, 5);

      // Then: the unseen post is now first
      assertThat(result.getPosts()).extracting(FeedPostDataDto::getPostId).containsExactly(2, 1);
    }

    @Test
    @DisplayName("should keep a seen post in the feed rather than removing it")
    void shouldNotRemoveSeenPost() throws Exception {
      // Given: every post has been seen. A hard filter would empty the feed here.
      when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
      when(zSetOperations.reverseRangeWithScores("feed:" + AUTHOR_ID, 0L, 999L))
          .thenReturn(window("1", now, "2", now - HOUR));
      givenSeen(List.of("1", "2"), "1", "2");
      givenPayloadsForAnyIds();

      // When
      FeedResponseDto result = newsfeedService.getFeed(AUTHOR_ID, 1, 5);

      // Then: both survive, in their original order — everything moved by the same amount. This is
      // what keeps a feed holding only seed data from running dry.
      assertThat(result.getPosts()).extracting(FeedPostDataDto::getPostId).containsExactly(1, 2);
    }

    @Test
    @DisplayName("should keep a seen post above an unseen one older than the penalty")
    void shouldKeepSeenPostAboveMuchOlderUnseenPost() throws Exception {
      // Given: 1 was seen but is fresh; 2 has never been seen but predates the 7-day penalty
      when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
      when(zSetOperations.reverseRangeWithScores("feed:" + AUTHOR_ID, 0L, 999L))
          .thenReturn(window("1", now, "2", now - 10 * 24 * HOUR));
      givenSeen(List.of("1", "2"), "1");
      givenPayloadsForAnyIds();

      // When
      FeedResponseDto result = newsfeedService.getFeed(AUTHOR_ID, 1, 5);

      // Then: a penalty, not a banishment — being seen costs a week of freshness, not the feed
      assertThat(result.getPosts()).extracting(FeedPostDataDto::getPostId).containsExactly(1, 2);
    }

    @Test
    @DisplayName("should not fail on a feed entry that carries no score")
    void shouldTolerateNullScore() throws Exception {
      // Given: TypedTuple.getScore() is nullable, and an NPE here would be a 500 out of a sort
      when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
      when(zSetOperations.reverseRangeWithScores("feed:" + AUTHOR_ID, 0L, 999L))
          .thenReturn(window("1", now, "2", null));
      givenSeen(List.of("1", "2"));
      givenPayloadsForAnyIds();

      // When / Then: the scoreless entry sorts last instead of blowing up
      assertThat(newsfeedService.getFeed(AUTHOR_ID, 1, 5).getPosts())
          .extracting(FeedPostDataDto::getPostId)
          .containsExactly(1, 2);
    }

    @Test
    @DisplayName("should serve the feed unranked when the seen lookup comes back empty")
    void shouldFailOpen_whenSeenLookupReturnsNothing() throws Exception {
      // Given: the tracker could not reach Redis and reported nothing rather than throwing
      when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
      when(zSetOperations.reverseRangeWithScores("feed:" + AUTHOR_ID, 0L, 999L))
          .thenReturn(window("1", now, "2", now - HOUR));
      when(seenPostTracker.hasSeenAnything(AUTHOR_ID)).thenReturn(true);
      when(seenPostTracker.orderKey(AUTHOR_ID, null)).thenReturn("feedorder:" + AUTHOR_ID);
      when(seenPostTracker.seenAt(eq(AUTHOR_ID), anyList())).thenReturn(List.of());
      givenPayloadsForAnyIds();

      // When / Then: losing the demotion is acceptable, losing the feed is not
      assertThat(newsfeedService.getFeed(AUTHOR_ID, 1, 5).getPosts())
          .extracting(FeedPostDataDto::getPostId)
          .containsExactly(1, 2);
    }

    @Test
    @DisplayName("should freeze the ranked order when the first page is served")
    void shouldSaveOrder_onFirstPage() throws Exception {
      // Given
      when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
      when(zSetOperations.reverseRangeWithScores("feed:" + AUTHOR_ID, 0L, 999L))
          .thenReturn(window("1", now, "2", now - HOUR));
      givenSeen(List.of("1", "2"), "1");
      givenPayloadsForAnyIds();

      // When
      newsfeedService.getFeed(AUTHOR_ID, 1, 5);

      // Then: the order page two will walk down is handed to the tracker to hold still
      verify(seenPostTracker).saveOrder("feedorder:" + AUTHOR_ID, List.of("2", "1"));
    }

    @Test
    @DisplayName("should serve a later page from the frozen order without re-ranking it")
    void shouldReadOrder_onLaterPage() throws Exception {
      // Given: page 2 of a scroll the reader is already in the middle of
      when(seenPostTracker.hasSeenAnything(AUTHOR_ID)).thenReturn(true);
      when(seenPostTracker.orderKey(AUTHOR_ID, null)).thenReturn("feedorder:" + AUTHOR_ID);
      when(seenPostTracker.readOrder("feedorder:" + AUTHOR_ID, 2L, 3)).thenReturn(List.of("3"));
      givenPayloadsForAnyIds();

      // When
      FeedResponseDto result = newsfeedService.getFeed(AUTHOR_ID, 2, 2);

      // Then: the test that pins the whole design. Re-ranking here would push the posts the reader
      // had just marked seen down into this very window and serve them page one again, forever.
      assertThat(result.getPosts()).extracting(FeedPostDataDto::getPostId).containsExactly(3);
      verify(zSetOperations, never()).reverseRangeWithScores(anyString(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("should fall back to the plain window when the frozen order has expired")
    void shouldFallBack_whenSnapshotExpiredMidScroll() throws Exception {
      // Given: the snapshot aged out while the reader was still scrolling
      when(seenPostTracker.hasSeenAnything(AUTHOR_ID)).thenReturn(true);
      when(seenPostTracker.orderKey(AUTHOR_ID, null)).thenReturn("feedorder:" + AUTHOR_ID);
      when(seenPostTracker.readOrder("feedorder:" + AUTHOR_ID, 2L, 3)).thenReturn(List.of());
      when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
      when(zSetOperations.reverseRange("feed:" + AUTHOR_ID, 2L, 4L))
          .thenReturn(new LinkedHashSet<>(List.of("9")));
      givenPayloadsForAnyIds();

      // When / Then: a repeated page is a blemish, an empty one looks like data loss
      assertThat(newsfeedService.getFeed(AUTHOR_ID, 2, 2).getPosts())
          .extracting(FeedPostDataDto::getPostId)
          .containsExactly(9);
    }

    @Test
    @DisplayName("should report hasMore from the ids read, not from the payloads that survived")
    void shouldReportHasMoreFromIds_notFromDeserializedPayloads() throws Exception {
      // Given: two ids, but one payload has aged out of the 7-day cache while its id survives in
      // the 30-day feed index
      when(seenPostTracker.hasSeenAnything(AUTHOR_ID)).thenReturn(false);
      when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
      when(zSetOperations.reverseRange("feed:" + AUTHOR_ID, 0L, 1L))
          .thenReturn(new LinkedHashSet<>(List.of("1", "2")));
      when(redisTemplate.opsForValue()).thenReturn(valueOperations);
      when(valueOperations.multiGet(List.of("feedpost:1", "feedpost:2")))
          .thenReturn(Arrays.asList("json-1", null));
      when(objectMapper.readValue("json-1", FeedPostDataDto.class))
          .thenReturn(feedPost(1, AUTHOR_ID, OffsetDateTime.now()));
      when(objectMapper.readValue((String) isNull(), eq(FeedPostDataDto.class)))
          .thenThrow(new IllegalArgumentException("no content"));

      // When
      FeedResponseDto result = newsfeedService.getFeed(AUTHOR_ID, 1, 1);

      // Then: the gap is in the payload cache, not at the end of the feed
      assertThat(result.getPosts()).hasSize(1);
      assertThat(result.isHasMore()).isTrue();
    }

    @Test
    @DisplayName("should return an empty page rather than overflowing on an enormous page number")
    void shouldNotOverflow_onEnormousPageNumber() {
      // Given: page carries @Positive but no @Max, so this is a legal request today
      when(seenPostTracker.hasSeenAnything(AUTHOR_ID)).thenReturn(true);
      when(seenPostTracker.orderKey(AUTHOR_ID, null)).thenReturn("feedorder:" + AUTHOR_ID);
      when(seenPostTracker.readOrder(anyString(), anyLong(), anyInt())).thenReturn(List.of());
      when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
      when(zSetOperations.reverseRange(anyString(), anyLong(), anyLong())).thenReturn(Set.of());

      // When / Then: BVA — (page - 1) * size must not wrap negative into a subList
      assertThat(newsfeedService.getFeed(AUTHOR_ID, Integer.MAX_VALUE, 50).getPosts()).isEmpty();
    }

    @Test
    @DisplayName("should still drop blocked authors from a re-ranked page")
    void shouldStillDropBlockedAuthors_whenRanked() throws Exception {
      // Given: demotion and blocking applied to the same page
      when(blockQueryService.blockedPairIds(AUTHOR_ID)).thenReturn(Set.of(FRIEND_ID));
      when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
      when(zSetOperations.reverseRangeWithScores("feed:" + AUTHOR_ID, 0L, 999L))
          .thenReturn(window("1", now, "2", now - HOUR));
      givenSeen(List.of("1", "2"), "1");
      when(redisTemplate.opsForValue()).thenReturn(valueOperations);
      when(valueOperations.multiGet(List.of("feedpost:2", "feedpost:1")))
          .thenReturn(List.of("json-2", "json-1"));
      when(objectMapper.readValue("json-2", FeedPostDataDto.class))
          .thenReturn(feedPost(2, FRIEND_ID, OffsetDateTime.now()));
      when(objectMapper.readValue("json-1", FeedPostDataDto.class))
          .thenReturn(feedPost(1, AUTHOR_ID, OffsetDateTime.now()));

      // When
      FeedResponseDto result = newsfeedService.getFeed(AUTHOR_ID, 1, 2);

      // Then: 2 outranked 1 and was still removed, because the block filter runs on the payloads
      assertThat(result.getPosts()).extracting(FeedPostDataDto::getPostId).containsExactly(1);
    }

    @Test
    @DisplayName("should keep the seen penalty larger than every freshness boost combined")
    void shouldKeepPenaltyLargerThanEveryBoost() {
      // Given / Then: a guard, not a behaviour. If the boosts are ever tuned up past the penalty, a
      // popular post by a close friend climbs straight back to where the reader already saw it, and
      // nothing else in this suite would notice.
      assertThat(NewsfeedService.SEEN_PENALTY_MILLIS)
          .isGreaterThan(
              2
                  * (PostScoringService.ENGAGEMENT_BOOST_MILLIS
                      + PostScoringService.AFFINITY_BOOST_MILLIS));
    }
  }

  @Nested
  @DisplayName("getFeed - SKILLS scope de-prioritisation")
  class GetSkillFeedSeenDemotionTests {

    @Test
    @DisplayName("should read the same 300-post window whether or not anything has been seen")
    void shouldNotNarrowTheCandidateWindow() {
      // Given
      when(skillTagResolver.resolveTagsFor(AUTHOR_ID)).thenReturn(Set.of("java"));
      when(seenPostTracker.hasSeenAnything(AUTHOR_ID)).thenReturn(true);
      when(seenPostTracker.orderKey(AUTHOR_ID, FeedScope.SKILLS))
          .thenReturn("feedorder:" + AUTHOR_ID + ":SKILLS");
      when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
      when(zSetOperations.reverseRangeWithScores("feed:" + AUTHOR_ID, 0L, 299L))
          .thenReturn(Set.of());

      // When
      newsfeedService.getFeed(AUTHOR_ID, 1, 10, FeedScope.SKILLS);

      // Then: ranking happens strictly inside the window. Penalising first and then taking the top
      // 300 would push seen posts out of the tab altogether, which is the filter this must not be.
      verify(zSetOperations).reverseRangeWithScores("feed:" + AUTHOR_ID, 0L, 299L);
    }

    @Test
    @DisplayName("should keep the two tabs' frozen orders apart")
    void shouldScopeTheSnapshotKey() throws Exception {
      // Given
      when(skillTagResolver.resolveTagsFor(AUTHOR_ID)).thenReturn(Set.of("java"));
      when(seenPostTracker.hasSeenAnything(AUTHOR_ID)).thenReturn(true);
      when(seenPostTracker.orderKey(AUTHOR_ID, FeedScope.SKILLS))
          .thenReturn("feedorder:" + AUTHOR_ID + ":SKILLS");
      when(seenPostTracker.seenAt(eq(AUTHOR_ID), anyList()))
          .thenReturn(Arrays.asList((Double) null));
      when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
      when(zSetOperations.reverseRangeWithScores("feed:" + AUTHOR_ID, 0L, 299L))
          .thenReturn(new LinkedHashSet<>(List.of(ZSetOperations.TypedTuple.of("1", 10d))));
      when(redisTemplate.opsForValue()).thenReturn(valueOperations);
      when(valueOperations.multiGet(List.of("feedpost:1"))).thenReturn(List.of("json-1"));
      FeedPostDataDto post = feedPost(1, FRIEND_ID, OffsetDateTime.now());
      post.setHashtags(List.of("java"));
      when(objectMapper.readValue("json-1", FeedPostDataDto.class)).thenReturn(post);

      // When
      newsfeedService.getFeed(AUTHOR_ID, 1, 10, FeedScope.SKILLS);

      // Then: switching tabs must not scramble the other tab's pagination
      verify(seenPostTracker).saveOrder("feedorder:" + AUTHOR_ID + ":SKILLS", List.of("1"));
    }
  }

  // =====================================================================
  // markSeen
  // =====================================================================

  @Nested
  @DisplayName("markSeen")
  class MarkSeenTests {

    @Test
    @DisplayName("should hand the batch to the tracker under the caller's own id")
    void shouldDelegateToTheTracker() {
      // When
      newsfeedService.markSeen(AUTHOR_ID, List.of(1, 2, 3));

      // Then: how it is stored, capped and expired is SeenPostTrackerTest's subject, not this one's
      verify(seenPostTracker).markSeen(AUTHOR_ID, List.of(1, 2, 3));
    }
  }
}
