package com.socialapp.reputation.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import com.socialapp.AbstractIntegrationTest;
import com.socialapp.reputation.entity.ReputationEventEntity;
import com.socialapp.reputation.entity.enums.RepSourceType;
import com.socialapp.security.entity.UserEntity;
import com.socialapp.security.repository.UserRepository;

/**
 * Component integration tests for {@link ReputationEventRepository} against a real PostgreSQL
 * instance (Testcontainers), per ISTQB CTFL v4.0.1 Section 2.2.1. The critical property under
 * test — that {@code insertIfAbsent} is idempotent — depends on the real DB unique constraint
 * ({@code uq_reputation_event} on (user_id, source_type, source_id)) firing an {@code ON
 * CONFLICT DO NOTHING}; a Mockito-mocked repository (as used in {@code ReputationServiceTest})
 * cannot exercise that constraint, so it is verified here instead. Each test rolls back its own
 * transaction, so no manual cleanup is needed.
 */
@Transactional
class ReputationEventRepositoryTest extends AbstractIntegrationTest {

  @Autowired private ReputationEventRepository reputationEventRepository;
  @Autowired private UserRepository userRepository;

  private Integer userId;

  @BeforeEach
  void seedUser() {
    UserEntity user = new UserEntity();
    user.setEmail("rep-user@example.com");
    user.setPassword("hashed-password");
    user.setUsername("rep-user");
    user.setFullName("Rep User");
    userId = userRepository.saveAndFlush(user).getId();
  }

  // =====================================================================
  // insertIfAbsent
  // =====================================================================

  @Nested
  @DisplayName("insertIfAbsent")
  class InsertIfAbsentTests {

    @Test
    @DisplayName("inserts a new event and returns 1 the first time")
    void insertsNewEvent_returnsOne_firstTime() {
      // When
      int inserted =
          reputationEventRepository.insertIfAbsent(
              userId, RepSourceType.REACTION_RECEIVED.name(), "100:2", 1);

      // Then
      assertThat(inserted).isEqualTo(1);
      assertThat(reputationEventRepository.sumPointsByUserId(userId)).isEqualTo(1);
    }

    @Test
    @DisplayName("returns 0 and does not double-count when the same triple is inserted again")
    void doesNotDoubleCount_whenSameTripleInsertedAgain() {
      // Given
      reputationEventRepository.insertIfAbsent(
          userId, RepSourceType.ACCEPTED_ANSWER.name(), "500", 15);

      // When — same (userId, sourceType, sourceId) re-submitted, e.g. a retried request
      int secondInsert =
          reputationEventRepository.insertIfAbsent(
              userId, RepSourceType.ACCEPTED_ANSWER.name(), "500", 15);

      // Then
      assertThat(secondInsert).isEqualTo(0);
      assertThat(reputationEventRepository.sumPointsByUserId(userId)).isEqualTo(15);
    }

    @Test
    @DisplayName("allows the same sourceId under a different sourceType for the same user")
    void allowsSameSourceId_underDifferentSourceType() {
      // Given — e.g. a roadmap node self-verified, then later re-verified via a different tier
      reputationEventRepository.insertIfAbsent(
          userId, RepSourceType.ROADMAP_SELF_VERIFIED.name(), "1:10", 5);

      // When
      int inserted =
          reputationEventRepository.insertIfAbsent(
              userId, RepSourceType.ROADMAP_NODE_VERIFIED.name(), "1:10", 20);

      // Then
      assertThat(inserted).isEqualTo(1);
      assertThat(reputationEventRepository.sumPointsByUserId(userId)).isEqualTo(25);
    }
  }

  // =====================================================================
  // deleteByUserIdAndSourceTypeAndSourceId
  // =====================================================================

  @Nested
  @DisplayName("deleteByUserIdAndSourceTypeAndSourceId")
  class DeleteTests {

    @Test
    @DisplayName("deletes a matching event and returns 1")
    void deletesMatchingEvent_returnsOne() {
      // Given
      reputationEventRepository.insertIfAbsent(
          userId, RepSourceType.REACTION_RECEIVED.name(), "100:2", 1);

      // When
      int deleted =
          reputationEventRepository.deleteByUserIdAndSourceTypeAndSourceId(
              userId, RepSourceType.REACTION_RECEIVED, "100:2");

      // Then
      assertThat(deleted).isEqualTo(1);
      assertThat(reputationEventRepository.sumPointsByUserId(userId)).isEqualTo(0);
    }

    @Test
    @DisplayName("returns 0 when there is no matching event")
    void returnsZero_whenNoMatchingEvent() {
      // When
      int deleted =
          reputationEventRepository.deleteByUserIdAndSourceTypeAndSourceId(
              userId, RepSourceType.REACTION_RECEIVED, "does-not-exist");

      // Then
      assertThat(deleted).isEqualTo(0);
    }
  }

  // =====================================================================
  // sumPointsByUserId
  // =====================================================================

  @Nested
  @DisplayName("sumPointsByUserId")
  class SumPointsTests {

    @Test
    @DisplayName("sums points across every event for the user")
    void sumsPointsAcrossEvents() {
      // Given
      reputationEventRepository.insertIfAbsent(
          userId, RepSourceType.REACTION_RECEIVED.name(), "100:2", 1);
      reputationEventRepository.insertIfAbsent(
          userId, RepSourceType.ACCEPTED_ANSWER.name(), "500", 15);

      // When
      int total = reputationEventRepository.sumPointsByUserId(userId);

      // Then
      assertThat(total).isEqualTo(16);
    }

    @Test
    @DisplayName("returns zero when the user has no events")
    void returnsZero_whenNoEvents() {
      // When
      int total = reputationEventRepository.sumPointsByUserId(userId);

      // Then
      assertThat(total).isEqualTo(0);
    }
  }

  // =====================================================================
  // findDistinctUserIds
  // =====================================================================

  @Nested
  @DisplayName("findDistinctUserIds")
  class FindDistinctUserIdsTests {

    @Test
    @DisplayName("returns each user with at least one event exactly once")
    void returnsEachUserOnce() {
      // Given
      UserEntity otherUser = new UserEntity();
      otherUser.setEmail("other-user@example.com");
      otherUser.setPassword("hashed-password");
      otherUser.setUsername("other-user");
      otherUser.setFullName("Other User");
      Integer otherUserId = userRepository.saveAndFlush(otherUser).getId();

      reputationEventRepository.insertIfAbsent(
          userId, RepSourceType.REACTION_RECEIVED.name(), "100:2", 1);
      reputationEventRepository.insertIfAbsent(
          userId, RepSourceType.ACCEPTED_ANSWER.name(), "500", 15);
      reputationEventRepository.insertIfAbsent(
          otherUserId, RepSourceType.PROJECT_APPLICATION_ACCEPTED.name(), "900", 10);

      // When
      List<Integer> userIds = reputationEventRepository.findDistinctUserIds();

      // Then
      assertThat(userIds).containsExactlyInAnyOrder(userId, otherUserId);
    }

    @Test
    @DisplayName("returns an empty list when there are no events")
    void returnsEmptyList_whenNoEvents() {
      // When
      List<Integer> userIds = reputationEventRepository.findDistinctUserIds();

      // Then
      assertThat(userIds).isEmpty();
    }
  }

  // =====================================================================
  // standard JpaRepository operations (save/findById)
  // =====================================================================

  @Nested
  @DisplayName("save/findById (standard JpaRepository operations)")
  class StandardJpaOperationsTests {

    @Test
    @DisplayName("persists and reloads every field of a directly-saved event")
    void persistsAndReloadsEveryField() {
      // Given
      ReputationEventEntity event =
          ReputationEventEntity.builder()
              .userId(userId)
              .sourceType(RepSourceType.ACCEPTED_ANSWER)
              .sourceId("500")
              .points(15)
              .build();

      // When
      ReputationEventEntity saved = reputationEventRepository.saveAndFlush(event);
      ReputationEventEntity reloaded =
          reputationEventRepository.findById(saved.getId()).orElseThrow();

      // Then
      assertThat(reloaded.getUserId()).isEqualTo(userId);
      assertThat(reloaded.getSourceType()).isEqualTo(RepSourceType.ACCEPTED_ANSWER);
      assertThat(reloaded.getSourceId()).isEqualTo("500");
      assertThat(reloaded.getPoints()).isEqualTo(15);
      assertThat(reloaded.getCreatedAt()).isNotNull();
    }
  }
}
