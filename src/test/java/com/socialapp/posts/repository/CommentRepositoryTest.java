package com.socialapp.posts.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import com.socialapp.AbstractIntegrationTest;
import com.socialapp.posts.entity.CommentEntity;
import com.socialapp.posts.entity.PostEntity;
import com.socialapp.security.entity.UserEntity;
import com.socialapp.security.repository.UserRepository;

/**
 * Component integration tests for {@link CommentRepository} against a real PostgreSQL instance
 * (Testcontainers), per ISTQB CTFL v4.0.1 Section 2.2.1. Each test rolls back its own
 * transaction, so no manual cleanup is needed. {@code t_comments} has foreign keys to a real
 * post and a real user, so each test seeds those first.
 */
@Transactional
class CommentRepositoryTest extends AbstractIntegrationTest {

  @Autowired private CommentRepository commentRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private PostRepository postRepository;

  private Integer authorId;
  private Integer postId;

  @BeforeEach
  void seedAuthorAndPost() {
    authorId = userRepository.saveAndFlush(user("author@example.com", "author")).getId();
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

  private static CommentEntity comment(
      Integer postId, Integer authorId, String content, Integer parentId) {
    CommentEntity comment = new CommentEntity();
    comment.setPostId(postId);
    comment.setAuthorId(authorId);
    comment.setContent(content);
    comment.setParentId(parentId);
    return comment;
  }

  @Nested
  @DisplayName("findByPostIdAndParentIdIsNullOrderByCreatedAtAsc")
  class FindTopLevelComments {

    @Test
    @DisplayName("returns only top-level comments, oldest first")
    void returnsTopLevelCommentsOldestFirst() {
      // Given
      CommentEntity first =
          commentRepository.saveAndFlush(comment(postId, authorId, "first", null));
      CommentEntity second =
          commentRepository.saveAndFlush(comment(postId, authorId, "second", null));
      commentRepository.saveAndFlush(comment(postId, authorId, "a reply", first.getId()));

      // When
      List<CommentEntity> result =
          commentRepository.findByPostIdAndParentIdIsNullOrderByCreatedAtAsc(postId);

      // Then
      assertThat(result)
          .extracting(CommentEntity::getId)
          .containsExactly(first.getId(), second.getId());
    }

    @Test
    @DisplayName("returns an empty list when the post has no comments")
    void returnsEmptyListWhenNoComments() {
      // When
      List<CommentEntity> result =
          commentRepository.findByPostIdAndParentIdIsNullOrderByCreatedAtAsc(postId);

      // Then
      assertThat(result).isEmpty();
    }
  }

  @Nested
  @DisplayName("findByParentIdOrderByCreatedAtAsc")
  class FindReplies {

    @Test
    @DisplayName("returns replies to a comment, oldest first")
    void returnsRepliesOldestFirst() {
      // Given
      CommentEntity parent =
          commentRepository.saveAndFlush(comment(postId, authorId, "parent", null));
      CommentEntity reply1 =
          commentRepository.saveAndFlush(comment(postId, authorId, "reply 1", parent.getId()));
      CommentEntity reply2 =
          commentRepository.saveAndFlush(comment(postId, authorId, "reply 2", parent.getId()));

      // When
      List<CommentEntity> result =
          commentRepository.findByParentIdOrderByCreatedAtAsc(parent.getId());

      // Then
      assertThat(result)
          .extracting(CommentEntity::getId)
          .containsExactly(reply1.getId(), reply2.getId());
    }

    @Test
    @DisplayName("returns an empty list when the comment has no replies")
    void returnsEmptyListWhenNoReplies() {
      // Given
      CommentEntity parent =
          commentRepository.saveAndFlush(comment(postId, authorId, "parent", null));

      // When
      List<CommentEntity> result =
          commentRepository.findByParentIdOrderByCreatedAtAsc(parent.getId());

      // Then
      assertThat(result).isEmpty();
    }
  }

  @Nested
  @DisplayName("existsByIdAndParentIdIsNull")
  class ExistsByIdAndParentIdIsNull {

    @Test
    @DisplayName("returns true for a top-level comment")
    void returnsTrueForTopLevelComment() {
      // Given
      CommentEntity topLevel =
          commentRepository.saveAndFlush(comment(postId, authorId, "top level", null));

      // When
      boolean result = commentRepository.existsByIdAndParentIdIsNull(topLevel.getId());

      // Then
      assertThat(result).isTrue();
    }

    @Test
    @DisplayName("returns false for a reply comment")
    void returnsFalseForReplyComment() {
      // Given
      CommentEntity parent =
          commentRepository.saveAndFlush(comment(postId, authorId, "parent", null));
      CommentEntity reply =
          commentRepository.saveAndFlush(comment(postId, authorId, "reply", parent.getId()));

      // When
      boolean result = commentRepository.existsByIdAndParentIdIsNull(reply.getId());

      // Then
      assertThat(result).isFalse();
    }

    @Test
    @DisplayName("returns false when the comment does not exist")
    void returnsFalseWhenCommentMissing() {
      // When
      boolean result = commentRepository.existsByIdAndParentIdIsNull(-1);

      // Then
      assertThat(result).isFalse();
    }
  }

  @Nested
  @DisplayName("countByPostIds")
  class CountByPostIds {

    @Test
    @DisplayName("counts a whole page of posts in one query, replies included")
    void countsManyPostsAtOnce() {
      // Given
      CommentEntity top =
          commentRepository.saveAndFlush(comment(postId, authorId, "top level", null));
      commentRepository.saveAndFlush(comment(postId, authorId, "a reply", top.getId()));
      Integer otherPostId = postRepository.saveAndFlush(post(authorId)).getId();

      // When
      var counts = commentRepository.countByPostIds(List.of(postId, otherPostId));

      // Then — a post with no comments is simply absent; the caller defaults it to zero
      assertThat(counts).containsEntry(postId, 2L).doesNotContainKey(otherPostId);
    }

    @Test
    @DisplayName("returns an empty map for an empty id list without touching the database")
    void emptyInputShortCircuits() {
      // When / Then
      assertThat(commentRepository.countByPostIds(List.of())).isEmpty();
    }
  }
}
