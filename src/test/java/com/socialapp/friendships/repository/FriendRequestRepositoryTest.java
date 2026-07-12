package com.socialapp.friendships.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import com.socialapp.AbstractIntegrationTest;
import com.socialapp.friendships.entity.FriendRequestEntity;
import com.socialapp.friendships.entity.enums.FriendRequestStatus;
import com.socialapp.security.entity.UserEntity;
import com.socialapp.security.repository.UserRepository;

/**
 * Component integration tests for {@link FriendRequestRepository} against a real PostgreSQL
 * instance (Testcontainers), per ISTQB CTFL v4.0.1 Section 2.2.1. Each test rolls back its own
 * transaction, so no manual cleanup is needed. {@code t_friend_requests} has foreign keys to
 * real users, so each test seeds those via {@link UserRepository}.
 */
@Transactional
class FriendRequestRepositoryTest extends AbstractIntegrationTest {

  @Autowired private FriendRequestRepository friendRequestRepository;
  @Autowired private UserRepository userRepository;

  private Integer userA;
  private Integer userB;
  private Integer userC;

  @BeforeEach
  void seedUsers() {
    userA = userRepository.saveAndFlush(user("a@example.com", "user_a")).getId();
    userB = userRepository.saveAndFlush(user("b@example.com", "user_b")).getId();
    userC = userRepository.saveAndFlush(user("c@example.com", "user_c")).getId();
  }

  private static UserEntity user(String email, String username) {
    UserEntity user = new UserEntity();
    user.setEmail(email);
    user.setPassword("hashed-password");
    user.setUsername(username);
    user.setFullName("Test User");
    return user;
  }

  private static FriendRequestEntity request(
      Integer requesterId, Integer addresseeId, FriendRequestStatus status) {
    FriendRequestEntity entity = new FriendRequestEntity();
    entity.setRequesterId(requesterId);
    entity.setAddresseeId(addresseeId);
    entity.setStatus(status);
    return entity;
  }

  @Nested
  @DisplayName("findByParticipantsAndStatus")
  class FindByParticipantsAndStatus {

    @Test
    @DisplayName("finds a request in the requester->addressee direction")
    void findsRequestInOriginalDirection() {
      // Given
      friendRequestRepository.saveAndFlush(request(userA, userB, FriendRequestStatus.PENDING));

      // When
      Optional<FriendRequestEntity> result =
          friendRequestRepository.findByParticipantsAndStatus(
              userA, userB, FriendRequestStatus.PENDING);

      // Then
      assertThat(result).isPresent();
    }

    @Test
    @DisplayName("finds a request regardless of which participant is passed first")
    void findsRequestInReversedDirection() {
      // Given
      friendRequestRepository.saveAndFlush(request(userA, userB, FriendRequestStatus.PENDING));

      // When
      Optional<FriendRequestEntity> result =
          friendRequestRepository.findByParticipantsAndStatus(
              userB, userA, FriendRequestStatus.PENDING);

      // Then
      assertThat(result).isPresent();
    }

    @Test
    @DisplayName("does not match a request with a different status")
    void doesNotMatchDifferentStatus() {
      // Given
      friendRequestRepository.saveAndFlush(request(userA, userB, FriendRequestStatus.ACCEPTED));

      // When
      Optional<FriendRequestEntity> result =
          friendRequestRepository.findByParticipantsAndStatus(
              userA, userB, FriendRequestStatus.PENDING);

      // Then
      assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("does not match a request between unrelated participants")
    void doesNotMatchUnrelatedParticipants() {
      // Given
      friendRequestRepository.saveAndFlush(request(userA, userB, FriendRequestStatus.PENDING));

      // When
      Optional<FriendRequestEntity> result =
          friendRequestRepository.findByParticipantsAndStatus(
              userA, userC, FriendRequestStatus.PENDING);

      // Then
      assertThat(result).isEmpty();
    }
  }

  @Nested
  @DisplayName("hasPendingRequestBetween")
  class HasPendingRequestBetween {

    @Test
    @DisplayName("returns true when a pending request exists between the pair")
    void returnsTrueWhenPendingRequestExists() {
      // Given
      friendRequestRepository.saveAndFlush(request(userA, userB, FriendRequestStatus.PENDING));

      // When
      boolean result = friendRequestRepository.hasPendingRequestBetween(userA, userB);

      // Then
      assertThat(result).isTrue();
    }

    @Test
    @DisplayName("returns true regardless of participant order")
    void returnsTrueRegardlessOfOrder() {
      // Given
      friendRequestRepository.saveAndFlush(request(userA, userB, FriendRequestStatus.PENDING));

      // When
      boolean result = friendRequestRepository.hasPendingRequestBetween(userB, userA);

      // Then
      assertThat(result).isTrue();
    }

    @Test
    @DisplayName("returns false when the only request between the pair is already accepted")
    void returnsFalseWhenRequestIsNotPending() {
      // Given
      friendRequestRepository.saveAndFlush(request(userA, userB, FriendRequestStatus.ACCEPTED));

      // When
      boolean result = friendRequestRepository.hasPendingRequestBetween(userA, userB);

      // Then
      assertThat(result).isFalse();
    }

    @Test
    @DisplayName("returns false when there is no request between the pair")
    void returnsFalseWhenNoRequestExists() {
      // When
      boolean result = friendRequestRepository.hasPendingRequestBetween(userA, userC);

      // Then
      assertThat(result).isFalse();
    }
  }
}
