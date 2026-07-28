package com.socialapp.posts.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.multipart.MultipartFile;

import com.socialapp.bookstore.dto.CreateBookRequestDto;
import com.socialapp.bookstore.service.BookService;
import com.socialapp.common.exception.ForbiddenException;
import com.socialapp.common.exception.NotFoundException;
import com.socialapp.common.exception.ValidationException;
import com.socialapp.moderation.config.ModerationProperties;
import com.socialapp.moderation.dto.ModerationResult;
import com.socialapp.moderation.enums.ModerationStatus;
import com.socialapp.moderation.enums.ViolationType;
import com.socialapp.moderation.event.ModerationEventPublisher;
import com.socialapp.moderation.exception.ContentViolationException;
import com.socialapp.moderation.exception.UserBannedException;
import com.socialapp.moderation.rule.ModerationRuleEngine;
import com.socialapp.moderation.service.UserBanService;
import com.socialapp.newsfeed.service.NewsfeedService;
import com.socialapp.posts.dto.CreatePostRequestDto;
import com.socialapp.posts.dto.UpdatePostRequestDto;
import com.socialapp.posts.entity.CommentEntity;
import com.socialapp.posts.entity.EventDetails;
import com.socialapp.posts.entity.HashtagEntity;
import com.socialapp.posts.entity.PollDetails;
import com.socialapp.posts.entity.PostEntity;
import com.socialapp.posts.entity.PostTagEntity;
import com.socialapp.posts.entity.PostTagId;
import com.socialapp.posts.entity.QnaDetails;
import com.socialapp.posts.entity.QuizDetails;
import com.socialapp.posts.entity.enums.PostType;
import com.socialapp.posts.entity.enums.PostVisibility;
import com.socialapp.posts.repository.CommentRepository;
import com.socialapp.posts.repository.HashtagRepository;
import com.socialapp.posts.repository.PostRepository;
import com.socialapp.reputation.entity.enums.RepSourceType;
import com.socialapp.reputation.event.ReputationEventPublisher;
import com.socialapp.security.entity.UserEntity;
import com.socialapp.security.repository.UserRepository;

/**
 * Component (unit) tests for {@link PostService}, per ISTQB CTFL v4.0.1:
 *
 * <ul>
 *   <li><b>Component testing</b> (Section 2.2.1) — every collaborator (repository, other
 *       services) is mocked with Mockito so PostService is tested in isolation, without a Spring
 *       context ({@code @ExtendWith(MockitoExtension.class)}, not {@code @SpringBootTest}).
 *   <li><b>Test Pyramid</b> (Section 5.1.6) — these are bottom-layer tests: small, isolated,
 *       fast, and numerous, in contrast to the few slow end-to-end tests at the top.
 *   <li><b>Branch testing / branch coverage</b> (Section 4.3.2, white-box) — test inputs are
 *       chosen with knowledge of PostService's internal if/else, loop, and short-circuit ({@code
 *       &&}) structure so every control-flow-graph branch is driven to both true and false at
 *       least once. Verified with JaCoCo: 68/68 branches (100%) covered on PostService.
 *   <li><b>BDD Given/When/Then</b> (Section 2.1.3) — every test follows the Given/When/Then
 *       format so behavior stays readable to non-developers.
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class PostServiceTest {

  private static final Integer AUTHOR_ID = 1;
  private static final Integer POST_ID = 100;

  @Mock private PostRepository postRepository;
  @Mock private UserRepository userRepository;
  @Mock private NewsfeedService newsfeedService;
  @Mock private ModerationRuleEngine moderationRuleEngine;
  @Mock private ModerationEventPublisher moderationEventPublisher;
  @Mock private ModerationProperties moderationProperties;
  @Mock private UserBanService userBanService;
  @Mock private BookService bookService;
  @Mock private HashtagRepository hashtagRepository;
  @Mock private CommentRepository commentRepository;
  @Mock private ReputationEventPublisher reputationEventPublisher;

  @InjectMocks private PostService postService;

  @Captor private ArgumentCaptor<PostEntity> postCaptor;
  @Captor private ArgumentCaptor<List<Integer>> taggedIdsCaptor;

  // ---------------------------------------------------------------------
  // Test data builders
  // ---------------------------------------------------------------------

  private static CreatePostRequestDto createRequest(
      String content, PostVisibility visibility, List<Integer> taggedUserIds) {
    CreatePostRequestDto request = new CreatePostRequestDto();
    request.setContent(content);
    request.setVisibility(visibility);
    request.setTaggedUserIds(taggedUserIds);
    return request;
  }

  private static UpdatePostRequestDto updateRequest(
      String content, PostVisibility visibility, List<Integer> taggedUserIds) {
    UpdatePostRequestDto request = new UpdatePostRequestDto();
    request.setContent(content);
    request.setVisibility(visibility);
    request.setTaggedUserIds(taggedUserIds);
    return request;
  }

  private static UserEntity someUser(Integer id) {
    UserEntity user = new UserEntity();
    user.setId(id);
    return user;
  }

  private static PostEntity existingPost(Integer id, Integer authorId) {
    PostEntity post = new PostEntity();
    post.setId(id);
    post.setAuthorId(authorId);
    post.setContent("Old content");
    post.setVisibility(PostVisibility.PUBLIC);
    return post;
  }

  private static ModerationResult approvedByRuleEngine() {
    return ModerationResult.builder()
        .status(ModerationStatus.PENDING_MODERATION)
        .violations(List.of())
        .build();
  }

  private static ModerationResult rejectedByRuleEngine() {
    return ModerationResult.builder()
        .status(ModerationStatus.REJECTED)
        .violations(List.of(ViolationType.SPAM))
        .build();
  }

  // =====================================================================
  // createPost
  // =====================================================================

  @Nested
  @DisplayName("createPost")
  class CreatePostTests {

    @Test
    @DisplayName("should reject BOOK post type before touching any dependency")
    void shouldThrowValidationException_whenPostTypeIsBook() {
      // Given
      CreatePostRequestDto request = createRequest("Hello", PostVisibility.PUBLIC, null);
      request.setPostType(PostType.BOOK);

      // When / Then
      assertThatThrownBy(() -> postService.createPost(AUTHOR_ID, request))
          .isInstanceOf(ValidationException.class)
          .hasMessageContaining("POST /v1/api/posts/books");
      verifyNoInteractions(
          postRepository,
          userRepository,
          userBanService,
          moderationRuleEngine,
          moderationEventPublisher,
          newsfeedService,
          bookService);
    }

    @Test
    @DisplayName("should reject when the author is currently banned")
    void shouldThrowUserBannedException_whenAuthorIsBanned() {
      // Given
      CreatePostRequestDto request = createRequest("Hello", PostVisibility.PUBLIC, null);
      OffsetDateTime banExpiry = OffsetDateTime.now().plusDays(1);
      when(userBanService.isUserBanned(AUTHOR_ID)).thenReturn(true);
      when(userBanService.getBanExpiry(AUTHOR_ID)).thenReturn(banExpiry);

      // When / Then
      assertThatThrownBy(() -> postService.createPost(AUTHOR_ID, request))
          .isInstanceOf(UserBannedException.class);
      verifyNoInteractions(postRepository, userRepository, moderationRuleEngine, newsfeedService);
    }

    @Test
    @DisplayName("should reject when the author no longer exists")
    void shouldThrowNotFoundException_whenAuthorDoesNotExist() {
      // Given
      CreatePostRequestDto request = createRequest("Hello", PostVisibility.PUBLIC, null);
      when(userBanService.isUserBanned(AUTHOR_ID)).thenReturn(false);
      when(userRepository.findById(AUTHOR_ID)).thenReturn(Optional.empty());

      // When / Then
      assertThatThrownBy(() -> postService.createPost(AUTHOR_ID, request))
          .isInstanceOf(NotFoundException.class)
          .hasMessageContaining("User not found");
      verifyNoInteractions(postRepository, moderationRuleEngine, newsfeedService);
    }

    @Test
    @DisplayName("should reject tagging users on a private post")
    void shouldThrowValidationException_whenPrivateVisibilityHasTaggedUsers() {
      // Given
      CreatePostRequestDto request = createRequest("Hello", PostVisibility.PRIVATE, List.of(2));
      when(userBanService.isUserBanned(AUTHOR_ID)).thenReturn(false);
      when(userRepository.findById(AUTHOR_ID)).thenReturn(Optional.of(someUser(AUTHOR_ID)));

      // When / Then
      assertThatThrownBy(() -> postService.createPost(AUTHOR_ID, request))
          .isInstanceOf(ValidationException.class)
          .hasMessageContaining("Private posts cannot tag other users");
      verifyNoInteractions(postRepository);
    }

    @Test
    @DisplayName("should reject tagged users when content is blank")
    void shouldThrowValidationException_whenTaggedUsersProvidedWithoutContent() {
      // Given
      CreatePostRequestDto request = createRequest(null, PostVisibility.PUBLIC, List.of(2));
      when(userBanService.isUserBanned(AUTHOR_ID)).thenReturn(false);
      when(userRepository.findById(AUTHOR_ID)).thenReturn(Optional.of(someUser(AUTHOR_ID)));

      // When / Then
      assertThatThrownBy(() -> postService.createPost(AUTHOR_ID, request))
          .isInstanceOf(ValidationException.class)
          .hasMessageContaining("Content is required if want to tag users");
      verifyNoInteractions(postRepository);
    }

    @Test
    @DisplayName("should reject more than the maximum allowed tagged users")
    void shouldThrowValidationException_whenTooManyTaggedUsers() {
      // Given
      List<Integer> tooManyIds = IntStream.rangeClosed(1, 21).boxed().toList();
      CreatePostRequestDto request =
          createRequest("Big shoutout", PostVisibility.PUBLIC, tooManyIds);
      when(userBanService.isUserBanned(AUTHOR_ID)).thenReturn(false);
      when(userRepository.findById(AUTHOR_ID)).thenReturn(Optional.of(someUser(AUTHOR_ID)));

      // When / Then
      assertThatThrownBy(() -> postService.createPost(AUTHOR_ID, request))
          .isInstanceOf(ValidationException.class)
          .hasMessageContaining("Cannot tag more than 20 users");
      verifyNoInteractions(postRepository);
    }

    @Test
    @DisplayName("should reject duplicate tagged users")
    void shouldThrowValidationException_whenDuplicateTaggedUsers() {
      // Given
      CreatePostRequestDto request =
          createRequest("Hi @[0] @[1]", PostVisibility.PUBLIC, List.of(2, 2));
      when(userBanService.isUserBanned(AUTHOR_ID)).thenReturn(false);
      when(userRepository.findById(AUTHOR_ID)).thenReturn(Optional.of(someUser(AUTHOR_ID)));

      // When / Then
      assertThatThrownBy(() -> postService.createPost(AUTHOR_ID, request))
          .isInstanceOf(ValidationException.class)
          .hasMessageContaining("Duplicate users in tag list");
      verifyNoInteractions(postRepository);
    }

    @Test
    @DisplayName("should reject a tagged user missing its content placeholder")
    void shouldThrowValidationException_whenPlaceholderMissingForTaggedUser() {
      // Given
      CreatePostRequestDto request =
          createRequest("Hello @[0]", PostVisibility.PUBLIC, List.of(2, 3));
      when(userBanService.isUserBanned(AUTHOR_ID)).thenReturn(false);
      when(userRepository.findById(AUTHOR_ID)).thenReturn(Optional.of(someUser(AUTHOR_ID)));

      // When / Then
      assertThatThrownBy(() -> postService.createPost(AUTHOR_ID, request))
          .isInstanceOf(ValidationException.class)
          .hasMessageContaining("Missing placeholder @[1]");
      verifyNoInteractions(postRepository);
    }

    @Test
    @DisplayName(
        "should save, tag and fan out immediately when every tag has a placeholder and moderation is off")
    void shouldSucceed_whenTaggedUsersHaveMatchingPlaceholders() {
      // Given
      CreatePostRequestDto request =
          createRequest("Hi @[0] and @[1]", PostVisibility.PUBLIC, List.of(2, 3));
      when(userBanService.isUserBanned(AUTHOR_ID)).thenReturn(false);
      when(userRepository.findById(AUTHOR_ID)).thenReturn(Optional.of(someUser(AUTHOR_ID)));
      when(moderationProperties.isEnabled()).thenReturn(false);

      // When
      postService.createPost(AUTHOR_ID, request);

      // Then
      verify(postRepository).save(postCaptor.capture());
      verify(postRepository).saveAndFlush(postCaptor.capture());
      PostEntity saved = postCaptor.getValue();
      assertThat(saved.getModerationStatus()).isEqualTo(ModerationStatus.APPROVED);
      assertThat(saved.getPostType()).isEqualTo(PostType.REGULAR);
      assertThat(saved.getTags()).hasSize(2);
      assertThat(saved.getTags()).extracting(PostTagEntity::getTaggedUserId).containsExactly(2, 3);
      verify(newsfeedService).fanOutPost(any());
      verifyNoInteractions(moderationEventPublisher);
    }

    @Test
    @DisplayName("should flush the insert before fanning out so createdAt is populated")
    void shouldFlushBeforeFanOut() {
      // Given — @CreationTimestamp fills createdAt when the INSERT runs, and save() only
      // schedules it. Fanning out before the flush cached posts with createdAt: null while the
      // Postgres row had the real timestamp.
      CreatePostRequestDto request = createRequest("Hello", PostVisibility.PUBLIC, null);
      when(userBanService.isUserBanned(AUTHOR_ID)).thenReturn(false);
      when(userRepository.findById(AUTHOR_ID)).thenReturn(Optional.of(someUser(AUTHOR_ID)));
      when(moderationProperties.isEnabled()).thenReturn(false);

      // When
      postService.createPost(AUTHOR_ID, request);

      // Then — order matters, not just the call: a flush after the fan-out fixes nothing
      InOrder inOrder = inOrder(postRepository, newsfeedService);
      inOrder.verify(postRepository).saveAndFlush(any(PostEntity.class));
      inOrder.verify(newsfeedService).fanOutPost(any());
    }

    @Test
    @DisplayName(
        "should save as REGULAR and fan out immediately when there are no tags and moderation is off")
    void shouldSucceed_whenNoTaggedUsersProvided() {
      // Given
      CreatePostRequestDto request =
          createRequest("Just a normal post", PostVisibility.PUBLIC, null);
      when(userBanService.isUserBanned(AUTHOR_ID)).thenReturn(false);
      when(userRepository.findById(AUTHOR_ID)).thenReturn(Optional.of(someUser(AUTHOR_ID)));
      when(moderationProperties.isEnabled()).thenReturn(false);

      // When
      postService.createPost(AUTHOR_ID, request);

      // Then
      verify(postRepository).save(postCaptor.capture());
      verify(postRepository).saveAndFlush(postCaptor.capture());
      PostEntity saved = postCaptor.getValue();
      assertThat(saved.getModerationStatus()).isEqualTo(ModerationStatus.APPROVED);
      assertThat(saved.getPostType()).isEqualTo(PostType.REGULAR);
      assertThat(saved.getTags()).isEmpty();
      verify(newsfeedService).fanOutPost(any());
      verifyNoInteractions(moderationEventPublisher);
    }

    @Test
    @DisplayName(
        "should allow a private post with blank content when there are no tagged users at all")
    void shouldSucceed_whenPrivateVisibilityWithNoContentAndNoTaggedUsers() {
      // Given
      CreatePostRequestDto request = createRequest(null, PostVisibility.PRIVATE, null);
      when(userBanService.isUserBanned(AUTHOR_ID)).thenReturn(false);
      when(userRepository.findById(AUTHOR_ID)).thenReturn(Optional.of(someUser(AUTHOR_ID)));
      when(moderationProperties.isEnabled()).thenReturn(false);

      // When
      postService.createPost(AUTHOR_ID, request);

      // Then
      verify(postRepository).save(postCaptor.capture());
      verify(postRepository).saveAndFlush(postCaptor.capture());
      assertThat(postCaptor.getValue().getVisibility()).isEqualTo(PostVisibility.PRIVATE);
      assertThat(postCaptor.getValue().getTags()).isEmpty();
      verify(newsfeedService).fanOutPost(any());
    }

    @Test
    @DisplayName("should reject when the moderation rule engine rejects the content")
    void shouldThrowContentViolationException_whenModerationEnabledAndRuleEngineRejects() {
      // Given
      CreatePostRequestDto request = createRequest("Spam spam spam", PostVisibility.PUBLIC, null);
      when(userBanService.isUserBanned(AUTHOR_ID)).thenReturn(false);
      when(userRepository.findById(AUTHOR_ID)).thenReturn(Optional.of(someUser(AUTHOR_ID)));
      when(moderationProperties.isEnabled()).thenReturn(true);
      when(moderationRuleEngine.evaluate(AUTHOR_ID, request.getContent()))
          .thenReturn(rejectedByRuleEngine());

      // When / Then
      assertThatThrownBy(() -> postService.createPost(AUTHOR_ID, request))
          .isInstanceOf(ContentViolationException.class);
      verifyNoInteractions(postRepository, newsfeedService, moderationEventPublisher);
    }

    @Test
    @DisplayName(
        "should save as PENDING_MODERATION and publish for review when moderation approves the content")
    void shouldPublishForReview_whenModerationEnabledAndRuleEngineApproves() {
      // Given
      CreatePostRequestDto request = createRequest("Nice content", PostVisibility.PUBLIC, null);
      when(userBanService.isUserBanned(AUTHOR_ID)).thenReturn(false);
      when(userRepository.findById(AUTHOR_ID)).thenReturn(Optional.of(someUser(AUTHOR_ID)));
      when(moderationProperties.isEnabled()).thenReturn(true);
      when(moderationRuleEngine.evaluate(AUTHOR_ID, request.getContent()))
          .thenReturn(approvedByRuleEngine());

      // When
      postService.createPost(AUTHOR_ID, request);

      // Then
      verify(postRepository).save(postCaptor.capture());
      verify(postRepository).saveAndFlush(postCaptor.capture());
      assertThat(postCaptor.getValue().getModerationStatus())
          .isEqualTo(ModerationStatus.PENDING_MODERATION);
      verify(moderationEventPublisher).publishForReview(any(PostEntity.class), isNull());
      verify(newsfeedService, never()).fanOutPost(any());
    }

    @Test
    @DisplayName("should reject an EVENT post with no event details")
    void shouldThrowValidationException_whenEventDetailsIsNull() {
      // Given
      CreatePostRequestDto request = createRequest("Party time", PostVisibility.PUBLIC, null);
      request.setPostType(PostType.EVENT);
      when(userBanService.isUserBanned(AUTHOR_ID)).thenReturn(false);
      when(userRepository.findById(AUTHOR_ID)).thenReturn(Optional.of(someUser(AUTHOR_ID)));
      when(moderationProperties.isEnabled()).thenReturn(false);

      // When / Then
      assertThatThrownBy(() -> postService.createPost(AUTHOR_ID, request))
          .isInstanceOf(ValidationException.class)
          .hasMessageContaining("Event details are required");
      verifyNoInteractions(postRepository);
    }

    @Test
    @DisplayName("should reject an EVENT post with a null event title")
    void shouldThrowValidationException_whenEventTitleIsNull() {
      // Given
      CreatePostRequestDto request = createRequest("Party time", PostVisibility.PUBLIC, null);
      request.setPostType(PostType.EVENT);
      request.setEventDetails(new EventDetails());
      when(userBanService.isUserBanned(AUTHOR_ID)).thenReturn(false);
      when(userRepository.findById(AUTHOR_ID)).thenReturn(Optional.of(someUser(AUTHOR_ID)));
      when(moderationProperties.isEnabled()).thenReturn(false);

      // When / Then
      assertThatThrownBy(() -> postService.createPost(AUTHOR_ID, request))
          .isInstanceOf(ValidationException.class)
          .hasMessageContaining("Event title is required");
    }

    @Test
    @DisplayName("should reject an EVENT post with a blank event title")
    void shouldThrowValidationException_whenEventTitleIsBlank() {
      // Given
      CreatePostRequestDto request = createRequest("Party time", PostVisibility.PUBLIC, null);
      request.setPostType(PostType.EVENT);
      EventDetails eventDetails = new EventDetails();
      eventDetails.setEventTitle("   ");
      request.setEventDetails(eventDetails);
      when(userBanService.isUserBanned(AUTHOR_ID)).thenReturn(false);
      when(userRepository.findById(AUTHOR_ID)).thenReturn(Optional.of(someUser(AUTHOR_ID)));
      when(moderationProperties.isEnabled()).thenReturn(false);

      // When / Then
      assertThatThrownBy(() -> postService.createPost(AUTHOR_ID, request))
          .isInstanceOf(ValidationException.class)
          .hasMessageContaining("Event title is required");
    }

    @Test
    @DisplayName("should reject an EVENT post with no start time")
    void shouldThrowValidationException_whenEventStartTimeIsNull() {
      // Given
      CreatePostRequestDto request = createRequest("Party time", PostVisibility.PUBLIC, null);
      request.setPostType(PostType.EVENT);
      EventDetails eventDetails = new EventDetails();
      eventDetails.setEventTitle("Launch party");
      request.setEventDetails(eventDetails);
      when(userBanService.isUserBanned(AUTHOR_ID)).thenReturn(false);
      when(userRepository.findById(AUTHOR_ID)).thenReturn(Optional.of(someUser(AUTHOR_ID)));
      when(moderationProperties.isEnabled()).thenReturn(false);

      // When / Then
      assertThatThrownBy(() -> postService.createPost(AUTHOR_ID, request))
          .isInstanceOf(ValidationException.class)
          .hasMessageContaining("Event start time is required");
    }

    @Test
    @DisplayName("should reject an EVENT post with no end time")
    void shouldThrowValidationException_whenEventEndTimeIsNull() {
      // Given
      CreatePostRequestDto request = createRequest("Party time", PostVisibility.PUBLIC, null);
      request.setPostType(PostType.EVENT);
      EventDetails eventDetails = new EventDetails();
      eventDetails.setEventTitle("Launch party");
      eventDetails.setStartTime(OffsetDateTime.now());
      request.setEventDetails(eventDetails);
      when(userBanService.isUserBanned(AUTHOR_ID)).thenReturn(false);
      when(userRepository.findById(AUTHOR_ID)).thenReturn(Optional.of(someUser(AUTHOR_ID)));
      when(moderationProperties.isEnabled()).thenReturn(false);

      // When / Then
      assertThatThrownBy(() -> postService.createPost(AUTHOR_ID, request))
          .isInstanceOf(ValidationException.class)
          .hasMessageContaining("Event end time is required");
    }

    @Test
    @DisplayName("should reject an EVENT post whose end time is before its start time")
    void shouldThrowValidationException_whenEventEndTimeBeforeStartTime() {
      // Given
      CreatePostRequestDto request = createRequest("Party time", PostVisibility.PUBLIC, null);
      request.setPostType(PostType.EVENT);
      OffsetDateTime start = OffsetDateTime.now();
      EventDetails eventDetails = new EventDetails();
      eventDetails.setEventTitle("Launch party");
      eventDetails.setStartTime(start);
      eventDetails.setEndTime(start.minusHours(1));
      request.setEventDetails(eventDetails);
      when(userBanService.isUserBanned(AUTHOR_ID)).thenReturn(false);
      when(userRepository.findById(AUTHOR_ID)).thenReturn(Optional.of(someUser(AUTHOR_ID)));
      when(moderationProperties.isEnabled()).thenReturn(false);

      // When / Then
      assertThatThrownBy(() -> postService.createPost(AUTHOR_ID, request))
          .isInstanceOf(ValidationException.class)
          .hasMessageContaining("end time must be after start time");
    }

    @Test
    @DisplayName("should save an EVENT post when all event details are valid")
    void shouldSucceed_whenEventDetailsValid() {
      // Given
      CreatePostRequestDto request = createRequest("Party time", PostVisibility.PUBLIC, null);
      request.setPostType(PostType.EVENT);
      OffsetDateTime start = OffsetDateTime.now();
      EventDetails eventDetails = new EventDetails();
      eventDetails.setEventTitle("Launch party");
      eventDetails.setStartTime(start);
      eventDetails.setEndTime(start.plusHours(2));
      request.setEventDetails(eventDetails);
      when(userBanService.isUserBanned(AUTHOR_ID)).thenReturn(false);
      when(userRepository.findById(AUTHOR_ID)).thenReturn(Optional.of(someUser(AUTHOR_ID)));
      when(moderationProperties.isEnabled()).thenReturn(false);

      // When
      postService.createPost(AUTHOR_ID, request);

      // Then
      verify(postRepository).save(postCaptor.capture());
      verify(postRepository).saveAndFlush(postCaptor.capture());
      assertThat(postCaptor.getValue().getPostType()).isEqualTo(PostType.EVENT);
      verify(newsfeedService).fanOutPost(any());
    }
  }

  // =====================================================================
  // createBookPost
  // =====================================================================

  @Nested
  @DisplayName("createBookPost")
  class CreateBookPostTests {

    private final MultipartFile bookFile = mock(MultipartFile.class);
    private final MultipartFile coverFile = mock(MultipartFile.class);

    @Test
    @DisplayName("should reject when book details are missing, before touching any dependency")
    void shouldThrowValidationException_whenBookDetailsIsNull() {
      // Given
      CreatePostRequestDto request =
          createRequest("Check out my book", PostVisibility.PUBLIC, null);

      // When / Then
      assertThatThrownBy(() -> postService.createBookPost(AUTHOR_ID, request, bookFile, coverFile))
          .isInstanceOf(ValidationException.class)
          .hasMessageContaining("Book details are required");
      verifyNoInteractions(postRepository, userRepository, userBanService, bookService);
    }

    @Test
    @DisplayName("should reject when the book title is null")
    void shouldThrowValidationException_whenBookTitleIsNull() {
      // Given
      CreatePostRequestDto request =
          createRequest("Check out my book", PostVisibility.PUBLIC, null);
      request.setBookDetails(new CreateBookRequestDto());

      // When / Then
      assertThatThrownBy(() -> postService.createBookPost(AUTHOR_ID, request, bookFile, coverFile))
          .isInstanceOf(ValidationException.class)
          .hasMessageContaining("Book title is required");
      verifyNoInteractions(postRepository, userRepository, userBanService, bookService);
    }

    @Test
    @DisplayName("should reject when the book title is blank")
    void shouldThrowValidationException_whenBookTitleIsBlank() {
      // Given
      CreatePostRequestDto request =
          createRequest("Check out my book", PostVisibility.PUBLIC, null);
      CreateBookRequestDto bookDetails = new CreateBookRequestDto();
      bookDetails.setTitle("   ");
      request.setBookDetails(bookDetails);

      // When / Then
      assertThatThrownBy(() -> postService.createBookPost(AUTHOR_ID, request, bookFile, coverFile))
          .isInstanceOf(ValidationException.class)
          .hasMessageContaining("Book title is required");
      verifyNoInteractions(postRepository, userRepository, userBanService, bookService);
    }

    @Test
    @DisplayName("should save the post as BOOK type and delegate upload to BookService")
    void shouldSucceed_whenBookDetailsValid() {
      // Given
      CreatePostRequestDto request =
          createRequest("Check out my book", PostVisibility.PUBLIC, null);
      CreateBookRequestDto bookDetails = new CreateBookRequestDto();
      bookDetails.setTitle("My First Book");
      request.setBookDetails(bookDetails);
      when(userBanService.isUserBanned(AUTHOR_ID)).thenReturn(false);
      when(userRepository.findById(AUTHOR_ID)).thenReturn(Optional.of(someUser(AUTHOR_ID)));
      when(moderationProperties.isEnabled()).thenReturn(false);

      // When
      postService.createBookPost(AUTHOR_ID, request, bookFile, coverFile);

      // Then
      verify(postRepository).save(postCaptor.capture());
      verify(postRepository).saveAndFlush(postCaptor.capture());
      assertThat(postCaptor.getValue().getPostType()).isEqualTo(PostType.BOOK);
      verify(bookService)
          .createBookForPost(eq(AUTHOR_ID), isNull(), eq(bookDetails), eq(bookFile), eq(coverFile));
      verify(newsfeedService).fanOutPost(any());
    }
  }

  // =====================================================================
  // updatePost
  // =====================================================================

  @Nested
  @DisplayName("updatePost")
  class UpdatePostTests {

    @Test
    @DisplayName("should keep detail blocks, images and tags that the request did not mention")
    void shouldKeepUnmentionedFields_whenRequestIsPartial() {
      // Given — a caption-only edit, exactly what a client sends when it has nothing else to
      // say. Before this, BeanUtils.copyProperties wrote null over every omitted column and one
      // such edit erased the post's quiz, poll, images and tag list.
      PostEntity post = existingPost(POST_ID, AUTHOR_ID);
      post.setQuizDetails(new QuizDetails());
      post.setPollDetails(new PollDetails());
      post.setImages(List.of("a.png"));
      post.getTags().add(new PostTagEntity(new PostTagId(POST_ID, 0), 42));

      UpdatePostRequestDto request = new UpdatePostRequestDto();
      request.setContent("Only the caption changed");

      when(userBanService.isUserBanned(AUTHOR_ID)).thenReturn(false);
      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(post));
      when(userRepository.findById(AUTHOR_ID)).thenReturn(Optional.of(someUser(AUTHOR_ID)));
      when(moderationProperties.isEnabled()).thenReturn(false);

      // When
      postService.updatePost(AUTHOR_ID, POST_ID, request);

      // Then
      assertThat(post.getContent()).isEqualTo("Only the caption changed");
      assertThat(post.getQuizDetails()).isNotNull();
      assertThat(post.getPollDetails()).isNotNull();
      assertThat(post.getImages()).containsExactly("a.png");
      assertThat(post.getVisibility()).isEqualTo(PostVisibility.PUBLIC);
      assertThat(post.getTags()).hasSize(1);
    }

    @Test
    @DisplayName("should still replace fields the request does mention")
    void shouldReplaceMentionedFields() {
      // Given — "leave alone" must not turn into "ignore": a value that IS sent still wins
      PostEntity post = existingPost(POST_ID, AUTHOR_ID);
      post.setImages(List.of("old.png"));

      UpdatePostRequestDto request = new UpdatePostRequestDto();
      request.setImages(List.of("new.png"));
      request.setVisibility(PostVisibility.FRIENDS);

      when(userBanService.isUserBanned(AUTHOR_ID)).thenReturn(false);
      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(post));
      when(userRepository.findById(AUTHOR_ID)).thenReturn(Optional.of(someUser(AUTHOR_ID)));
      when(moderationProperties.isEnabled()).thenReturn(false);

      // When
      postService.updatePost(AUTHOR_ID, POST_ID, request);

      // Then
      assertThat(post.getImages()).containsExactly("new.png");
      assertThat(post.getVisibility()).isEqualTo(PostVisibility.FRIENDS);
      assertThat(post.getContent()).isEqualTo("Old content");
    }

    @Test
    @DisplayName("should clear the tag list when the request sends an empty one")
    void shouldClearTags_whenRequestSendsEmptyList() {
      // Given — an empty list is the escape hatch that null no longer provides
      PostEntity post = existingPost(POST_ID, AUTHOR_ID);
      post.getTags().add(new PostTagEntity(new PostTagId(POST_ID, 0), 42));

      UpdatePostRequestDto request = new UpdatePostRequestDto();
      request.setTaggedUserIds(List.of());

      when(userBanService.isUserBanned(AUTHOR_ID)).thenReturn(false);
      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(post));
      when(userRepository.findById(AUTHOR_ID)).thenReturn(Optional.of(someUser(AUTHOR_ID)));
      when(moderationProperties.isEnabled()).thenReturn(false);

      // When
      postService.updatePost(AUTHOR_ID, POST_ID, request);

      // Then
      assertThat(post.getTags()).isEmpty();
    }

    @Test
    @DisplayName("should judge the privacy rule on the merged post, not on the request alone")
    void shouldEnforcePrivacyRule_againstExistingTags() {
      // Given — the post already tags someone and the request only flips it to PRIVATE. The
      // tags are never mentioned, yet the merged post would be a private post with tags.
      PostEntity post = existingPost(POST_ID, AUTHOR_ID);
      post.getTags().add(new PostTagEntity(new PostTagId(POST_ID, 0), 42));

      UpdatePostRequestDto request = new UpdatePostRequestDto();
      request.setVisibility(PostVisibility.PRIVATE);

      when(userBanService.isUserBanned(AUTHOR_ID)).thenReturn(false);
      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(post));
      when(userRepository.findById(AUTHOR_ID)).thenReturn(Optional.of(someUser(AUTHOR_ID)));

      // When / Then
      assertThatThrownBy(() -> postService.updatePost(AUTHOR_ID, POST_ID, request))
          .isInstanceOf(ValidationException.class)
          .hasMessageContaining("Private posts cannot tag other users");
    }

    @Test
    @DisplayName("should not apply the placeholder rule to tags the request never sent")
    void shouldSkipPlaceholderRule_whenTagsAreNotSent() {
      // Given — editing the caption of a tagged post. The new text carries no @[i] placeholder,
      // but the caller is not touching the tags, so the rule does not apply to them.
      PostEntity post = existingPost(POST_ID, AUTHOR_ID);
      post.getTags().add(new PostTagEntity(new PostTagId(POST_ID, 0), 42));

      UpdatePostRequestDto request = new UpdatePostRequestDto();
      request.setContent("A caption with no placeholders");

      when(userBanService.isUserBanned(AUTHOR_ID)).thenReturn(false);
      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(post));
      when(userRepository.findById(AUTHOR_ID)).thenReturn(Optional.of(someUser(AUTHOR_ID)));
      when(moderationProperties.isEnabled()).thenReturn(false);

      // When / Then — rejecting this edit would be no better than silently dropping the tags
      postService.updatePost(AUTHOR_ID, POST_ID, request);
      assertThat(post.getTags()).hasSize(1);
    }

    @Test
    @DisplayName("should still apply the placeholder rule to a tag list the request does send")
    void shouldApplyPlaceholderRule_whenTagsAreSent() {
      // Given
      PostEntity post = existingPost(POST_ID, AUTHOR_ID);

      UpdatePostRequestDto request = new UpdatePostRequestDto();
      request.setContent("No placeholder here");
      request.setTaggedUserIds(List.of(42));

      when(userBanService.isUserBanned(AUTHOR_ID)).thenReturn(false);
      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(post));
      when(userRepository.findById(AUTHOR_ID)).thenReturn(Optional.of(someUser(AUTHOR_ID)));

      // When / Then
      assertThatThrownBy(() -> postService.updatePost(AUTHOR_ID, POST_ID, request))
          .isInstanceOf(ValidationException.class)
          .hasMessageContaining("Missing placeholder");
    }

    @Test
    @DisplayName("should reject when the actor is currently banned")
    void shouldThrowUserBannedException_whenActorIsBanned() {
      // Given
      UpdatePostRequestDto request = updateRequest("Updated", PostVisibility.PUBLIC, null);
      OffsetDateTime banExpiry = OffsetDateTime.now().plusDays(1);
      when(userBanService.isUserBanned(AUTHOR_ID)).thenReturn(true);
      when(userBanService.getBanExpiry(AUTHOR_ID)).thenReturn(banExpiry);

      // When / Then
      assertThatThrownBy(() -> postService.updatePost(AUTHOR_ID, POST_ID, request))
          .isInstanceOf(UserBannedException.class);
      verifyNoInteractions(postRepository, userRepository, newsfeedService);
    }

    @Test
    @DisplayName("should reject when the post does not exist")
    void shouldThrowNotFoundException_whenPostDoesNotExist() {
      // Given
      UpdatePostRequestDto request = updateRequest("Updated", PostVisibility.PUBLIC, null);
      when(userBanService.isUserBanned(AUTHOR_ID)).thenReturn(false);
      when(postRepository.findById(POST_ID)).thenReturn(Optional.empty());

      // When / Then
      assertThatThrownBy(() -> postService.updatePost(AUTHOR_ID, POST_ID, request))
          .isInstanceOf(NotFoundException.class)
          .hasMessageContaining("Post not found");
      verifyNoInteractions(userRepository, newsfeedService);
    }

    @Test
    @DisplayName("should reject when the actor is not the author")
    void shouldThrowForbiddenException_whenActorIsNotAuthor() {
      // Given
      UpdatePostRequestDto request = updateRequest("Updated", PostVisibility.PUBLIC, null);
      PostEntity post = existingPost(POST_ID, 999);
      when(userBanService.isUserBanned(AUTHOR_ID)).thenReturn(false);
      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(post));

      // When / Then
      assertThatThrownBy(() -> postService.updatePost(AUTHOR_ID, POST_ID, request))
          .isInstanceOf(ForbiddenException.class)
          .hasMessageContaining("Only the author can modify this post");
      verifyNoInteractions(userRepository, newsfeedService);
    }

    @Test
    @DisplayName("should reject when the acting user no longer exists")
    void shouldThrowNotFoundException_whenActorDoesNotExist() {
      // Given
      UpdatePostRequestDto request = updateRequest("Updated", PostVisibility.PUBLIC, null);
      PostEntity post = existingPost(POST_ID, AUTHOR_ID);
      when(userBanService.isUserBanned(AUTHOR_ID)).thenReturn(false);
      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(post));
      when(userRepository.findById(AUTHOR_ID)).thenReturn(Optional.empty());

      // When / Then
      assertThatThrownBy(() -> postService.updatePost(AUTHOR_ID, POST_ID, request))
          .isInstanceOf(NotFoundException.class)
          .hasMessageContaining("User not found");
      verifyNoInteractions(newsfeedService);
    }

    @Test
    @DisplayName("should reject tagging users on a private post")
    void shouldThrowValidationException_whenTagValidationFails() {
      // Given
      UpdatePostRequestDto request = updateRequest("Updated", PostVisibility.PRIVATE, List.of(2));
      PostEntity post = existingPost(POST_ID, AUTHOR_ID);
      when(userBanService.isUserBanned(AUTHOR_ID)).thenReturn(false);
      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(post));
      when(userRepository.findById(AUTHOR_ID)).thenReturn(Optional.of(someUser(AUTHOR_ID)));

      // When / Then
      assertThatThrownBy(() -> postService.updatePost(AUTHOR_ID, POST_ID, request))
          .isInstanceOf(ValidationException.class)
          .hasMessageContaining("Private posts cannot tag other users");
      verifyNoInteractions(newsfeedService, moderationEventPublisher);
    }

    @Test
    @DisplayName("should reject when the moderation rule engine rejects the updated content")
    void shouldThrowContentViolationException_whenModerationEnabledAndRejected() {
      // Given
      UpdatePostRequestDto request = updateRequest("Bad content", PostVisibility.PUBLIC, null);
      PostEntity post = existingPost(POST_ID, AUTHOR_ID);
      when(userBanService.isUserBanned(AUTHOR_ID)).thenReturn(false);
      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(post));
      when(userRepository.findById(AUTHOR_ID)).thenReturn(Optional.of(someUser(AUTHOR_ID)));
      when(moderationProperties.isEnabled()).thenReturn(true);
      when(moderationRuleEngine.evaluate(AUTHOR_ID, request.getContent()))
          .thenReturn(rejectedByRuleEngine());

      // When / Then
      assertThatThrownBy(() -> postService.updatePost(AUTHOR_ID, POST_ID, request))
          .isInstanceOf(ContentViolationException.class);
      verifyNoInteractions(newsfeedService, moderationEventPublisher);
      verify(postRepository, never()).save(any());
    }

    @Test
    @DisplayName(
        "should set PENDING_MODERATION, pull the post from feeds and publish for review when moderation is on")
    void shouldSetPendingModerationAndPublish_whenModerationEnabledAndApproved() {
      // Given
      UpdatePostRequestDto request = updateRequest("Nice update", PostVisibility.PUBLIC, null);
      PostEntity post = existingPost(POST_ID, AUTHOR_ID);
      when(userBanService.isUserBanned(AUTHOR_ID)).thenReturn(false);
      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(post));
      when(userRepository.findById(AUTHOR_ID)).thenReturn(Optional.of(someUser(AUTHOR_ID)));
      when(moderationProperties.isEnabled()).thenReturn(true);
      when(moderationRuleEngine.evaluate(AUTHOR_ID, request.getContent()))
          .thenReturn(approvedByRuleEngine());

      // When
      postService.updatePost(AUTHOR_ID, POST_ID, request);

      // Then
      assertThat(post.getModerationStatus()).isEqualTo(ModerationStatus.PENDING_MODERATION);
      verify(postRepository).save(post);
      verify(newsfeedService).removePost(eq(POST_ID), eq(AUTHOR_ID), taggedIdsCaptor.capture());
      assertThat(taggedIdsCaptor.getValue()).isEmpty();
      verify(moderationEventPublisher).publishForReview(post, request.getTaggedUserIds());
      verify(newsfeedService, never()).fanOutPost(any());
    }

    @Test
    @DisplayName("should save and fan out immediately when moderation is off")
    void shouldSaveAndFanOut_whenModerationDisabled() {
      // Given
      UpdatePostRequestDto request = updateRequest("Nice update", PostVisibility.PUBLIC, null);
      PostEntity post = existingPost(POST_ID, AUTHOR_ID);
      when(userBanService.isUserBanned(AUTHOR_ID)).thenReturn(false);
      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(post));
      when(userRepository.findById(AUTHOR_ID)).thenReturn(Optional.of(someUser(AUTHOR_ID)));
      when(moderationProperties.isEnabled()).thenReturn(false);

      // When
      postService.updatePost(AUTHOR_ID, POST_ID, request);

      // Then
      verify(postRepository).save(post);
      verify(newsfeedService).fanOutPost(post.getId());
      verifyNoInteractions(moderationEventPublisher);
      verify(newsfeedService, never()).removePost(any(), any(), any());
    }
  }

  // =====================================================================
  // deletePost
  // =====================================================================

  @Nested
  @DisplayName("deletePost")
  class DeletePostTests {

    @Test
    @DisplayName("should reject when the post does not exist")
    void shouldThrowNotFoundException_whenPostDoesNotExist() {
      // Given
      when(postRepository.findById(POST_ID)).thenReturn(Optional.empty());

      // When / Then
      assertThatThrownBy(() -> postService.deletePost(AUTHOR_ID, POST_ID))
          .isInstanceOf(NotFoundException.class)
          .hasMessageContaining("Post not found");
      verifyNoInteractions(newsfeedService);
      verify(postRepository, never()).delete(any());
    }

    @Test
    @DisplayName("should reject when the actor is not the author")
    void shouldThrowForbiddenException_whenActorIsNotAuthor() {
      // Given
      PostEntity post = existingPost(POST_ID, 999);
      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(post));

      // When / Then
      assertThatThrownBy(() -> postService.deletePost(AUTHOR_ID, POST_ID))
          .isInstanceOf(ForbiddenException.class)
          .hasMessageContaining("Only the author can modify this post");
      verifyNoInteractions(newsfeedService);
      verify(postRepository, never()).delete(any());
    }

    @Test
    @DisplayName(
        "should delete the post and remove it from feeds with an empty tag list when it has no tags")
    void shouldDeleteAndRemoveFromFeeds_whenPostHasNoTags() {
      // Given
      PostEntity post = existingPost(POST_ID, AUTHOR_ID);
      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(post));

      // When
      postService.deletePost(AUTHOR_ID, POST_ID);

      // Then
      verify(postRepository).delete(post);
      verify(newsfeedService).removePost(eq(POST_ID), eq(AUTHOR_ID), taggedIdsCaptor.capture());
      assertThat(taggedIdsCaptor.getValue()).isEmpty();
    }

    @Test
    @DisplayName("should remove the post from feeds with tagged users sorted by their tag position")
    void shouldPassSortedTaggedUserIds_whenPostHasTags() {
      // Given
      PostEntity post = existingPost(POST_ID, AUTHOR_ID);
      post.getTags().add(new PostTagEntity(new PostTagId(POST_ID, 1), 20));
      post.getTags().add(new PostTagEntity(new PostTagId(POST_ID, 0), 10));
      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(post));

      // When
      postService.deletePost(AUTHOR_ID, POST_ID);

      // Then
      verify(newsfeedService).removePost(eq(POST_ID), eq(AUTHOR_ID), taggedIdsCaptor.capture());
      assertThat(taggedIdsCaptor.getValue()).containsExactly(10, 20);
    }

    @Test
    @DisplayName(
        "should still delete the post and swallow the error when removing it from feeds fails")
    void shouldSwallowException_whenRemovingFromFeedsFails() {
      // Given
      PostEntity post = existingPost(POST_ID, AUTHOR_ID);
      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(post));
      doThrow(new RuntimeException("feed store unavailable"))
          .when(newsfeedService)
          .removePost(eq(POST_ID), eq(AUTHOR_ID), any());

      // When / Then
      assertThatCode(() -> postService.deletePost(AUTHOR_ID, POST_ID)).doesNotThrowAnyException();
      verify(postRepository).delete(post);
    }
  }

  // =====================================================================
  // acceptAnswer
  // =====================================================================

  @Nested
  @DisplayName("acceptAnswer")
  class AcceptAnswerTests {

    private static final Integer COMMENT_ID = 500;
    private static final Integer COMMENTER_ID = 2;

    private PostEntity qnaPost() {
      PostEntity post = existingPost(POST_ID, AUTHOR_ID);
      post.setPostType(PostType.QNA);
      post.setQnaDetails(new QnaDetails(false, null, null));
      return post;
    }

    private CommentEntity comment(Integer authorId) {
      CommentEntity comment = new CommentEntity();
      comment.setId(COMMENT_ID);
      comment.setPostId(POST_ID);
      comment.setAuthorId(authorId);
      comment.setContent("The answer");
      return comment;
    }

    @Test
    @DisplayName("should accept the answer and award reputation to a different comment author")
    void shouldAcceptAnswerAndAwardRep_whenCommenterIsNotTheAuthor() {
      // Given
      PostEntity post = qnaPost();
      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(post));
      when(commentRepository.findById(COMMENT_ID)).thenReturn(Optional.of(comment(COMMENTER_ID)));

      // When
      postService.acceptAnswer(AUTHOR_ID, POST_ID, COMMENT_ID);

      // Then
      assertThat(post.getQnaDetails().getAcceptedAnswerId()).isEqualTo(COMMENT_ID);
      // B10: isResolved was never written by anything, so an answered question kept the
      // "unanswered" label forever
      assertThat(post.getQnaDetails().getIsResolved()).isTrue();
      verify(postRepository).save(post);
      verify(reputationEventPublisher)
          .award(COMMENTER_ID, RepSourceType.ACCEPTED_ANSWER, COMMENT_ID.toString());
    }

    @Test
    @DisplayName("should refresh the cached QNA block so the feed stops saying unanswered")
    void shouldRefreshCachedQnaDetails() {
      // Given
      PostEntity post = qnaPost();
      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(post));
      when(commentRepository.findById(COMMENT_ID)).thenReturn(Optional.of(comment(COMMENTER_ID)));

      // When
      postService.acceptAnswer(AUTHOR_ID, POST_ID, COMMENT_ID);

      // Then — the feed reads qnaDetails out of Redis and never falls back to Postgres
      verify(newsfeedService).updateCachedQnaDetails(POST_ID, post.getQnaDetails());
    }

    @Test
    @DisplayName("should not award reputation when the author accepts their own answer")
    void shouldNotAwardRep_whenAuthorAcceptsOwnAnswer() {
      // Given
      PostEntity post = qnaPost();
      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(post));
      when(commentRepository.findById(COMMENT_ID)).thenReturn(Optional.of(comment(AUTHOR_ID)));

      // When
      postService.acceptAnswer(AUTHOR_ID, POST_ID, COMMENT_ID);

      // Then
      assertThat(post.getQnaDetails().getAcceptedAnswerId()).isEqualTo(COMMENT_ID);
      verifyNoInteractions(reputationEventPublisher);
    }

    @Test
    @DisplayName("should reject when the actor is not the post author")
    void shouldThrowForbiddenException_whenActorIsNotAuthor() {
      // Given
      PostEntity post = qnaPost();
      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(post));

      // When / Then
      assertThatThrownBy(() -> postService.acceptAnswer(999, POST_ID, COMMENT_ID))
          .isInstanceOf(ForbiddenException.class);
      verifyNoInteractions(commentRepository, reputationEventPublisher);
    }

    @Test
    @DisplayName("should reject when the post is not a QNA post")
    void shouldThrowValidationException_whenPostIsNotQna() {
      // Given
      PostEntity post = existingPost(POST_ID, AUTHOR_ID);
      post.setPostType(PostType.REGULAR);
      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(post));

      // When / Then
      assertThatThrownBy(() -> postService.acceptAnswer(AUTHOR_ID, POST_ID, COMMENT_ID))
          .isInstanceOf(ValidationException.class)
          .hasMessageContaining("Only QNA posts");
      verifyNoInteractions(commentRepository, reputationEventPublisher);
    }

    @Test
    @DisplayName("should reject when an answer has already been accepted")
    void shouldThrowValidationException_whenAnswerAlreadyAccepted() {
      // Given
      PostEntity post = qnaPost();
      post.getQnaDetails().setAcceptedAnswerId(COMMENT_ID);
      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(post));

      // When / Then
      assertThatThrownBy(() -> postService.acceptAnswer(AUTHOR_ID, POST_ID, COMMENT_ID))
          .isInstanceOf(ValidationException.class)
          .hasMessageContaining("already been accepted");
      verifyNoInteractions(commentRepository, reputationEventPublisher);
    }

    @Test
    @DisplayName("should reject when the comment does not exist")
    void shouldThrowNotFoundException_whenCommentDoesNotExist() {
      // Given
      PostEntity post = qnaPost();
      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(post));
      when(commentRepository.findById(COMMENT_ID)).thenReturn(Optional.empty());

      // When / Then
      assertThatThrownBy(() -> postService.acceptAnswer(AUTHOR_ID, POST_ID, COMMENT_ID))
          .isInstanceOf(NotFoundException.class)
          .hasMessageContaining("Comment not found");
      verifyNoInteractions(reputationEventPublisher);
    }

    @Test
    @DisplayName("should reject when the comment belongs to a different post")
    void shouldThrowValidationException_whenCommentBelongsToDifferentPost() {
      // Given
      PostEntity post = qnaPost();
      CommentEntity foreignComment = comment(COMMENTER_ID);
      foreignComment.setPostId(POST_ID + 1);
      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(post));
      when(commentRepository.findById(COMMENT_ID)).thenReturn(Optional.of(foreignComment));

      // When / Then
      assertThatThrownBy(() -> postService.acceptAnswer(AUTHOR_ID, POST_ID, COMMENT_ID))
          .isInstanceOf(ValidationException.class)
          .hasMessageContaining("does not belong to this post");
      verifyNoInteractions(reputationEventPublisher);
    }
  }

  // =====================================================================
  // unacceptAnswer
  // =====================================================================

  @Nested
  @DisplayName("unacceptAnswer")
  class UnacceptAnswerTests {

    private static final Integer COMMENT_ID = 500;
    private static final Integer COMMENTER_ID = 2;

    private PostEntity resolvedQnaPost() {
      PostEntity post = existingPost(POST_ID, AUTHOR_ID);
      post.setPostType(PostType.QNA);
      post.setQnaDetails(new QnaDetails(true, null, COMMENT_ID));
      return post;
    }

    private CommentEntity comment(Integer authorId) {
      CommentEntity entity = new CommentEntity();
      entity.setId(COMMENT_ID);
      entity.setPostId(POST_ID);
      entity.setAuthorId(authorId);
      entity.setContent("The answer");
      return entity;
    }

    @Test
    @DisplayName("should clear the pick, reopen the question and take the reputation back")
    void shouldClearAcceptedAnswerAndRevokeRep() {
      // Given — before this endpoint existed the first pick was permanent, because acceptAnswer
      // refuses to run twice
      PostEntity post = resolvedQnaPost();
      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(post));
      when(commentRepository.findById(COMMENT_ID)).thenReturn(Optional.of(comment(COMMENTER_ID)));

      // When
      postService.unacceptAnswer(AUTHOR_ID, POST_ID);

      // Then
      assertThat(post.getQnaDetails().getAcceptedAnswerId()).isNull();
      assertThat(post.getQnaDetails().getIsResolved()).isFalse();
      verify(postRepository).save(post);
      verify(newsfeedService).updateCachedQnaDetails(POST_ID, post.getQnaDetails());
      // Same sourceId the award used, or accept/un-accept in a loop would mint points
      verify(reputationEventPublisher)
          .revoke(COMMENTER_ID, RepSourceType.ACCEPTED_ANSWER, COMMENT_ID.toString());
    }

    @Test
    @DisplayName("should not revoke reputation that was never granted for a self-answer")
    void shouldNotRevokeRep_whenAuthorHadAcceptedOwnAnswer() {
      // Given — acceptAnswer skips the award when the author accepts themselves
      PostEntity post = resolvedQnaPost();
      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(post));
      when(commentRepository.findById(COMMENT_ID)).thenReturn(Optional.of(comment(AUTHOR_ID)));

      // When
      postService.unacceptAnswer(AUTHOR_ID, POST_ID);

      // Then
      assertThat(post.getQnaDetails().getAcceptedAnswerId()).isNull();
      verifyNoInteractions(reputationEventPublisher);
    }

    @Test
    @DisplayName("should still clear the pick when the accepted comment has since been deleted")
    void shouldClearAcceptedAnswer_whenCommentIsGone() {
      // Given
      PostEntity post = resolvedQnaPost();
      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(post));
      when(commentRepository.findById(COMMENT_ID)).thenReturn(Optional.empty());

      // When
      postService.unacceptAnswer(AUTHOR_ID, POST_ID);

      // Then — a deleted comment must not strand the post as resolved
      assertThat(post.getQnaDetails().getAcceptedAnswerId()).isNull();
      assertThat(post.getQnaDetails().getIsResolved()).isFalse();
      verifyNoInteractions(reputationEventPublisher);
    }

    @Test
    @DisplayName("should reject when the actor is not the post author")
    void shouldThrowForbiddenException_whenActorIsNotAuthor() {
      // Given
      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(resolvedQnaPost()));

      // When / Then
      assertThatThrownBy(() -> postService.unacceptAnswer(999, POST_ID))
          .isInstanceOf(ForbiddenException.class);
      verifyNoInteractions(commentRepository, reputationEventPublisher, newsfeedService);
    }

    @Test
    @DisplayName("should reject when the post does not exist")
    void shouldThrowNotFoundException_whenPostDoesNotExist() {
      // Given
      when(postRepository.findById(POST_ID)).thenReturn(Optional.empty());

      // When / Then
      assertThatThrownBy(() -> postService.unacceptAnswer(AUTHOR_ID, POST_ID))
          .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("should reject when the post is not a QNA post")
    void shouldThrowValidationException_whenPostIsNotQna() {
      // Given
      PostEntity post = existingPost(POST_ID, AUTHOR_ID);
      post.setPostType(PostType.REGULAR);
      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(post));

      // When / Then
      assertThatThrownBy(() -> postService.unacceptAnswer(AUTHOR_ID, POST_ID))
          .isInstanceOf(ValidationException.class)
          .hasMessageContaining("Only QNA posts");
      verifyNoInteractions(commentRepository, reputationEventPublisher, newsfeedService);
    }

    @Test
    @DisplayName("should reject when no answer has been accepted yet")
    void shouldThrowValidationException_whenNothingAccepted() {
      // Given
      PostEntity post = existingPost(POST_ID, AUTHOR_ID);
      post.setPostType(PostType.QNA);
      post.setQnaDetails(new QnaDetails(false, null, null));
      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(post));

      // When / Then
      assertThatThrownBy(() -> postService.unacceptAnswer(AUTHOR_ID, POST_ID))
          .isInstanceOf(ValidationException.class)
          .hasMessageContaining("No answer has been accepted");
      verify(postRepository, never()).save(any());
      verifyNoInteractions(commentRepository, reputationEventPublisher, newsfeedService);
    }
  }

  // =====================================================================

  @Nested
  @DisplayName("Hashtag Extraction (processHashtags)")
  class HashtagExtractionTests {

    @Test
    @DisplayName("should not interact with hashtag repository when content has no hashtags")
    void shouldNotInteract_whenNoHashtags() {
      // Given
      CreatePostRequestDto request = createRequest("Hello world", PostVisibility.PUBLIC, null);
      when(userRepository.findById(AUTHOR_ID)).thenReturn(Optional.of(someUser(AUTHOR_ID)));
      when(moderationProperties.isEnabled()).thenReturn(false);

      // When
      postService.createPost(AUTHOR_ID, request);

      // Then
      verifyNoInteractions(hashtagRepository);
    }

    @Test
    @DisplayName("should extract, save and link new hashtags case-insensitively")
    void shouldExtractAndSaveNewHashtags() {
      // Given
      CreatePostRequestDto request =
          createRequest(
              "Learning #Java and #Spring today! Also #JAVA", PostVisibility.PUBLIC, null);
      when(userRepository.findById(AUTHOR_ID)).thenReturn(Optional.of(someUser(AUTHOR_ID)));
      when(moderationProperties.isEnabled()).thenReturn(false);

      when(hashtagRepository.findByNameIn(any())).thenReturn(new java.util.ArrayList<>());

      when(hashtagRepository.saveAll(any()))
          .thenAnswer(
              invocation -> {
                Iterable<HashtagEntity> args = invocation.getArgument(0);
                List<HashtagEntity> list = new java.util.ArrayList<>();
                args.forEach(list::add);
                return list;
              });

      // When
      postService.createPost(AUTHOR_ID, request);

      // Then
      verify(postRepository).save(postCaptor.capture());
      verify(postRepository).saveAndFlush(postCaptor.capture());
      PostEntity savedPost = postCaptor.getValue();

      Set<HashtagEntity> hashtags = savedPost.getHashtags();
      assertThat(hashtags).hasSize(2); // java and spring
      assertThat(hashtags)
          .extracting(HashtagEntity::getName)
          .containsExactlyInAnyOrder("java", "spring");

      assertThat(hashtags).extracting(HashtagEntity::getUsageCount).containsOnly(1);
    }

    @Test
    @DisplayName("should increment usage count of existing hashtags")
    void shouldIncrementExistingHashtags() {
      // Given
      CreatePostRequestDto request = createRequest("I love #java", PostVisibility.PUBLIC, null);
      when(userRepository.findById(AUTHOR_ID)).thenReturn(Optional.of(someUser(AUTHOR_ID)));
      when(moderationProperties.isEnabled()).thenReturn(false);

      HashtagEntity existingTag = new HashtagEntity();
      existingTag.setName("java");
      existingTag.setUsageCount(5);

      when(hashtagRepository.findByNameIn(any()))
          .thenReturn(new java.util.ArrayList<>(List.of(existingTag)));
      when(hashtagRepository.saveAll(any()))
          .thenAnswer(
              invocation -> {
                Iterable<HashtagEntity> args = invocation.getArgument(0);
                List<HashtagEntity> list = new java.util.ArrayList<>();
                args.forEach(list::add);
                return list;
              });

      // When
      postService.createPost(AUTHOR_ID, request);

      // Then
      verify(postRepository).save(postCaptor.capture());
      verify(postRepository).saveAndFlush(postCaptor.capture());
      PostEntity savedPost = postCaptor.getValue();

      assertThat(savedPost.getHashtags()).hasSize(1);
      HashtagEntity tag = savedPost.getHashtags().iterator().next();
      assertThat(tag.getName()).isEqualTo("java");
      assertThat(tag.getUsageCount()).isEqualTo(6); // 5 + 1
    }

    @Test
    @DisplayName("should decrement old tags and increment new tags when updating a post")
    void shouldDecrementOldAndIncrementNewTags_whenUpdatingPost() {
      // Given
      UpdatePostRequestDto request =
          updateRequest("Now I learn #react", PostVisibility.PUBLIC, null);

      PostEntity post = existingPost(POST_ID, AUTHOR_ID);
      HashtagEntity oldTag = new HashtagEntity();
      oldTag.setName("java");
      oldTag.setUsageCount(2);
      post.setHashtags(new HashSet<>(List.of(oldTag)));

      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(post));
      when(userRepository.findById(AUTHOR_ID)).thenReturn(Optional.of(someUser(AUTHOR_ID)));
      when(moderationProperties.isEnabled()).thenReturn(false);

      when(hashtagRepository.findByNameIn(any())).thenReturn(new java.util.ArrayList<>());
      when(hashtagRepository.saveAll(any()))
          .thenAnswer(
              invocation -> {
                Iterable<HashtagEntity> args = invocation.getArgument(0);
                List<HashtagEntity> list = new java.util.ArrayList<>();
                args.forEach(list::add);
                return list;
              });

      // When
      postService.updatePost(AUTHOR_ID, POST_ID, request);

      // Then
      // verify old tag was decremented
      assertThat(oldTag.getUsageCount()).isEqualTo(1);

      verify(postRepository).save(postCaptor.capture());
      PostEntity savedPost = postCaptor.getValue();

      assertThat(savedPost.getHashtags()).hasSize(1);
      HashtagEntity newTag = savedPost.getHashtags().iterator().next();
      assertThat(newTag.getName()).isEqualTo("react");
      assertThat(newTag.getUsageCount()).isEqualTo(1);
    }
  }
}
