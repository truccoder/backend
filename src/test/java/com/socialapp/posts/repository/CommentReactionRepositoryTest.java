package com.socialapp.posts.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import com.socialapp.AbstractIntegrationTest;
import com.socialapp.posts.entity.CommentEntity;
import com.socialapp.posts.entity.CommentReactionEntity;
import com.socialapp.posts.entity.CommentReactionId;
import com.socialapp.posts.entity.PostEntity;
import com.socialapp.posts.entity.enums.ReactionType;
import com.socialapp.security.entity.UserEntity;
import com.socialapp.security.repository.UserRepository;

/**
 * Component integration tests for {@link CommentReactionRepository} against a real PostgreSQL
 * instance (Testcontainers), per ISTQB CTFL v4.0.1 Section 2.2.1. Each test rolls back its own
 * transaction, so no manual cleanup is needed.
 *
 * <p>The batch methods are the reason this class exists. {@code countByCommentIds} and {@code
 * findMyReactions} are what keeps the comment list to two extra queries no matter how long a
 * thread is, and both take a collection straight into an SQL {@code IN} — where an empty
 * collection is a syntax error, not an empty result. That guard cannot be verified with mocks.
 */
@Transactional
class CommentReactionRepositoryTest extends AbstractIntegrationTest {

  @Autowired private CommentReactionRepository commentReactionRepository;
  @Autowired private CommentRepository commentRepository;
  @Autowired private PostRepository postRepository;
  @Autowired private UserRepository userRepository;

  private Integer authorId;
  private Integer readerId;
  private Integer commentA;
  private Integer commentB;

  @BeforeEach
  void seedThread() {
    authorId = userRepository.saveAndFlush(user("author@example.com", "author")).getId();
    readerId = userRepository.saveAndFlush(user("reader@example.com", "reader")).getId();
    Integer postId = postRepository.saveAndFlush(post(authorId)).getId();
    commentA = commentRepository.saveAndFlush(comment(postId, authorId, "first")).getId();
    commentB = commentRepository.saveAndFlush(comment(postId, authorId, "second")).getId();
  }

  private static UserEntity user(String email, String username) {
    UserEntity user = new UserEntity();
    user.setEmail(email);
    user.setPassword("hashed-password");
    user.setUsername(username);
    user.setFullName("Test User");
    return user;
  }

  private static PostEntity post(Integer authorId) {
    PostEntity post = new PostEntity();
    post.setContent("post content");
    post.setAuthorId(authorId);
    return post;
  }

  private static CommentEntity comment(Integer postId, Integer authorId, String content) {
    CommentEntity comment = new CommentEntity();
    comment.setPostId(postId);
    comment.setAuthorId(authorId);
    comment.setContent(content);
    return comment;
  }

  private void react(Integer userId, Integer commentId, ReactionType type) {
    CommentReactionEntity reaction = new CommentReactionEntity();
    reaction.setId(new CommentReactionId(userId, commentId));
    reaction.setReactionType(type);
    commentReactionRepository.saveAndFlush(reaction);
  }

  // =====================================================================
  // countByTypeForCommentIds
  // =====================================================================

  @Nested
  @DisplayName("countByTypeForCommentIds")
  class CountByTypeForCommentIdsTests {

    @Test
    @DisplayName("should break the total down by reaction type, per comment")
    void shouldGroupByCommentAndType() {
      // Given - the same rows countByCommentIds sees as "2 and 1". The breakdown is what a
      // reaction row can no longer say for itself now that the text labels are gone.
      react(authorId, commentA, ReactionType.LIKE);
      react(readerId, commentA, ReactionType.INSIGHT);
      react(readerId, commentB, ReactionType.CLAP);

      // When
      Map<Integer, Map<ReactionType, Long>> summaries =
          commentReactionRepository.countByTypeForCommentIds(List.of(commentA, commentB));

      // Then
      assertThat(summaries.get(commentA))
          .containsEntry(ReactionType.LIKE, 1L)
          .containsEntry(ReactionType.INSIGHT, 1L)
          .hasSize(2);
      assertThat(summaries.get(commentB)).containsExactly(entry(ReactionType.CLAP, 1L));
    }

    @Test
    @DisplayName("should add up several reactions of the same type on one comment")
    void shouldSumWithinAType() {
      // Given
      react(authorId, commentA, ReactionType.LIKE);
      react(readerId, commentA, ReactionType.LIKE);

      // When
      Map<Integer, Map<ReactionType, Long>> summaries =
          commentReactionRepository.countByTypeForCommentIds(List.of(commentA));

      // Then
      assertThat(summaries.get(commentA)).containsExactly(entry(ReactionType.LIKE, 2L));
    }

    @Test
    @DisplayName("should leave out a comment nobody reacted to, rather than mapping it to zero")
    void shouldOmitUnreactedComments() {
      // Given
      react(authorId, commentA, ReactionType.LIKE);

      // When
      Map<Integer, Map<ReactionType, Long>> summaries =
          commentReactionRepository.countByTypeForCommentIds(List.of(commentA, commentB));

      // Then - the caller substitutes an empty map, and a client renders one chip per entry, so a
      // zero would be noise it has to filter out again
      assertThat(summaries).containsOnlyKeys(commentA);
    }

    @Test
    @DisplayName("should return an empty map for an empty id list, not raise a syntax error")
    void shouldGuardTheEmptyList() {
      // Given / When / Then - "IN ()" is a syntax error in Postgres, and an unanswered post is
      // the ordinary case. Only a real database can prove the guard is needed.
      assertThat(commentReactionRepository.countByTypeForCommentIds(List.of())).isEmpty();
    }
  }

  // =====================================================================
  // findReactorIds
  // =====================================================================

  @Nested
  @DisplayName("findReactorIds")
  class FindReactorIdsTests {

    @Test
    @DisplayName("should return the reactors of one comment, ordered by user id")
    void shouldReturnReactorsOrderedById() {
      // Given - ordered by user id because that is the cursor column: this table's key is
      // (userId, commentId) and createdAt has no tiebreaker
      react(readerId, commentA, ReactionType.LIKE);
      react(authorId, commentA, ReactionType.CLAP);

      // When
      List<Integer> reactors =
          commentReactionRepository.findReactorIds(commentA, null, null, PageRequest.of(0, 10));

      // Then
      assertThat(reactors)
          .containsExactly(Math.min(authorId, readerId), Math.max(authorId, readerId));
    }

    @Test
    @DisplayName("should narrow to one reaction type when asked")
    void shouldFilterByType() {
      // Given
      react(readerId, commentA, ReactionType.LIKE);
      react(authorId, commentA, ReactionType.CLAP);

      // When
      List<Integer> reactors =
          commentReactionRepository.findReactorIds(
              commentA, ReactionType.CLAP, null, PageRequest.of(0, 10));

      // Then
      assertThat(reactors).containsExactly(authorId);
    }

    @Test
    @DisplayName("should resume strictly after the cursor")
    void shouldResumeAfterCursor() {
      // Given
      react(readerId, commentA, ReactionType.LIKE);
      react(authorId, commentA, ReactionType.LIKE);
      int first = Math.min(authorId, readerId);
      int second = Math.max(authorId, readerId);

      // When
      List<Integer> reactors =
          commentReactionRepository.findReactorIds(commentA, null, first, PageRequest.of(0, 10));

      // Then - strictly greater than, so the cursor row is not served twice
      assertThat(reactors).containsExactly(second);
    }

    @Test
    @DisplayName("should not leak reactors of a different comment")
    void shouldScopeToTheComment() {
      // Given
      react(readerId, commentB, ReactionType.LIKE);

      // When / Then
      assertThat(
              commentReactionRepository.findReactorIds(commentA, null, null, PageRequest.of(0, 10)))
          .isEmpty();
    }
  }

  // =====================================================================
  // countByCommentIds
  // =====================================================================

  @Nested
  @DisplayName("countByCommentIds")
  class CountByCommentIdsTests {

    @Test
    @DisplayName("should count every reaction type together, per comment")
    void shouldCountAllTypesPerComment() {
      // Given — a comment with three reactions of different types, and one with a single reaction
      react(authorId, commentA, ReactionType.LIKE);
      react(readerId, commentA, ReactionType.INSIGHT);
      react(readerId, commentB, ReactionType.CLAP);

      // When
      Map<Integer, Long> counts =
          commentReactionRepository.countByCommentIds(List.of(commentA, commentB));

      // Then — likeCount is a total, not a per-type breakdown
      assertThat(counts).containsEntry(commentA, 2L).containsEntry(commentB, 1L);
    }

    @Test
    @DisplayName("should leave out comments nobody reacted to rather than reporting zero")
    void shouldOmitCommentsWithNoReactions() {
      // Given
      react(authorId, commentA, ReactionType.LIKE);

      // When
      Map<Integer, Long> counts =
          commentReactionRepository.countByCommentIds(List.of(commentA, commentB));

      // Then — the caller defaults a missing key to 0, so an absent entry is the normal case
      assertThat(counts).containsOnlyKeys(commentA);
    }

    @Test
    @DisplayName("should return an empty map for an empty id list without touching the database")
    void shouldReturnEmptyMap_whenIdListIsEmpty() {
      // Given — a post with no comments at all. Passing this through would build "IN ()", which
      // Postgres rejects as a syntax error, so the guard is what keeps an unanswered post from
      // failing outright.
      // When
      Map<Integer, Long> counts = commentReactionRepository.countByCommentIds(List.of());

      // Then
      assertThat(counts).isEmpty();
    }

    @Test
    @DisplayName("should ignore ids that do not exist")
    void shouldIgnoreUnknownIds() {
      // Given
      react(authorId, commentA, ReactionType.LOVE);

      // When
      Map<Integer, Long> counts =
          commentReactionRepository.countByCommentIds(List.of(commentA, 999_999));

      // Then
      assertThat(counts).containsOnlyKeys(commentA);
    }
  }

  // =====================================================================
  // findMyReactions
  // =====================================================================

  @Nested
  @DisplayName("findMyReactions")
  class FindMyReactionsTests {

    @Test
    @DisplayName("should return only the caller's own reactions")
    void shouldReturnOnlyTheCallersReactions() {
      // Given — two people reacted to the same comment with different types
      react(authorId, commentA, ReactionType.LIKE);
      react(readerId, commentA, ReactionType.INSIGHT);
      react(authorId, commentB, ReactionType.CLAP);

      // When
      Map<Integer, ReactionType> mine =
          commentReactionRepository.findMyReactions(readerId, List.of(commentA, commentB));

      // Then — the reader sees their own INSIGHT, not the author's LIKE, and nothing for commentB
      assertThat(mine).containsExactly(Map.entry(commentA, ReactionType.INSIGHT));
    }

    @Test
    @DisplayName("should return an empty map for a guest with no user id")
    void shouldReturnEmptyMap_whenViewerIsNull() {
      // Given — the comment list is reachable on a public post without signing in, and a guest has
      // reacted to nothing
      react(authorId, commentA, ReactionType.LIKE);

      // When
      Map<Integer, ReactionType> mine =
          commentReactionRepository.findMyReactions(null, List.of(commentA));

      // Then
      assertThat(mine).isEmpty();
    }

    @Test
    @DisplayName("should return an empty map for an empty id list")
    void shouldReturnEmptyMap_whenIdListIsEmpty() {
      // When
      Map<Integer, ReactionType> mine =
          commentReactionRepository.findMyReactions(readerId, List.of());

      // Then
      assertThat(mine).isEmpty();
    }
  }

  // =====================================================================
  // The composite key
  // =====================================================================

  @Nested
  @DisplayName("the (userId, commentId) primary key")
  class PrimaryKeyTests {

    @Test
    @DisplayName("should make a second reaction by the same user an update, not a new row")
    void shouldUpsertRatherThanDuplicate() {
      // Given — the key is what guarantees one reaction per person per comment; without it,
      // switching from LIKE to INSIGHT would count as two
      react(readerId, commentA, ReactionType.LIKE);

      // When
      react(readerId, commentA, ReactionType.INSIGHT);

      // Then
      assertThat(commentReactionRepository.countByCommentIds(List.of(commentA)))
          .containsEntry(commentA, 1L);
      assertThat(commentReactionRepository.findMyReactions(readerId, List.of(commentA)))
          .containsEntry(commentA, ReactionType.INSIGHT);
    }

    @Test
    @DisplayName("should delete a reaction by its composite id")
    void shouldDeleteByCompositeId() {
      // Given
      CommentReactionId id = new CommentReactionId(readerId, commentA);
      react(readerId, commentA, ReactionType.LIKE);

      // When
      commentReactionRepository.deleteById(id);
      commentReactionRepository.flush();

      // Then
      assertThat(commentReactionRepository.existsById(id)).isFalse();
    }
  }
}
