package com.socialapp.bookstore.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import com.socialapp.AbstractIntegrationTest;
import com.socialapp.bookstore.entity.BookEntity;
import com.socialapp.bookstore.entity.enums.FileFormat;
import com.socialapp.posts.entity.PostEntity;
import com.socialapp.posts.repository.PostRepository;
import com.socialapp.security.entity.UserEntity;
import com.socialapp.security.repository.UserRepository;

/**
 * Component integration tests for {@link BookRepository} against a real PostgreSQL instance
 * (Testcontainers), per ISTQB CTFL v4.0.1 Section 2.2.1. Each test rolls back its own
 * transaction, so no manual cleanup is needed. {@code t_books.author_id} is a required foreign
 * key to a real user, so each test seeds one via {@link UserRepository}.
 */
@Transactional
class BookRepositoryTest extends AbstractIntegrationTest {

  @Autowired private BookRepository bookRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private PostRepository postRepository;

  private Integer authorId;

  @BeforeEach
  void seedAuthor() {
    authorId = userRepository.saveAndFlush(user("author@example.com", "author")).getId();
  }

  private static UserEntity user(String email, String username) {
    UserEntity user = new UserEntity();
    user.setEmail(email);
    user.setPassword("hashed-password");
    user.setUsername(username);
    user.setFullName("Test User");
    return user;
  }

  private static BookEntity book(Integer authorId, String title, String description) {
    return BookEntity.builder()
        .authorId(authorId)
        .title(title)
        .description(description)
        .fileKey("books/" + title)
        .fileFormat(FileFormat.PDF)
        .build();
  }

  private static BookEntity book(
      Integer authorId, String title, double avgRating, int reviewCount) {
    BookEntity entity = book(authorId, title, null);
    entity.setAvgRating(avgRating);
    entity.setReviewCount(reviewCount);
    return entity;
  }

  private static PostEntity post(Integer authorId) {
    PostEntity post = new PostEntity();
    post.setContent("post content");
    post.setAuthorId(authorId);
    return post;
  }

  @Nested
  @DisplayName("findByAuthorIdOrderByCreatedAtDesc")
  class FindByAuthorIdOrderByCreatedAtDesc {

    @Test
    @DisplayName("orders the author's books newest first")
    void ordersNewestFirst() {
      // Given
      BookEntity first = bookRepository.saveAndFlush(book(authorId, "First", null));
      BookEntity second = bookRepository.saveAndFlush(book(authorId, "Second", null));

      // When
      List<BookEntity> result = bookRepository.findByAuthorIdOrderByCreatedAtDesc(authorId);

      // Then
      assertThat(result)
          .extracting(BookEntity::getId)
          .containsExactly(second.getId(), first.getId());
    }

    @Test
    @DisplayName("excludes books belonging to a different author")
    void excludesOtherAuthorsBooks() {
      // Given
      Integer otherAuthorId =
          userRepository.saveAndFlush(user("other@example.com", "other")).getId();
      bookRepository.saveAndFlush(book(otherAuthorId, "Not mine", null));

      // When
      List<BookEntity> result = bookRepository.findByAuthorIdOrderByCreatedAtDesc(authorId);

      // Then
      assertThat(result).isEmpty();
    }
  }

  @Nested
  @DisplayName("findByPostId")
  class FindByPostId {

    @Test
    @DisplayName("returns books linked to the given post")
    void returnsBooksLinkedToPost() {
      // Given
      Integer postId = postRepository.saveAndFlush(post(authorId)).getId();
      BookEntity linked = bookRepository.saveAndFlush(book(authorId, "Linked", null));
      linked.setPostId(postId);
      bookRepository.saveAndFlush(linked);
      bookRepository.saveAndFlush(book(authorId, "Unlinked", null));

      // When
      List<BookEntity> result = bookRepository.findByPostId(postId);

      // Then
      assertThat(result).extracting(BookEntity::getId).containsExactly(linked.getId());
    }

    @Test
    @DisplayName("returns an empty list when no book is linked to the post")
    void returnsEmptyListWhenNoBookLinked() {
      // Given
      Integer postId = postRepository.saveAndFlush(post(authorId)).getId();

      // When
      List<BookEntity> result = bookRepository.findByPostId(postId);

      // Then
      assertThat(result).isEmpty();
    }
  }

  @Nested
  @DisplayName("findByPostIdIn")
  class FindByPostIdIn {

    @Test
    @DisplayName("returns books linked to any of the given posts")
    void returnsBooksForMultiplePosts() {
      // Given
      Integer postId1 = postRepository.saveAndFlush(post(authorId)).getId();
      Integer postId2 = postRepository.saveAndFlush(post(authorId)).getId();
      Integer postId3 = postRepository.saveAndFlush(post(authorId)).getId();

      BookEntity book1 = bookRepository.saveAndFlush(book(authorId, "Book 1", null));
      book1.setPostId(postId1);
      bookRepository.saveAndFlush(book1);

      BookEntity book2 = bookRepository.saveAndFlush(book(authorId, "Book 2", null));
      book2.setPostId(postId2);
      bookRepository.saveAndFlush(book2);

      BookEntity book3 = bookRepository.saveAndFlush(book(authorId, "Book 3", null));
      book3.setPostId(postId3);
      bookRepository.saveAndFlush(book3);

      // When
      List<BookEntity> result = bookRepository.findByPostIdIn(List.of(postId1, postId2));

      // Then
      assertThat(result)
          .extracting(BookEntity::getId)
          .containsExactlyInAnyOrder(book1.getId(), book2.getId());
    }

    @Test
    @DisplayName("returns an empty list when given an empty id collection")
    void returnsEmptyListForEmptyIds() {
      // When
      List<BookEntity> result = bookRepository.findByPostIdIn(List.of());

      // Then
      assertThat(result).isEmpty();
    }
  }

  @Nested
  @DisplayName("search")
  class Search {

    @Test
    @DisplayName("matches by title ignoring accents and case")
    void matchesTitleIgnoringAccentsAndCase() {
      // Given
      BookEntity target =
          bookRepository.saveAndFlush(book(authorId, "Lập Trình Java Cơ Bản", null));

      // When
      Page<BookEntity> result = bookRepository.search("lap trinh java", PageRequest.of(0, 10));

      // Then
      assertThat(result.getContent()).extracting(BookEntity::getId).containsExactly(target.getId());
    }

    @Test
    @DisplayName("matches by description")
    void matchesDescription() {
      // Given
      BookEntity target =
          bookRepository.saveAndFlush(book(authorId, "Untitled", "A guide to distributed systems"));

      // When
      Page<BookEntity> result = bookRepository.search("distributed systems", PageRequest.of(0, 10));

      // Then
      assertThat(result.getContent()).extracting(BookEntity::getId).containsExactly(target.getId());
    }

    @Test
    @DisplayName("orders matches by average rating, then review count, both descending")
    void ordersByRatingThenReviewCount() {
      // Given
      BookEntity lowRating =
          bookRepository.saveAndFlush(book(authorId, "Searchtermranking Low", 3.0, 100));
      BookEntity highRatingFewerReviews =
          bookRepository.saveAndFlush(book(authorId, "Searchtermranking High Few", 4.5, 5));
      BookEntity highRatingMoreReviews =
          bookRepository.saveAndFlush(book(authorId, "Searchtermranking High Many", 4.5, 50));

      // When
      Page<BookEntity> result = bookRepository.search("searchtermranking", PageRequest.of(0, 10));

      // Then
      assertThat(result.getContent())
          .extracting(BookEntity::getId)
          .containsExactly(
              highRatingMoreReviews.getId(), highRatingFewerReviews.getId(), lowRating.getId());
    }

    @Test
    @DisplayName("returns an empty page when nothing matches")
    void returnsEmptyPageWhenNoMatch() {
      // Given
      bookRepository.saveAndFlush(book(authorId, "Some Title", "Some description"));

      // When
      Page<BookEntity> result = bookRepository.search("zzz_no_such_keyword", PageRequest.of(0, 10));

      // Then
      assertThat(result.getContent()).isEmpty();
    }
  }
}
