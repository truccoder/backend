package com.socialapp.search.service;

import java.util.*;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import com.socialapp.blocks.service.BlockQueryService;
import com.socialapp.bookstore.entity.BookEntity;
import com.socialapp.bookstore.repository.BookRepository;
import com.socialapp.bookstore.service.BookStorageService;
import com.socialapp.moderation.enums.ModerationStatus;
import com.socialapp.posts.dto.PublicQuizDetailsDto;
import com.socialapp.posts.entity.PostEntity;
import com.socialapp.posts.entity.enums.PostType;
import com.socialapp.posts.entity.enums.PostVisibility;
import com.socialapp.posts.repository.PostRepository;
import com.socialapp.reputation.RepLevel;
import com.socialapp.search.dto.BookDto;
import com.socialapp.search.dto.PostDto;
import com.socialapp.search.dto.SearchResult;
import com.socialapp.search.dto.UserDto;
import com.socialapp.search.util.SearchQuerySanitizer;
import com.socialapp.security.entity.UserEntity;
import com.socialapp.security.repository.UserRepository;

import lombok.RequiredArgsConstructor;

/**
 * Searches directly against Postgres (t_users, t_posts, t_books) rather than a separate search
 * index. Matches are diacritics-insensitive (Postgres {@code unaccent(...)} on both sides) plain
 * substring — no relevance scoring/fuzzy typo tolerance like a real search engine would give.
 *
 * <p>Three branches, one per result list in {@code SearchResponse}: people, posts, books. Each is
 * its own public method taking the same viewer context, and each applies the same two filters —
 * blocks both ways, and post visibility — so no branch can surface something another one hides.
 */
@Service
@RequiredArgsConstructor
public class SearchService {
  private final BlockQueryService blockQueryService;
  private final UserRepository userRepository;
  private final PostRepository postRepository;
  private final BookRepository bookRepository;
  private final BookStorageService bookStorageService;

  public SearchResult<UserDto> searchUsers(
      String query, int page, int size, Integer currentUserId, List<Integer> friendIds) {
    Page<UserEntity> result =
        userRepository.search(
            SearchQuerySanitizer.sanitize(query),
            safeFriendIds(friendIds),
            excludedIds(blockQueryService.blockedPairIds(currentUserId)),
            PageRequest.of(page - 1, size));
    return toSearchResult(result, this::toUserDtos, page, size);
  }

  /**
   * Posts where either the post itself (content/event name) or its attached book's
   * title/description matched — book posts found via their book carry {@code book} info inline
   * instead of appearing as a separate result type.
   */
  public List<PostDto> searchPostsWithBookInfo(
      String query, int size, Integer currentUserId, List<Integer> friendIds) {
    String sanitized = SearchQuerySanitizer.sanitize(query);
    List<Integer> safeFriends = safeFriendIds(friendIds);
    Set<Integer> blockedIds = blockQueryService.blockedPairIds(currentUserId);

    List<PostEntity> contentMatches =
        postRepository
            .searchByContentOrEventName(
                sanitized,
                currentUserId,
                safeFriends,
                excludedIds(blockedIds),
                PageRequest.of(0, size))
            .getContent();

    List<Integer> bookMatchedPostIds =
        bookRepository.search(sanitized, PageRequest.of(0, size)).getContent().stream()
            .map(BookEntity::getPostId)
            .filter(Objects::nonNull)
            .toList();
    // The book table has no author of its own to filter on in SQL, so the block check for this
    // branch happens here, on the posts the matched books belong to.
    List<PostEntity> bookMatches =
        bookMatchedPostIds.isEmpty()
            ? List.of()
            : postRepository.findAllById(bookMatchedPostIds).stream()
                .filter(p -> !blockedIds.contains(p.getAuthorId()))
                .filter(p -> isVisibleToViewer(p, currentUserId, safeFriends))
                .toList();

    Map<Integer, PostEntity> merged = new LinkedHashMap<>();
    contentMatches.forEach(p -> merged.put(p.getId(), p));
    bookMatches.forEach(p -> merged.putIfAbsent(p.getId(), p));

    return toPostDtos(merged.values().stream().limit(size).toList());
  }

  /**
   * Books whose title or description matched, as their own result list.
   *
   * <p>Filtered by the visibility of the <b>post</b> the book was published as, not by anything on
   * the book row: {@code t_books} has no visibility column of its own, and every book is created
   * through {@code PostService.createBookPost}, so the post is the authority on who may see it.
   * That is the same rule {@link #searchPostsWithBookInfo} applies to its book branch, deliberately
   * — the two lists must not disagree about whether a given book exists.
   *
   * <p>This repeats the {@code bookRepository.search} that {@link #searchPostsWithBookInfo} also
   * runs, one extra indexed lookup per search. The alternative was a combined entry point that
   * returns the whole {@link com.socialapp.search.dto.SearchResponse}, which would make the two
   * branches impossible to call — or test — apart. The controller composes; each branch stays a
   * question you can ask on its own.
   */
  public List<BookDto> searchBooks(
      String query, int size, Integer currentUserId, List<Integer> friendIds) {
    List<BookEntity> matches =
        bookRepository
            .search(SearchQuerySanitizer.sanitize(query), PageRequest.of(0, size))
            .getContent();

    if (matches.isEmpty()) {
      return List.of();
    }

    List<Integer> safeFriends = safeFriendIds(friendIds);
    Set<Integer> blockedIds = blockQueryService.blockedPairIds(currentUserId);

    Map<Integer, PostEntity> postsById = new HashMap<>();
    postRepository
        .findAllById(
            matches.stream()
                .map(BookEntity::getPostId)
                .filter(Objects::nonNull)
                .distinct()
                .toList())
        .forEach(post -> postsById.put(post.getId(), post));

    return matches.stream()
        .filter(book -> isBookVisible(book, postsById, blockedIds, currentUserId, safeFriends))
        .map(this::toBookDto)
        .toList();
  }

  /**
   * A book with no readable post is dropped rather than shown. {@code postId} is non-null for every
   * book this application can create, so a null here means a row that predates that rule or was
   * inserted by hand — and a book nobody can trace back to an author's post is not something to put
   * in front of a reader on the strength of a title match.
   */
  private boolean isBookVisible(
      BookEntity book,
      Map<Integer, PostEntity> postsById,
      Set<Integer> blockedIds,
      Integer currentUserId,
      List<Integer> friendIds) {
    PostEntity post = Objects.isNull(book.getPostId()) ? null : postsById.get(book.getPostId());
    return Objects.nonNull(post)
        && !blockedIds.contains(post.getAuthorId())
        && isVisibleToViewer(post, currentUserId, friendIds);
  }

  /**
   * The book branch's in-Java copy of the post visibility rule.
   *
   * <p>Moderation is checked before visibility, mirroring {@code PostVisibilityService.isVisibleTo}
   * and the SQL in {@code PostRepository.searchByContentOrEventName}: a post that is PENDING or
   * REJECTED has not been cleared for an audience, however public its visibility column says it
   * is. This check was missing here for the same reason it was missing from the SQL — the two were
   * written together — so a taken-down book post stayed findable through the book branch.
   */
  private boolean isVisibleToViewer(
      PostEntity post, Integer currentUserId, List<Integer> friendIds) {
    if (post.getAuthorId().equals(currentUserId)) {
      return true;
    }
    if (!ModerationStatus.APPROVED.equals(post.getModerationStatus())) {
      return false;
    }
    if (PostVisibility.PUBLIC.equals(post.getVisibility())) {
      return true;
    }
    return PostVisibility.FRIENDS.equals(post.getVisibility())
        && friendIds.contains(post.getAuthorId());
  }

  private <E, D> SearchResult<D> toSearchResult(
      Page<E> page, java.util.function.Function<List<E>, List<D>> mapper, int pageNum, int size) {
    return SearchResult.<D>builder()
        .items(mapper.apply(page.getContent()))
        .totalHits(page.getTotalElements())
        .page(pageNum)
        .size(size)
        .build();
  }

  private List<UserDto> toUserDtos(List<UserEntity> users) {
    return users.stream()
        .map(
            u ->
                UserDto.builder()
                    .id(u.getId())
                    .fullName(u.getFullName())
                    .username(u.getUsername())
                    .profilePictureUrl(u.getProfilePictureUrl())
                    .eliteScore(u.getEliteScore())
                    .build())
        .toList();
  }

  private List<PostDto> toPostDtos(List<PostEntity> posts) {
    Map<Integer, UserEntity> authorsById =
        loadAuthors(posts.stream().map(PostEntity::getAuthorId).toList());

    List<Integer> bookPostIds =
        posts.stream()
            .filter(p -> PostType.BOOK.equals(p.getPostType()))
            .map(PostEntity::getId)
            .toList();
    Map<Integer, BookEntity> booksByPostId =
        bookPostIds.isEmpty()
            ? Map.of()
            : bookRepository.findByPostIdIn(bookPostIds).stream()
                .collect(
                    java.util.stream.Collectors.toMap(BookEntity::getPostId, b -> b, (a, b) -> a));

    return posts.stream()
        .map(
            post -> {
              UserEntity author = authorsById.get(post.getAuthorId());
              BookEntity book = booksByPostId.get(post.getId());
              return withAuthor(PostDto.builder(), author)
                  .id(post.getId())
                  .content(post.getContent())
                  .eventName(
                      post.getEventDetails() != null
                          ? post.getEventDetails().getEventTitle()
                          : null)
                  .authorId(post.getAuthorId())
                  .visibility(post.getVisibility() != null ? post.getVisibility().name() : null)
                  // Handed over whole, offset included — see PostDto#createdAt for why the old
                  // toLocalDateTime() call skewed every search result by the reader's offset.
                  .createdAt(post.getCreatedAt())
                  // PostDto has declared these six all along and nothing ever set them, so every
                  // quiz/poll/code post in a search result came back as text only. This is the
                  // same omission as NewsfeedService.fanOutPost, in the second service that
                  // renders posts — fixing one without the other leaves half the bug alive.
                  .quizDetails(PublicQuizDetailsDto.from(post.getQuizDetails()))
                  .codeSnippetDetails(post.getCodeSnippetDetails())
                  .articleDetails(post.getArticleDetails())
                  .qnaDetails(post.getQnaDetails())
                  .pollDetails(post.getPollDetails())
                  .linkDetails(post.getLinkDetails())
                  .book(book != null ? toBookDto(book) : null)
                  .build();
            })
        .toList();
  }

  /**
   * Fills in every field derived from the author's row, or none of them.
   *
   * <p>Extracted from the mapping lambda above, which carried one {@code author != null} ternary
   * per field. Five independent null checks on one object read as five independent decisions, and
   * each one PMD counted separately — the method sat over both the cognitive-complexity and the
   * NPath threshold before this. There is only one decision here: either the author row was
   * loaded or it was not, and in the second case every author field stays null together.
   *
   * <p>A missing row is not an error. A post outlives the account that wrote it, and a search
   * result that renders with a blank byline is better than one that throws.
   */
  private static PostDto.PostDtoBuilder withAuthor(
      PostDto.PostDtoBuilder builder, UserEntity author) {
    if (author == null) {
      return builder;
    }
    return builder
        .authorUsername(author.getUsername())
        .authorFullName(author.getFullName())
        .authorProfilePictureUrl(author.getProfilePictureUrl())
        .authorEliteScore(author.getEliteScore())
        .authorLevelName(RepLevel.displayNameForScore(author.getEliteScore()));
  }

  private BookDto toBookDto(BookEntity b) {
    return BookDto.builder()
        .id(b.getId())
        .title(b.getTitle())
        .description(b.getDescription())
        .coverImageUrl(bookStorageService.getCoverUrl(b.getCoverImageKey()))
        .authorId(b.getAuthorId())
        .price(b.getPrice())
        .isFree(b.getIsFree())
        .avgRating(b.getAvgRating())
        .build();
  }

  private Map<Integer, UserEntity> loadAuthors(List<Integer> authorIds) {
    Map<Integer, UserEntity> byId = new HashMap<>();
    userRepository
        .findAllById(authorIds.stream().filter(Objects::nonNull).distinct().toList())
        .forEach(u -> byId.put(u.getId(), u));
    return byId;
  }

  private List<Integer> safeFriendIds(List<Integer> friendIds) {
    return friendIds == null || friendIds.isEmpty() ? List.of(-1) : friendIds;
  }

  /**
   * The caller's block set, never empty — same sentinel trick as {@link #safeFriendIds}, for the
   * same reason: {@code NOT IN ()} is not valid SQL and having blocked nobody is the normal case.
   */
  private Collection<Integer> excludedIds(Set<Integer> blockedIds) {
    return blockedIds.isEmpty() ? List.of(-1) : blockedIds;
  }
}
