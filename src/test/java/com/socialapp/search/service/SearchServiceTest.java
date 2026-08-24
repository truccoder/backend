package com.socialapp.search.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import com.socialapp.blocks.service.BlockQueryService;
import com.socialapp.bookstore.entity.BookEntity;
import com.socialapp.bookstore.repository.BookRepository;
import com.socialapp.bookstore.service.BookStorageService;
import com.socialapp.moderation.enums.ModerationStatus;
import com.socialapp.posts.entity.ArticleDetails;
import com.socialapp.posts.entity.CodeSnippetDetails;
import com.socialapp.posts.entity.EventDetails;
import com.socialapp.posts.entity.LinkDetails;
import com.socialapp.posts.entity.PollDetails;
import com.socialapp.posts.entity.PostEntity;
import com.socialapp.posts.entity.QnaDetails;
import com.socialapp.posts.entity.QuizDetails;
import com.socialapp.posts.entity.QuizQuestion;
import com.socialapp.posts.entity.enums.PostType;
import com.socialapp.posts.entity.enums.PostVisibility;
import com.socialapp.posts.repository.PostRepository;
import com.socialapp.search.dto.BookDto;
import com.socialapp.search.dto.PostDto;
import com.socialapp.search.dto.SearchResult;
import com.socialapp.search.dto.UserDto;
import com.socialapp.security.entity.UserEntity;
import com.socialapp.security.repository.UserRepository;

/**
 * Component (unit) tests for {@link SearchService}, per ISTQB CTFL v4.0.1 (Section 2.2.1
 * component testing, Section 5.1.6 test pyramid, Section 4.3.2 branch testing, Section 2.1.3 BDD
 * Given/When/Then) — see {@code PostServiceTest} for the full rationale.
 */
@ExtendWith(MockitoExtension.class)
class SearchServiceTest {

  private static final Integer CURRENT_USER_ID = 1;
  private static final Integer FRIEND_ID = 2;
  private static final Integer STRANGER_ID = 3;

  @Mock private BlockQueryService blockQueryService;
  @Mock private UserRepository userRepository;
  @Mock private PostRepository postRepository;
  @Mock private BookRepository bookRepository;
  @Mock private BookStorageService bookStorageService;

  @InjectMocks private SearchService searchService;

  private static UserEntity user(Integer id, String fullName) {
    UserEntity user = new UserEntity();
    user.setId(id);
    user.setUsername("user_" + id);
    user.setFullName(fullName);
    return user;
  }

  private static PostEntity post(
      Integer id, Integer authorId, PostVisibility visibility, PostType type) {
    PostEntity post = new PostEntity();
    post.setId(id);
    post.setAuthorId(authorId);
    post.setVisibility(visibility);
    post.setPostType(type);
    post.setContent("content " + id);
    // A post only reaches search once moderation has cleared it; the book branch checks this in
    // Java (SearchService#isVisibleToViewer) the way the post branch checks it in SQL.
    post.setModerationStatus(ModerationStatus.APPROVED);
    return post;
  }

  private static PostEntity postWithStatus(
      Integer id, Integer authorId, PostVisibility visibility, ModerationStatus status) {
    PostEntity post = post(id, authorId, visibility, PostType.BOOK);
    post.setModerationStatus(status);
    return post;
  }

  // =====================================================================
  // searchUsers
  // =====================================================================

  @Nested
  @DisplayName("searchUsers")
  class SearchUsersTests {

    @Test
    @DisplayName("should return the mapped users with paging metadata")
    void shouldReturnMappedUsers_withPagingMetadata() {
      // Given
      Page<UserEntity> page = new PageImpl<>(List.of(user(2, "Alice")), PageRequest.of(0, 10), 1);
      when(userRepository.search(
              eq("alice"), eq(List.of(FRIEND_ID)), eq(List.of(-1)), eq(PageRequest.of(0, 10))))
          .thenReturn(page);

      // When
      SearchResult<UserDto> result =
          searchService.searchUsers("alice", 1, 10, CURRENT_USER_ID, List.of(FRIEND_ID));

      // Then
      assertThat(result.getItems()).hasSize(1);
      assertThat(result.getItems().get(0).getFullName()).isEqualTo("Alice");
      assertThat(result.getTotalHits()).isEqualTo(1);
      assertThat(result.getPage()).isEqualTo(1);
      assertThat(result.getSize()).isEqualTo(10);
    }

    @Test
    @DisplayName("should use a sentinel friend id when the friend list is null")
    void shouldUseSentinelFriendId_whenFriendIdsIsNull() {
      // Given
      when(userRepository.search(any(), eq(List.of(-1)), any(), any())).thenReturn(Page.empty());

      // When
      SearchResult<UserDto> result = searchService.searchUsers("q", 1, 10, CURRENT_USER_ID, null);

      // Then — the stub above only matches a call with the sentinel [-1]; a mismatched call would
      // return null from the unstubbed default and NPE before reaching this assertion.
      assertThat(result.getItems()).isEmpty();
    }

    @Test
    @DisplayName("should use a sentinel friend id when the friend list is empty")
    void shouldUseSentinelFriendId_whenFriendIdsIsEmpty() {
      // Given
      when(userRepository.search(any(), eq(List.of(-1)), any(), any())).thenReturn(Page.empty());

      // When
      SearchResult<UserDto> result =
          searchService.searchUsers("q", 1, 10, CURRENT_USER_ID, List.of());

      // Then
      assertThat(result.getItems()).isEmpty();
    }
  }

  // =====================================================================
  // searchPostsWithBookInfo
  // =====================================================================

  @Nested
  @DisplayName("searchPostsWithBookInfo")
  class SearchPostsWithBookInfoTests {

    private void stubEmptyBookSearch() {
      when(bookRepository.search(any(), any())).thenReturn(Page.empty());
    }

    @Test
    @DisplayName("should return content matches when no book titles match")
    void shouldReturnContentMatches_whenNoBookMatches() {
      // Given
      PostEntity matched = post(10, CURRENT_USER_ID, PostVisibility.PUBLIC, PostType.REGULAR);
      when(postRepository.searchByContentOrEventName(
              any(), eq(CURRENT_USER_ID), any(), any(), any()))
          .thenReturn(new PageImpl<>(List.of(matched)));
      stubEmptyBookSearch();
      when(userRepository.findAllById(List.of(CURRENT_USER_ID)))
          .thenReturn(List.of(user(CURRENT_USER_ID, "Me")));

      // When
      List<PostDto> result =
          searchService.searchPostsWithBookInfo("q", 10, CURRENT_USER_ID, List.of(FRIEND_ID));

      // Then
      assertThat(result).extracting(PostDto::getId).containsExactly(10);
      verify(postRepository, never()).findAllById(any());
    }

    @Test
    @DisplayName("should carry the author's username, so a search result can link to a profile")
    void shouldCarryAuthorUsername() {
      // Given — the same gap the feed had: a name rendered on a result card with nowhere to go,
      // because the public profile route is keyed by username and no endpoint maps an id to one
      PostEntity matched = post(10, CURRENT_USER_ID, PostVisibility.PUBLIC, PostType.REGULAR);
      when(postRepository.searchByContentOrEventName(
              any(), eq(CURRENT_USER_ID), any(), any(), any()))
          .thenReturn(new PageImpl<>(List.of(matched)));
      stubEmptyBookSearch();
      when(userRepository.findAllById(List.of(CURRENT_USER_ID)))
          .thenReturn(List.of(user(CURRENT_USER_ID, "Me")));

      // When
      List<PostDto> result =
          searchService.searchPostsWithBookInfo("q", 10, CURRENT_USER_ID, List.of(FRIEND_ID));

      // Then — taken from the authorsById batch the other author fields already use
      assertThat(result.get(0).getAuthorUsername()).isEqualTo("user_" + CURRENT_USER_ID);
    }

    @Test
    @DisplayName("should include a book match authored by the current viewer")
    void shouldIncludeBookMatch_whenAuthoredByViewer() {
      // Given
      when(postRepository.searchByContentOrEventName(any(), any(), any(), any(), any()))
          .thenReturn(Page.empty());
      BookEntity book = BookEntity.builder().id(5).postId(20).title("Java Basics").build();
      when(bookRepository.search(any(), any())).thenReturn(new PageImpl<>(List.of(book)));
      PostEntity bookPost = post(20, CURRENT_USER_ID, PostVisibility.PRIVATE, PostType.BOOK);
      when(postRepository.findAllById(List.of(20))).thenReturn(List.of(bookPost));
      when(userRepository.findAllById(List.of(CURRENT_USER_ID)))
          .thenReturn(List.of(user(CURRENT_USER_ID, "Me")));
      when(bookRepository.findByPostIdIn(List.of(20))).thenReturn(List.of(book));

      // When
      List<PostDto> result =
          searchService.searchPostsWithBookInfo("java", 10, CURRENT_USER_ID, List.of());

      // Then
      assertThat(result).extracting(PostDto::getId).containsExactly(20);
      assertThat(result.get(0).getBook().getTitle()).isEqualTo("Java Basics");
    }

    @Test
    @DisplayName("should include a public book match authored by someone else")
    void shouldIncludeBookMatch_whenPublic() {
      // Given
      when(postRepository.searchByContentOrEventName(any(), any(), any(), any(), any()))
          .thenReturn(Page.empty());
      BookEntity book = BookEntity.builder().id(5).postId(20).title("Java Basics").build();
      when(bookRepository.search(any(), any())).thenReturn(new PageImpl<>(List.of(book)));
      PostEntity bookPost = post(20, STRANGER_ID, PostVisibility.PUBLIC, PostType.BOOK);
      when(postRepository.findAllById(List.of(20))).thenReturn(List.of(bookPost));
      when(userRepository.findAllById(List.of(STRANGER_ID)))
          .thenReturn(List.of(user(STRANGER_ID, "Stranger")));
      when(bookRepository.findByPostIdIn(List.of(20))).thenReturn(List.of(book));

      // When
      List<PostDto> result =
          searchService.searchPostsWithBookInfo("java", 10, CURRENT_USER_ID, List.of());

      // Then
      assertThat(result).hasSize(1);
    }

    @Test
    @DisplayName(
        "should include a friends-only book match when the viewer is a friend of the author")
    void shouldIncludeBookMatch_whenFriendsOnlyAndViewerIsFriend() {
      // Given
      when(postRepository.searchByContentOrEventName(any(), any(), any(), any(), any()))
          .thenReturn(Page.empty());
      BookEntity book = BookEntity.builder().id(5).postId(20).title("Java Basics").build();
      when(bookRepository.search(any(), any())).thenReturn(new PageImpl<>(List.of(book)));
      PostEntity bookPost = post(20, FRIEND_ID, PostVisibility.FRIENDS, PostType.BOOK);
      when(postRepository.findAllById(List.of(20))).thenReturn(List.of(bookPost));
      when(userRepository.findAllById(List.of(FRIEND_ID)))
          .thenReturn(List.of(user(FRIEND_ID, "Friend")));
      when(bookRepository.findByPostIdIn(List.of(20))).thenReturn(List.of(book));

      // When
      List<PostDto> result =
          searchService.searchPostsWithBookInfo("java", 10, CURRENT_USER_ID, List.of(FRIEND_ID));

      // Then
      assertThat(result).hasSize(1);
    }

    @Test
    @DisplayName("should exclude a friends-only book match when the viewer is not a friend")
    void shouldExcludeBookMatch_whenFriendsOnlyAndViewerNotFriend() {
      // Given
      when(postRepository.searchByContentOrEventName(any(), any(), any(), any(), any()))
          .thenReturn(Page.empty());
      BookEntity book = BookEntity.builder().id(5).postId(20).title("Java Basics").build();
      when(bookRepository.search(any(), any())).thenReturn(new PageImpl<>(List.of(book)));
      PostEntity bookPost = post(20, STRANGER_ID, PostVisibility.FRIENDS, PostType.BOOK);
      when(postRepository.findAllById(List.of(20))).thenReturn(List.of(bookPost));

      // When
      List<PostDto> result =
          searchService.searchPostsWithBookInfo("java", 10, CURRENT_USER_ID, List.of(FRIEND_ID));

      // Then
      assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("should exclude a private book match not authored by the viewer")
    void shouldExcludePrivateBookMatch_whenNotOwnedByViewer() {
      // Given
      when(postRepository.searchByContentOrEventName(any(), any(), any(), any(), any()))
          .thenReturn(Page.empty());
      BookEntity book = BookEntity.builder().id(5).postId(20).title("Java Basics").build();
      when(bookRepository.search(any(), any())).thenReturn(new PageImpl<>(List.of(book)));
      PostEntity bookPost = post(20, STRANGER_ID, PostVisibility.PRIVATE, PostType.BOOK);
      when(postRepository.findAllById(List.of(20))).thenReturn(List.of(bookPost));

      // When
      List<PostDto> result =
          searchService.searchPostsWithBookInfo("java", 10, CURRENT_USER_ID, List.of(FRIEND_ID));

      // Then
      assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("should not duplicate a post matched by both content and its book")
    void shouldNotDuplicate_whenMatchedByBothContentAndBook() {
      // Given
      PostEntity matched = post(20, CURRENT_USER_ID, PostVisibility.PUBLIC, PostType.BOOK);
      when(postRepository.searchByContentOrEventName(any(), any(), any(), any(), any()))
          .thenReturn(new PageImpl<>(List.of(matched)));
      BookEntity book = BookEntity.builder().id(5).postId(20).title("Java Basics").build();
      when(bookRepository.search(any(), any())).thenReturn(new PageImpl<>(List.of(book)));
      when(postRepository.findAllById(List.of(20))).thenReturn(List.of(matched));
      when(userRepository.findAllById(List.of(CURRENT_USER_ID)))
          .thenReturn(List.of(user(CURRENT_USER_ID, "Me")));
      when(bookRepository.findByPostIdIn(List.of(20))).thenReturn(List.of(book));

      // When
      List<PostDto> result =
          searchService.searchPostsWithBookInfo("java", 10, CURRENT_USER_ID, List.of());

      // Then
      assertThat(result).hasSize(1);
    }

    @Test
    @DisplayName("should map every optional field when present")
    void shouldMapOptionalFields_whenPresent() {
      // Given
      PostEntity eventPost = post(10, CURRENT_USER_ID, PostVisibility.PUBLIC, PostType.EVENT);
      EventDetails details = new EventDetails();
      details.setEventTitle("Launch Party");
      eventPost.setEventDetails(details);
      eventPost.setCreatedAt(OffsetDateTime.now());
      when(postRepository.searchByContentOrEventName(any(), any(), any(), any(), any()))
          .thenReturn(new PageImpl<>(List.of(eventPost)));
      stubEmptyBookSearch();
      when(userRepository.findAllById(List.of(CURRENT_USER_ID)))
          .thenReturn(List.of(user(CURRENT_USER_ID, "Me")));

      // When
      List<PostDto> result =
          searchService.searchPostsWithBookInfo("q", 10, CURRENT_USER_ID, List.of());

      // Then
      PostDto dto = result.get(0);
      assertThat(dto.getEventName()).isEqualTo("Launch Party");
      assertThat(dto.getAuthorFullName()).isEqualTo("Me");
      assertThat(dto.getVisibility()).isEqualTo("PUBLIC");
      assertThat(dto.getCreatedAt()).isNotNull();
      assertThat(dto.getBook()).isNull();
    }

    @Test
    @DisplayName("should hand back createdAt with its offset intact, not a zone-less local time")
    void shouldKeepOffsetOnCreatedAt() {
      // Given — B9: created_at is a timestamptz and the DTO used to declare LocalDateTime, so the
      // service dropped the zone and every client re-read the string as its own local time.
      OffsetDateTime createdAt = OffsetDateTime.parse("2026-07-27T18:24:54Z");
      PostEntity postEntity = post(10, CURRENT_USER_ID, PostVisibility.PUBLIC, PostType.REGULAR);
      postEntity.setCreatedAt(createdAt);
      when(postRepository.searchByContentOrEventName(any(), any(), any(), any(), any()))
          .thenReturn(new PageImpl<>(List.of(postEntity)));
      stubEmptyBookSearch();
      when(userRepository.findAllById(List.of(CURRENT_USER_ID)))
          .thenReturn(List.of(user(CURRENT_USER_ID, "Me")));

      // When
      List<PostDto> result =
          searchService.searchPostsWithBookInfo("q", 10, CURRENT_USER_ID, List.of());

      // Then
      assertThat(result.get(0).getCreatedAt()).isEqualTo(createdAt);
    }

    @Test
    @DisplayName("should label the author's elite score with the matching reputation level")
    void shouldMapAuthorLevelName() {
      // Given — B15: the payload carried the raw score with no label, so the chip in a search
      // result had nothing to render but a number.
      PostEntity postEntity = post(10, CURRENT_USER_ID, PostVisibility.PUBLIC, PostType.REGULAR);
      when(postRepository.searchByContentOrEventName(any(), any(), any(), any(), any()))
          .thenReturn(new PageImpl<>(List.of(postEntity)));
      stubEmptyBookSearch();
      UserEntity author = user(CURRENT_USER_ID, "Me");
      author.setEliteScore(1_200);
      when(userRepository.findAllById(List.of(CURRENT_USER_ID))).thenReturn(List.of(author));

      // When
      List<PostDto> result =
          searchService.searchPostsWithBookInfo("q", 10, CURRENT_USER_ID, List.of());

      // Then — 1,200 sits between PRACTITIONER (1,000) and EXPERT (5,000)
      assertThat(result.get(0).getAuthorEliteScore()).isEqualTo(1_200);
      assertThat(result.get(0).getAuthorLevelName()).isEqualTo("Practitioner");
    }

    @Test
    @DisplayName("should map all six detail blocks so non-text posts are not reduced to text")
    void shouldMapAllDetailBlocks() {
      // Given — PostDto declared these six all along and toPostDtos never set any of them, so a
      // quiz/poll/code post came back from search as content-only (B8, the same omission as
      // NewsfeedService.fanOutPost)
      PostEntity qnaPost = post(10, CURRENT_USER_ID, PostVisibility.PUBLIC, PostType.QNA);
      QuizQuestion quizQuestion = new QuizQuestion();
      quizQuestion.setQuestion("2 + 2?");
      quizQuestion.setOptions(List.of("3", "4"));
      quizQuestion.setCorrectOptionIndex(1);
      QuizDetails quiz = new QuizDetails();
      quiz.setQuestions(List.of(quizQuestion));
      qnaPost.setQuizDetails(quiz);
      qnaPost.setCodeSnippetDetails(new CodeSnippetDetails());
      qnaPost.setArticleDetails(new ArticleDetails());
      qnaPost.setQnaDetails(new QnaDetails());
      qnaPost.setPollDetails(new PollDetails());
      qnaPost.setLinkDetails(new LinkDetails());
      when(postRepository.searchByContentOrEventName(any(), any(), any(), any(), any()))
          .thenReturn(new PageImpl<>(List.of(qnaPost)));
      stubEmptyBookSearch();
      when(userRepository.findAllById(List.of(CURRENT_USER_ID)))
          .thenReturn(List.of(user(CURRENT_USER_ID, "Me")));

      // When
      List<PostDto> result =
          searchService.searchPostsWithBookInfo("q", 10, CURRENT_USER_ID, List.of());

      // Then
      PostDto dto = result.get(0);
      assertThat(dto.getQuizDetails()).isNotNull();
      // B5: search renders the quiz too, so it must strip the answers just like the feed does
      assertThat(dto.getQuizDetails().getQuestions().get(0).getQuestion()).isEqualTo("2 + 2?");
      assertThat(dto.getCodeSnippetDetails()).isNotNull();
      assertThat(dto.getArticleDetails()).isNotNull();
      assertThat(dto.getQnaDetails()).isNotNull();
      assertThat(dto.getPollDetails()).isNotNull();
      assertThat(dto.getLinkDetails()).isNotNull();
    }

    @Test
    @DisplayName("should map every optional field to null when absent")
    void shouldMapOptionalFields_toNullWhenAbsent() {
      // Given
      PostEntity plainPost = post(10, CURRENT_USER_ID, null, PostType.REGULAR);
      when(postRepository.searchByContentOrEventName(any(), any(), any(), any(), any()))
          .thenReturn(new PageImpl<>(List.of(plainPost)));
      stubEmptyBookSearch();
      when(userRepository.findAllById(List.of(CURRENT_USER_ID))).thenReturn(List.of());

      // When
      List<PostDto> result =
          searchService.searchPostsWithBookInfo("q", 10, CURRENT_USER_ID, List.of());

      // Then
      PostDto dto = result.get(0);
      assertThat(dto.getEventName()).isNull();
      assertThat(dto.getAuthorFullName()).isNull();
      assertThat(dto.getAuthorProfilePictureUrl()).isNull();
      assertThat(dto.getVisibility()).isNull();
      assertThat(dto.getCreatedAt()).isNull();
      assertThat(dto.getBook()).isNull();
    }

    @Test
    @DisplayName("should limit the merged results to the requested size")
    void shouldLimitResultsToRequestedSize() {
      // Given
      PostEntity post1 = post(1, CURRENT_USER_ID, PostVisibility.PUBLIC, PostType.REGULAR);
      PostEntity post2 = post(2, CURRENT_USER_ID, PostVisibility.PUBLIC, PostType.REGULAR);
      when(postRepository.searchByContentOrEventName(any(), any(), any(), any(), any()))
          .thenReturn(new PageImpl<>(List.of(post1, post2)));
      stubEmptyBookSearch();
      when(userRepository.findAllById(List.of(CURRENT_USER_ID)))
          .thenReturn(List.of(user(CURRENT_USER_ID, "Me")));

      // When
      List<PostDto> result =
          searchService.searchPostsWithBookInfo("q", 1, CURRENT_USER_ID, List.of());

      // Then
      assertThat(result).hasSize(1);
    }
  }

  // =====================================================================
  // block filtering
  // =====================================================================

  @Nested
  @DisplayName("block filtering")
  class BlockFilteringTests {

    @Test
    @DisplayName("should pass the caller's block set to the user query as an exclusion")
    void shouldExcludeBlockedUsersFromUserSearch() {
      // Given
      when(blockQueryService.blockedPairIds(CURRENT_USER_ID)).thenReturn(java.util.Set.of(9));
      when(userRepository.search(any(), any(), eq(java.util.Set.of(9)), any()))
          .thenReturn(Page.empty());

      // When
      SearchResult<UserDto> result =
          searchService.searchUsers("q", 1, 10, CURRENT_USER_ID, List.of());

      // Then — the stub only matches a call carrying the block set; a search box that still finds
      // a blocked user's profile makes the block cosmetic
      assertThat(result.getItems()).isEmpty();
    }

    @Test
    @DisplayName("should drop a book-matched post whose author is blocked")
    void shouldDropBlockedAuthorsBookMatch() {
      // Given: the book branch has no author column of its own to filter on in SQL
      when(blockQueryService.blockedPairIds(CURRENT_USER_ID))
          .thenReturn(java.util.Set.of(STRANGER_ID));
      when(postRepository.searchByContentOrEventName(any(), any(), any(), any(), any()))
          .thenReturn(Page.empty());
      BookEntity book = BookEntity.builder().id(5).postId(20).title("Java Basics").build();
      when(bookRepository.search(any(), any())).thenReturn(new PageImpl<>(List.of(book)));
      PostEntity blockedAuthorsPost = post(20, STRANGER_ID, PostVisibility.PUBLIC, PostType.BOOK);
      when(postRepository.findAllById(List.of(20))).thenReturn(List.of(blockedAuthorsPost));

      // When
      List<PostDto> posts =
          searchService.searchPostsWithBookInfo("java", 10, CURRENT_USER_ID, List.of());

      // Then
      assertThat(posts).isEmpty();
    }
  }

  // =====================================================================
  // searchBooks
  // =====================================================================

  @Nested
  @DisplayName("searchBooks")
  class SearchBooksTests {

    private BookEntity book(Integer id, Integer postId, String title) {
      return BookEntity.builder()
          .id(id)
          .postId(postId)
          .authorId(FRIEND_ID)
          .title(title)
          .description("about " + title)
          .isFree(true)
          .price(0L)
          .build();
    }

    @Test
    @DisplayName("should return a matched book whose post the caller may read")
    void shouldReturnVisibleBook() {
      // Given
      when(blockQueryService.blockedPairIds(CURRENT_USER_ID)).thenReturn(java.util.Set.of());
      when(bookRepository.search(any(), any()))
          .thenReturn(new PageImpl<>(List.of(book(5, 20, "Java Basics"))));
      when(postRepository.findAllById(List.of(20)))
          .thenReturn(List.of(post(20, FRIEND_ID, PostVisibility.PUBLIC, PostType.BOOK)));
      when(bookStorageService.getCoverUrl(any())).thenReturn(null);

      // When
      List<BookDto> books = searchService.searchBooks("java", 10, CURRENT_USER_ID, List.of());

      // Then — the shelf the frontend could not draw a tab for while this branch did not exist
      assertThat(books).extracting(BookDto::getId).containsExactly(5);
      assertThat(books.get(0).getTitle()).isEqualTo("Java Basics");
    }

    @Test
    @DisplayName("should drop a book whose post has been taken down")
    void shouldDropRejectedBookPost() {
      // Given: a PUBLIC book post an admin has REJECTED. The post branch filters this in SQL;
      // this branch resolves visibility in Java and was missing the same check, so a taken-down
      // book stayed findable through the book tab.
      when(blockQueryService.blockedPairIds(CURRENT_USER_ID)).thenReturn(java.util.Set.of());
      when(bookRepository.search(any(), any()))
          .thenReturn(new PageImpl<>(List.of(book(5, 20, "Java Basics"))));
      when(postRepository.findAllById(List.of(20)))
          .thenReturn(
              List.of(
                  postWithStatus(20, FRIEND_ID, PostVisibility.PUBLIC, ModerationStatus.REJECTED)));

      // When
      List<BookDto> books = searchService.searchBooks("java", 10, CURRENT_USER_ID, List.of());

      // Then
      assertThat(books).isEmpty();
    }

    @Test
    @DisplayName("should still show the author their own book while it awaits review")
    void shouldKeepOwnPendingBookPost() {
      // Given: the author exemption, matching PostVisibilityService.isVisibleTo and the SQL
      when(blockQueryService.blockedPairIds(CURRENT_USER_ID)).thenReturn(java.util.Set.of());
      when(bookRepository.search(any(), any()))
          .thenReturn(new PageImpl<>(List.of(book(5, 20, "Java Basics"))));
      when(postRepository.findAllById(List.of(20)))
          .thenReturn(
              List.of(
                  postWithStatus(
                      20,
                      CURRENT_USER_ID,
                      PostVisibility.PUBLIC,
                      ModerationStatus.PENDING_REVIEW)));
      when(bookStorageService.getCoverUrl(any())).thenReturn(null);

      // When
      List<BookDto> books = searchService.searchBooks("java", 10, CURRENT_USER_ID, List.of());

      // Then
      assertThat(books).extracting(BookDto::getId).containsExactly(5);
    }

    @Test
    @DisplayName("should not query posts at all when nothing matched")
    void shouldShortCircuitOnNoMatches() {
      // Given
      when(bookRepository.search(any(), any())).thenReturn(Page.empty());

      // When
      List<BookDto> books = searchService.searchBooks("nothing", 10, CURRENT_USER_ID, List.of());

      // Then
      assertThat(books).isEmpty();
      verify(postRepository, never()).findAllById(any());
    }

    @Test
    @DisplayName("should drop a book whose author has blocked, or been blocked by, the caller")
    void shouldDropBlockedAuthorsBook() {
      // Given — the same rule the inline book branch applies, because the two lists must not
      // disagree about whether a book exists
      when(blockQueryService.blockedPairIds(CURRENT_USER_ID))
          .thenReturn(java.util.Set.of(STRANGER_ID));
      when(bookRepository.search(any(), any()))
          .thenReturn(new PageImpl<>(List.of(book(5, 20, "Java Basics"))));
      when(postRepository.findAllById(List.of(20)))
          .thenReturn(List.of(post(20, STRANGER_ID, PostVisibility.PUBLIC, PostType.BOOK)));

      // When
      List<BookDto> books = searchService.searchBooks("java", 10, CURRENT_USER_ID, List.of());

      // Then
      assertThat(books).isEmpty();
    }

    @Test
    @DisplayName("should drop a FRIENDS-only book when the caller is not a friend")
    void shouldDropFriendsOnlyBookForStranger() {
      // Given — EP: the visibility of the post is the book's visibility; t_books has none of its
      // own
      when(blockQueryService.blockedPairIds(CURRENT_USER_ID)).thenReturn(java.util.Set.of());
      when(bookRepository.search(any(), any()))
          .thenReturn(new PageImpl<>(List.of(book(5, 20, "Java Basics"))));
      when(postRepository.findAllById(List.of(20)))
          .thenReturn(List.of(post(20, STRANGER_ID, PostVisibility.FRIENDS, PostType.BOOK)));

      // When
      List<BookDto> books = searchService.searchBooks("java", 10, CURRENT_USER_ID, List.of());

      // Then
      assertThat(books).isEmpty();
    }

    @Test
    @DisplayName("should keep a FRIENDS-only book when the caller is a friend")
    void shouldKeepFriendsOnlyBookForFriend() {
      // Given
      when(blockQueryService.blockedPairIds(CURRENT_USER_ID)).thenReturn(java.util.Set.of());
      when(bookRepository.search(any(), any()))
          .thenReturn(new PageImpl<>(List.of(book(5, 20, "Java Basics"))));
      when(postRepository.findAllById(List.of(20)))
          .thenReturn(List.of(post(20, FRIEND_ID, PostVisibility.FRIENDS, PostType.BOOK)));
      when(bookStorageService.getCoverUrl(any())).thenReturn(null);

      // When
      List<BookDto> books =
          searchService.searchBooks("java", 10, CURRENT_USER_ID, List.of(FRIEND_ID));

      // Then
      assertThat(books).extracting(BookDto::getId).containsExactly(5);
    }

    @Test
    @DisplayName("should drop a book whose post row is gone")
    void shouldDropBookWithoutAReadablePost() {
      // Given — EP: a book nobody can trace back to an author's post. Showing it on a title
      // match would put content in front of a reader with no visibility rule behind it.
      when(blockQueryService.blockedPairIds(CURRENT_USER_ID)).thenReturn(java.util.Set.of());
      when(bookRepository.search(any(), any()))
          .thenReturn(new PageImpl<>(List.of(book(5, 20, "Java Basics"))));
      when(postRepository.findAllById(List.of(20))).thenReturn(List.of());

      // When
      List<BookDto> books = searchService.searchBooks("java", 10, CURRENT_USER_ID, List.of());

      // Then
      assertThat(books).isEmpty();
    }
  }
}
