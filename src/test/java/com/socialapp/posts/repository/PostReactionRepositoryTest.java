package com.socialapp.posts.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import com.socialapp.AbstractIntegrationTest;
import com.socialapp.posts.entity.PostEntity;
import com.socialapp.posts.entity.PostReactionEntity;
import com.socialapp.posts.entity.PostReactionId;
import com.socialapp.posts.entity.enums.ReactionType;
import com.socialapp.security.entity.UserEntity;
import com.socialapp.security.repository.UserRepository;

/**
 * Component integration tests for {@link PostReactionRepository} against a real PostgreSQL
 * instance (Testcontainers), per ISTQB CTFL v4.0.1 Section 2.2.1. Each test rolls back its own
 * transaction, so no manual cleanup is needed. {@code t_post_reactions} has foreign keys to a
 * real post and real users, so each test seeds those first.
 */
@Transactional
class PostReactionRepositoryTest extends AbstractIntegrationTest {

  @Autowired private PostReactionRepository postReactionRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private PostRepository postRepository;

  private Integer postId;

  @BeforeEach
  void seedPost() {
    Integer authorId = userRepository.saveAndFlush(user("author@example.com", "author")).getId();
    postId = postRepository.saveAndFlush(post(authorId)).getId();
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

  private static PostReactionEntity reaction(Integer userId, Integer postId, ReactionType type) {
    PostReactionEntity reaction = new PostReactionEntity();
    reaction.setId(new PostReactionId(userId, postId));
    reaction.setReactionType(type);
    return reaction;
  }

  private Integer newUserId(String email, String username) {
    return userRepository.saveAndFlush(user(email, username)).getId();
  }

  @Nested
  @DisplayName("countByIdPostId")
  class CountByIdPostId {

    @Test
    @DisplayName("counts every reaction on the post")
    void countsAllReactions() {
      // Given
      postReactionRepository.saveAndFlush(
          reaction(newUserId("a@example.com", "a"), postId, ReactionType.LIKE));
      postReactionRepository.saveAndFlush(
          reaction(newUserId("b@example.com", "b"), postId, ReactionType.LOVE));

      // When
      long result = postReactionRepository.countByIdPostId(postId);

      // Then
      assertThat(result).isEqualTo(2);
    }

    @Test
    @DisplayName("returns zero when the post has no reactions")
    void returnsZeroWhenNoReactions() {
      // When
      long result = postReactionRepository.countByIdPostId(postId);

      // Then
      assertThat(result).isEqualTo(0);
    }
  }

  @Nested
  @DisplayName("countByType")
  class CountByType {

    @Test
    @DisplayName("groups reaction counts by type")
    void groupsCountsByType() {
      // Given
      postReactionRepository.saveAndFlush(
          reaction(newUserId("a@example.com", "a"), postId, ReactionType.LIKE));
      postReactionRepository.saveAndFlush(
          reaction(newUserId("b@example.com", "b"), postId, ReactionType.LIKE));
      postReactionRepository.saveAndFlush(
          reaction(newUserId("c@example.com", "c"), postId, ReactionType.ANGRY));

      // When
      Map<ReactionType, Long> result = postReactionRepository.countByType(postId);

      // Then
      assertThat(result).containsEntry(ReactionType.LIKE, 2L).containsEntry(ReactionType.ANGRY, 1L);
    }

    @Test
    @DisplayName("returns an empty map when the post has no reactions")
    void returnsEmptyMapWhenNoReactions() {
      // When
      Map<ReactionType, Long> result = postReactionRepository.countByType(postId);

      // Then
      assertThat(result).isEmpty();
    }
  }

  @Nested
  @DisplayName("findReactorIds")
  class FindReactorIds {

    private Integer seedReactor(String name, ReactionType type) {
      Integer id = userRepository.saveAndFlush(user(name + "@example.com", name)).getId();
      postReactionRepository.saveAndFlush(reaction(id, postId, type));
      return id;
    }

    @Test
    @DisplayName("returns every reactor in ascending id order when no type is given")
    void returnsAllReactors() {
      // Given
      Integer first = seedReactor("r1", ReactionType.LIKE);
      Integer second = seedReactor("r2", ReactionType.LOVE);

      // When
      var ids =
          postReactionRepository.findReactorIds(
              postId, null, null, org.springframework.data.domain.PageRequest.of(0, 10));

      // Then
      assertThat(ids).containsExactly(first, second);
    }

    @Test
    @DisplayName("narrows to one reaction type when asked")
    void filtersByType() {
      // Given
      seedReactor("r3", ReactionType.LIKE);
      Integer lover = seedReactor("r4", ReactionType.LOVE);

      // When
      var ids =
          postReactionRepository.findReactorIds(
              postId,
              ReactionType.LOVE,
              null,
              org.springframework.data.domain.PageRequest.of(0, 10));

      // Then
      assertThat(ids).containsExactly(lover);
    }

    @Test
    @DisplayName("walks forward from the cursor without repeating it")
    void appliesCursor() {
      // Given
      Integer first = seedReactor("r5", ReactionType.LIKE);
      Integer second = seedReactor("r6", ReactionType.LIKE);

      // When
      var ids =
          postReactionRepository.findReactorIds(
              postId, null, first, org.springframework.data.domain.PageRequest.of(0, 10));

      // Then
      assertThat(ids).containsExactly(second);
    }
  }

  @Nested
  @DisplayName("countByIdPostIdAndReactionType / countByPostIds")
  class CountingHelpers {

    @Test
    @DisplayName("counts one type on one post")
    void countsOneType() {
      // Given
      Integer a = userRepository.saveAndFlush(user("c1@example.com", "c1")).getId();
      Integer b = userRepository.saveAndFlush(user("c2@example.com", "c2")).getId();
      postReactionRepository.saveAndFlush(reaction(a, postId, ReactionType.LIKE));
      postReactionRepository.saveAndFlush(reaction(b, postId, ReactionType.LOVE));

      // When / Then
      assertThat(postReactionRepository.countByIdPostIdAndReactionType(postId, ReactionType.LIKE))
          .isEqualTo(1);
    }

    @Test
    @DisplayName("counts a whole page of posts in one query, omitting posts with no reactions")
    void countsManyPosts() {
      // Given
      Integer a = userRepository.saveAndFlush(user("c3@example.com", "c3")).getId();
      postReactionRepository.saveAndFlush(reaction(a, postId, ReactionType.LIKE));
      Integer emptyPostId = postRepository.saveAndFlush(post(a)).getId();

      // When
      Map<Integer, Long> counts =
          postReactionRepository.countByPostIds(java.util.List.of(postId, emptyPostId));

      // Then — the caller defaults an absent post to zero
      assertThat(counts).containsEntry(postId, 1L).doesNotContainKey(emptyPostId);
    }

    @Test
    @DisplayName("returns an empty map without querying for an empty id list")
    void emptyInputShortCircuits() {
      // When / Then
      assertThat(postReactionRepository.countByPostIds(java.util.List.of())).isEmpty();
    }
  }
}
