package com.socialapp.search.service;

import java.util.*;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import com.socialapp.bookstore.entity.BookEntity;
import com.socialapp.bookstore.repository.BookRepository;
import com.socialapp.bookstore.service.BookStorageService;
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
 */
@Service
@RequiredArgsConstructor
public class SearchService {
  private final UserRepository userRepository;
  private final PostRepository postRepository;
  private final BookRepository bookRepository;
  private final BookStorageService bookStorageService;

  public SearchResult<UserDto> searchUsers(
      String query, int page, int size, List<Integer> friendIds) {
    Page<UserEntity> result =
        userRepository.search(
            SearchQuerySanitizer.sanitize(query),
            safeFriendIds(friendIds),
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

    List<PostEntity> contentMatches =
        postRepository
            .searchByContentOrEventName(
                sanitized, currentUserId, safeFriends, PageRequest.of(0, size))
            .getContent();

    List<Integer> bookMatchedPostIds =
        bookRepository.search(sanitized, PageRequest.of(0, size)).getContent().stream()
            .map(BookEntity::getPostId)
            .filter(Objects::nonNull)
            .toList();
    List<PostEntity> bookMatches =
        bookMatchedPostIds.isEmpty()
            ? List.of()
            : postRepository.findAllById(bookMatchedPostIds).stream()
                .filter(p -> isVisibleToViewer(p, currentUserId, safeFriends))
                .toList();

    Map<Integer, PostEntity> merged = new LinkedHashMap<>();
    contentMatches.forEach(p -> merged.put(p.getId(), p));
    bookMatches.forEach(p -> merged.putIfAbsent(p.getId(), p));

    return toPostDtos(merged.values().stream().limit(size).toList());
  }

  private boolean isVisibleToViewer(
      PostEntity post, Integer currentUserId, List<Integer> friendIds) {
    if (post.getAuthorId().equals(currentUserId)) {
      return true;
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
              return PostDto.builder()
                  .id(post.getId())
                  .content(post.getContent())
                  .eventName(
                      post.getEventDetails() != null
                          ? post.getEventDetails().getEventTitle()
                          : null)
                  .authorId(post.getAuthorId())
                  .authorFullName(author != null ? author.getFullName() : null)
                  .authorProfilePictureUrl(author != null ? author.getProfilePictureUrl() : null)
                  .authorEliteScore(author != null ? author.getEliteScore() : null)
                  .authorLevelName(
                      author != null ? RepLevel.displayNameForScore(author.getEliteScore()) : null)
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
}
