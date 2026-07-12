package com.socialapp.posts.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
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
import com.socialapp.posts.entity.EventDetails;
import com.socialapp.posts.entity.PostEntity;
import com.socialapp.posts.entity.PostTagEntity;
import com.socialapp.posts.entity.PostTagId;
import com.socialapp.posts.entity.enums.PostType;
import com.socialapp.posts.entity.enums.PostVisibility;
import com.socialapp.posts.repository.PostRepository;
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
      verify(postRepository, times(2)).save(postCaptor.capture());
      PostEntity saved = postCaptor.getValue();
      assertThat(saved.getModerationStatus()).isEqualTo(ModerationStatus.APPROVED);
      assertThat(saved.getPostType()).isEqualTo(PostType.REGULAR);
      assertThat(saved.getTags()).hasSize(2);
      assertThat(saved.getTags()).extracting(PostTagEntity::getTaggedUserId).containsExactly(2, 3);
      verify(newsfeedService).fanOutPost(any());
      verifyNoInteractions(moderationEventPublisher);
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
      verify(postRepository, times(2)).save(postCaptor.capture());
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
      verify(postRepository, times(2)).save(postCaptor.capture());
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
      verify(postRepository, times(2)).save(postCaptor.capture());
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
      verify(postRepository, times(2)).save(postCaptor.capture());
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
      verify(postRepository, times(2)).save(postCaptor.capture());
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
}
