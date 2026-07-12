package com.socialapp.posts.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import com.socialapp.AbstractIntegrationTest;
import com.socialapp.posts.entity.EventRsvpEntity;
import com.socialapp.posts.entity.PostEntity;
import com.socialapp.posts.entity.enums.RsvpStatus;
import com.socialapp.security.entity.UserEntity;
import com.socialapp.security.repository.UserRepository;

/**
 * Component integration tests for {@link EventRsvpRepository} against a real PostgreSQL instance
 * (Testcontainers), per ISTQB CTFL v4.0.1 Section 2.2.1. Each test rolls back its own
 * transaction, so no manual cleanup is needed. {@code t_event_rsvps} has foreign keys to a real
 * post and a real user, so each test seeds those first.
 */
@Transactional
class EventRsvpRepositoryTest extends AbstractIntegrationTest {

  @Autowired private EventRsvpRepository eventRsvpRepository;
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
    post.setContent("event post");
    post.setAuthorId(authorId);
    return post;
  }

  private static EventRsvpEntity rsvp(Integer postId, Integer userId, RsvpStatus status) {
    return EventRsvpEntity.builder().postId(postId).userId(userId).status(status).build();
  }

  private Integer newUserId(String email, String username) {
    return userRepository.saveAndFlush(user(email, username)).getId();
  }

  @Nested
  @DisplayName("findByPostIdAndUserId")
  class FindByPostIdAndUserId {

    @Test
    @DisplayName("finds the user's RSVP for the post")
    void findsExistingRsvp() {
      // Given
      Integer userId = newUserId("a@example.com", "a");
      eventRsvpRepository.saveAndFlush(rsvp(postId, userId, RsvpStatus.GOING));

      // When
      Optional<EventRsvpEntity> result = eventRsvpRepository.findByPostIdAndUserId(postId, userId);

      // Then
      assertThat(result).isPresent();
      assertThat(result.get().getStatus()).isEqualTo(RsvpStatus.GOING);
    }

    @Test
    @DisplayName("returns empty when the user has not RSVP'd to the post")
    void returnsEmptyWhenNoRsvp() {
      // Given
      Integer userId = newUserId("a@example.com", "a");

      // When
      Optional<EventRsvpEntity> result = eventRsvpRepository.findByPostIdAndUserId(postId, userId);

      // Then
      assertThat(result).isEmpty();
    }
  }

  @Nested
  @DisplayName("findByPostIdAndStatus")
  class FindByPostIdAndStatus {

    @Test
    @DisplayName("returns only RSVPs with the given status")
    void returnsMatchingRsvps() {
      // Given
      Integer goingUserId = newUserId("going@example.com", "going");
      Integer interestedUserId = newUserId("interested@example.com", "interested");
      eventRsvpRepository.saveAndFlush(rsvp(postId, goingUserId, RsvpStatus.GOING));
      eventRsvpRepository.saveAndFlush(rsvp(postId, interestedUserId, RsvpStatus.INTERESTED));

      // When
      List<EventRsvpEntity> result =
          eventRsvpRepository.findByPostIdAndStatus(postId, RsvpStatus.GOING);

      // Then
      assertThat(result).extracting(EventRsvpEntity::getUserId).containsExactly(goingUserId);
    }

    @Test
    @DisplayName("returns an empty list when no RSVP has the given status")
    void returnsEmptyListWhenNoMatch() {
      // When
      List<EventRsvpEntity> result =
          eventRsvpRepository.findByPostIdAndStatus(postId, RsvpStatus.NOT_GOING);

      // Then
      assertThat(result).isEmpty();
    }
  }

  @Nested
  @DisplayName("countByPostIdAndStatus")
  class CountByPostIdAndStatus {

    @Test
    @DisplayName("counts RSVPs with the given status")
    void countsMatchingRsvps() {
      // Given
      eventRsvpRepository.saveAndFlush(
          rsvp(postId, newUserId("a@example.com", "a"), RsvpStatus.GOING));
      eventRsvpRepository.saveAndFlush(
          rsvp(postId, newUserId("b@example.com", "b"), RsvpStatus.GOING));
      eventRsvpRepository.saveAndFlush(
          rsvp(postId, newUserId("c@example.com", "c"), RsvpStatus.NOT_GOING));

      // When
      int result = eventRsvpRepository.countByPostIdAndStatus(postId, RsvpStatus.GOING);

      // Then
      assertThat(result).isEqualTo(2);
    }

    @Test
    @DisplayName("returns zero when no RSVP has the given status")
    void returnsZeroWhenNoMatch() {
      // When
      int result = eventRsvpRepository.countByPostIdAndStatus(postId, RsvpStatus.GOING);

      // Then
      assertThat(result).isEqualTo(0);
    }
  }

  @Nested
  @DisplayName("findByPostId")
  class FindByPostId {

    @Test
    @DisplayName("returns every RSVP for the post regardless of status")
    void returnsAllRsvpsForPost() {
      // Given
      eventRsvpRepository.saveAndFlush(
          rsvp(postId, newUserId("a@example.com", "a"), RsvpStatus.GOING));
      eventRsvpRepository.saveAndFlush(
          rsvp(postId, newUserId("b@example.com", "b"), RsvpStatus.NOT_GOING));

      // When
      List<EventRsvpEntity> result = eventRsvpRepository.findByPostId(postId);

      // Then
      assertThat(result).hasSize(2);
    }

    @Test
    @DisplayName("returns an empty list when the post has no RSVPs")
    void returnsEmptyListWhenNoRsvps() {
      // When
      List<EventRsvpEntity> result = eventRsvpRepository.findByPostId(postId);

      // Then
      assertThat(result).isEmpty();
    }
  }
}
