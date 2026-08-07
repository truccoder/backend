package com.socialapp.friendships.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import com.socialapp.AbstractIntegrationTest;
import com.socialapp.friendships.entity.UserNode;

/**
 * Component integration tests for {@link FriendshipRepository} against a real Neo4j instance
 * (Testcontainers), per ISTQB CTFL v4.0.1 Section 2.2.1. {@code FriendshipRepository} is bound to
 * its own {@code neo4jTransactionManager} (see {@code Neo4jConfig}), so each test must roll back
 * against that specific manager rather than the default JPA one for data isolation to hold.
 */
@Transactional("neo4jTransactionManager")
class FriendshipRepositoryTest extends AbstractIntegrationTest {

  @Autowired private FriendshipRepository friendshipRepository;

  private static final Integer USER_1 = 1;
  private static final Integer USER_2 = 2;
  private static final Integer USER_3 = 3;

  @Nested
  @DisplayName("mergeUser")
  class MergeUser {

    @Test
    @DisplayName("creates a new user node")
    void createsNewUserNode() {
      // When
      UserNode result = friendshipRepository.mergeUser(USER_1);

      // Then
      assertThat(result.getUserId()).isEqualTo(USER_1);
    }

    @Test
    @DisplayName("is idempotent: merging the same userId twice does not create a duplicate node")
    void isIdempotentForSameUserId() {
      // Given
      friendshipRepository.mergeUser(USER_1);

      // When
      friendshipRepository.mergeUser(USER_1);

      // Then
      assertThat(friendshipRepository.count()).isEqualTo(1);
    }
  }

  @Nested
  @DisplayName("findByUserId")
  class FindByUserId {

    @Test
    @DisplayName("finds a previously merged user node")
    void findsExistingUserNode() {
      // Given
      friendshipRepository.mergeUser(USER_1);

      // When
      Optional<UserNode> result = friendshipRepository.findByUserId(USER_1);

      // Then
      assertThat(result).isPresent();
    }

    @Test
    @DisplayName("returns empty when the user node does not exist")
    void returnsEmptyWhenNodeMissing() {
      // When
      Optional<UserNode> result = friendshipRepository.findByUserId(USER_1);

      // Then
      assertThat(result).isEmpty();
    }
  }

  @Nested
  @DisplayName("createFriendship / areFriends")
  class CreateFriendshipAndAreFriends {

    @Test
    @DisplayName("two merged users become friends after createFriendship")
    void createsFriendshipBetweenTwoUsers() {
      // Given
      friendshipRepository.mergeUser(USER_1);
      friendshipRepository.mergeUser(USER_2);

      // When
      friendshipRepository.createFriendship(USER_1, USER_2);

      // Then
      assertThat(friendshipRepository.areFriends(USER_1, USER_2)).isTrue();
    }

    @Test
    @DisplayName("the friendship is queryable regardless of argument order")
    void friendshipIsQueryableInReverseOrder() {
      // Given
      friendshipRepository.mergeUser(USER_1);
      friendshipRepository.mergeUser(USER_2);
      friendshipRepository.createFriendship(USER_1, USER_2);

      // When
      boolean result = friendshipRepository.areFriends(USER_2, USER_1);

      // Then
      assertThat(result).isTrue();
    }

    @Test
    @DisplayName("returns false for two users that are not friends")
    void returnsFalseWhenNotFriends() {
      // Given
      friendshipRepository.mergeUser(USER_1);
      friendshipRepository.mergeUser(USER_2);

      // When
      boolean result = friendshipRepository.areFriends(USER_1, USER_2);

      // Then
      assertThat(result).isFalse();
    }
  }

  @Nested
  @DisplayName("findFriendIds / countFriends")
  class FindFriendIdsAndCountFriends {

    @Test
    @DisplayName("returns every friend id of a user")
    void returnsAllFriendIds() {
      // Given
      friendshipRepository.mergeUser(USER_1);
      friendshipRepository.mergeUser(USER_2);
      friendshipRepository.mergeUser(USER_3);
      friendshipRepository.createFriendship(USER_1, USER_2);
      friendshipRepository.createFriendship(USER_1, USER_3);

      // When
      List<Integer> result = friendshipRepository.findFriendIds(USER_1);

      // Then
      assertThat(result).containsExactlyInAnyOrder(USER_2, USER_3);
    }

    @Test
    @DisplayName("returns an empty list for a user with no friends")
    void returnsEmptyListWhenNoFriends() {
      // Given
      friendshipRepository.mergeUser(USER_1);

      // When
      List<Integer> result = friendshipRepository.findFriendIds(USER_1);

      // Then
      assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("counts the number of friends")
    void countsFriends() {
      // Given
      friendshipRepository.mergeUser(USER_1);
      friendshipRepository.mergeUser(USER_2);
      friendshipRepository.mergeUser(USER_3);
      friendshipRepository.createFriendship(USER_1, USER_2);
      friendshipRepository.createFriendship(USER_1, USER_3);

      // When
      long result = friendshipRepository.countFriends(USER_1);

      // Then
      assertThat(result).isEqualTo(2);
    }

    @Test
    @DisplayName("counts zero friends for an isolated user")
    void countsZeroForIsolatedUser() {
      // Given
      friendshipRepository.mergeUser(USER_1);

      // When
      long result = friendshipRepository.countFriends(USER_1);

      // Then
      assertThat(result).isEqualTo(0);
    }
  }

  @Nested
  @DisplayName("findFriendIdsAfterCursor")
  class FindFriendIdsAfterCursor {

    @Test
    @DisplayName("returns friends from the beginning when the cursor is null")
    void returnsFromBeginningWhenCursorIsNull() {
      // Given
      friendshipRepository.mergeUser(USER_1);
      friendshipRepository.mergeUser(USER_2);
      friendshipRepository.mergeUser(USER_3);
      friendshipRepository.createFriendship(USER_1, USER_2);
      friendshipRepository.createFriendship(USER_1, USER_3);

      // When
      List<Integer> result = friendshipRepository.findFriendIdsAfterCursor(USER_1, null, 10);

      // Then
      assertThat(result).containsExactly(USER_2, USER_3);
    }

    @Test
    @DisplayName("excludes the friend id at the cursor itself (boundary: cursor)")
    void excludesFriendAtCursor() {
      // Given
      friendshipRepository.mergeUser(USER_1);
      friendshipRepository.mergeUser(USER_2);
      friendshipRepository.mergeUser(USER_3);
      friendshipRepository.createFriendship(USER_1, USER_2);
      friendshipRepository.createFriendship(USER_1, USER_3);

      // When
      List<Integer> result = friendshipRepository.findFriendIdsAfterCursor(USER_1, USER_2, 10);

      // Then
      assertThat(result).containsExactly(USER_3);
    }

    @Test
    @DisplayName("includes the friend id just after the cursor (boundary: cursor + 1)")
    void includesFriendJustAfterCursor() {
      // Given
      friendshipRepository.mergeUser(USER_1);
      friendshipRepository.mergeUser(USER_2);
      friendshipRepository.createFriendship(USER_1, USER_2);

      // When
      List<Integer> result = friendshipRepository.findFriendIdsAfterCursor(USER_1, USER_2 - 1, 10);

      // Then
      assertThat(result).containsExactly(USER_2);
    }

    @Test
    @DisplayName("respects the page limit")
    void respectsLimit() {
      // Given
      friendshipRepository.mergeUser(USER_1);
      friendshipRepository.mergeUser(USER_2);
      friendshipRepository.mergeUser(USER_3);
      friendshipRepository.createFriendship(USER_1, USER_2);
      friendshipRepository.createFriendship(USER_1, USER_3);

      // When
      List<Integer> result = friendshipRepository.findFriendIdsAfterCursor(USER_1, null, 1);

      // Then
      assertThat(result).containsExactly(USER_2);
    }
  }

  @Nested
  @DisplayName("deleteFriendship")
  class DeleteFriendship {

    @Test
    @DisplayName("removes the relationship whichever direction it was written in")
    void removesUndirectedRelationship() {
      // Given: created as (1)-[:FRIENDS_WITH]-(2)
      friendshipRepository.mergeUser(USER_1);
      friendshipRepository.mergeUser(USER_2);
      friendshipRepository.createFriendship(USER_1, USER_2);

      // When: deleted with the arguments the other way round
      friendshipRepository.deleteFriendship(USER_2, USER_1);

      // Then — a directed delete would have missed the stored direction and left an orphan that
      // countFriends still counts
      assertThat(friendshipRepository.areFriends(USER_1, USER_2)).isFalse();
      assertThat(friendshipRepository.countFriends(USER_1)).isZero();
      assertThat(friendshipRepository.countFriends(USER_2)).isZero();
    }

    @Test
    @DisplayName("leaves other friendships of the same users alone")
    void leavesOtherFriendshipsAlone() {
      // Given
      friendshipRepository.mergeUser(USER_1);
      friendshipRepository.mergeUser(USER_2);
      friendshipRepository.mergeUser(USER_3);
      friendshipRepository.createFriendship(USER_1, USER_2);
      friendshipRepository.createFriendship(USER_1, USER_3);

      // When
      friendshipRepository.deleteFriendship(USER_1, USER_2);

      // Then
      assertThat(friendshipRepository.areFriends(USER_1, USER_3)).isTrue();
      assertThat(friendshipRepository.countFriends(USER_1)).isEqualTo(1);
    }

    @Test
    @DisplayName("is a no-op when the two were never friends")
    void isNoOpForStrangers() {
      // Given
      friendshipRepository.mergeUser(USER_1);
      friendshipRepository.mergeUser(USER_2);

      // When / Then — idempotency is a contract of DELETE /v1/api/friendships/{userId}
      friendshipRepository.deleteFriendship(USER_1, USER_2);
      assertThat(friendshipRepository.areFriends(USER_1, USER_2)).isFalse();
    }
  }
}
