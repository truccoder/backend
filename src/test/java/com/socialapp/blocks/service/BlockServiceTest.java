package com.socialapp.blocks.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.socialapp.blocks.entity.UserBlockEntity;
import com.socialapp.blocks.entity.UserBlockId;
import com.socialapp.blocks.repository.UserBlockRepository;
import com.socialapp.common.exception.NotFoundException;
import com.socialapp.common.exception.ValidationException;
import com.socialapp.friendships.repository.FriendRequestRepository;
import com.socialapp.friendships.service.FriendshipService;
import com.socialapp.security.dto.PublicUserResponse;
import com.socialapp.security.entity.UserEntity;
import com.socialapp.security.repository.UserRepository;

/**
 * Component (unit) tests for {@link BlockService}, per ISTQB CTFL v4.0.1 (Section 2.2.1 component
 * testing, Section 4.3.2 branch testing, Section 2.1.3 BDD Given/When/Then).
 */
@ExtendWith(MockitoExtension.class)
class BlockServiceTest {

  private static final Integer BLOCKER_ID = 1;
  private static final Integer BLOCKED_ID = 2;

  @Mock private UserBlockRepository userBlockRepository;
  @Mock private UserRepository userRepository;
  @Mock private FriendshipService friendshipService;
  @Mock private FriendRequestRepository friendRequestRepository;

  @InjectMocks private BlockService blockService;

  @Captor private ArgumentCaptor<UserBlockEntity> blockCaptor;

  private static UserEntity user(Integer id, String fullName) {
    UserEntity user = new UserEntity();
    user.setId(id);
    user.setFullName(fullName);
    user.setUsername("u" + id);
    user.setEmail("u" + id + "@example.com");
    user.setEliteScore(10);
    user.setCreatedAt(OffsetDateTime.parse("2026-01-01T00:00:00Z"));
    return user;
  }

  private static UserBlockEntity block(Integer blockerId, Integer blockedId) {
    UserBlockEntity entity = new UserBlockEntity();
    entity.setId(new UserBlockId(blockerId, blockedId));
    return entity;
  }

  @Nested
  @DisplayName("block")
  class Block {

    @Test
    @DisplayName("stores the block and severs the friendship and any pending request")
    void storesAndSevers() {
      // Given
      when(userRepository.existsById(BLOCKED_ID)).thenReturn(true);
      when(userBlockRepository.existsById(new UserBlockId(BLOCKER_ID, BLOCKED_ID)))
          .thenReturn(false);

      // When
      blockService.block(BLOCKER_ID, BLOCKED_ID);

      // Then
      verify(userBlockRepository).save(blockCaptor.capture());
      assertThat(blockCaptor.getValue().getId().getBlockerId()).isEqualTo(BLOCKER_ID);
      assertThat(blockCaptor.getValue().getId().getBlockedId()).isEqualTo(BLOCKED_ID);
      // A block that leaves the friendship standing leaves the blocked user inside every
      // friends-only audience — the severing is the point, not a side effect.
      verify(friendshipService).unfriend(BLOCKER_ID, BLOCKED_ID);
      verify(friendRequestRepository).cancelPendingBetween(BLOCKER_ID, BLOCKED_ID);
    }

    @Test
    @DisplayName("is idempotent: a repeat block writes nothing but still re-severs")
    void repeatBlockIsIdempotent() {
      // Given
      when(userRepository.existsById(BLOCKED_ID)).thenReturn(true);
      when(userBlockRepository.existsById(new UserBlockId(BLOCKER_ID, BLOCKED_ID)))
          .thenReturn(true);

      // When
      blockService.block(BLOCKER_ID, BLOCKED_ID);

      // Then
      verify(userBlockRepository, never()).save(org.mockito.ArgumentMatchers.any());
      verify(friendshipService).unfriend(BLOCKER_ID, BLOCKED_ID);
    }

    @Test
    @DisplayName("rejects blocking yourself")
    void rejectsSelfBlock() {
      // When / Then: allowed through, this would erase the user from their own feed and search
      assertThatThrownBy(() -> blockService.block(BLOCKER_ID, BLOCKER_ID))
          .isInstanceOf(ValidationException.class);
      verify(userBlockRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("throws NotFound when the user being blocked does not exist")
    void rejectsUnknownUser() {
      // Given
      when(userRepository.existsById(BLOCKED_ID)).thenReturn(false);

      // When / Then
      assertThatThrownBy(() -> blockService.block(BLOCKER_ID, BLOCKED_ID))
          .isInstanceOf(NotFoundException.class);
    }
  }

  @Nested
  @DisplayName("unblock")
  class Unblock {

    @Test
    @DisplayName("deletes the block when it exists")
    void deletesExistingBlock() {
      // Given
      UserBlockId id = new UserBlockId(BLOCKER_ID, BLOCKED_ID);
      when(userBlockRepository.existsById(id)).thenReturn(true);

      // When
      blockService.unblock(BLOCKER_ID, BLOCKED_ID);

      // Then
      verify(userBlockRepository).deleteById(id);
    }

    @Test
    @DisplayName("is a no-op when there is no such block")
    void noOpWhenAbsent() {
      // Given
      UserBlockId id = new UserBlockId(BLOCKER_ID, BLOCKED_ID);
      when(userBlockRepository.existsById(id)).thenReturn(false);

      // When
      blockService.unblock(BLOCKER_ID, BLOCKED_ID);

      // Then
      verify(userBlockRepository, never()).deleteById(id);
    }

    @Test
    @DisplayName("does not restore the friendship the block ended")
    void doesNotRestoreFriendship() {
      // Given
      when(userBlockRepository.existsById(new UserBlockId(BLOCKER_ID, BLOCKED_ID)))
          .thenReturn(true);

      // When
      blockService.unblock(BLOCKER_ID, BLOCKED_ID);

      // Then
      verify(friendshipService, never()).areFriends(BLOCKER_ID, BLOCKED_ID);
    }
  }

  @Nested
  @DisplayName("getBlockedUsers")
  class GetBlockedUsers {

    @Test
    @DisplayName("returns the blocked users in the order they were blocked")
    void keepsBlockedAtOrder() {
      // Given: the repository returns ids newest-first, findAllById returns them in id order
      when(userBlockRepository.findByIdBlockerIdOrderByCreatedAtDesc(BLOCKER_ID))
          .thenReturn(List.of(block(BLOCKER_ID, 9), block(BLOCKER_ID, 4)));
      when(userRepository.findAllById(List.of(9, 4)))
          .thenReturn(List.of(user(4, "Four"), user(9, "Nine")));

      // When
      List<PublicUserResponse> blocked = blockService.getBlockedUsers(BLOCKER_ID);

      // Then
      assertThat(blocked).extracting(PublicUserResponse::id).containsExactly(9, 4);
    }

    @Test
    @DisplayName("returns an empty list without touching the user table when nothing is blocked")
    void emptyWhenNoBlocks() {
      // Given
      when(userBlockRepository.findByIdBlockerIdOrderByCreatedAtDesc(BLOCKER_ID))
          .thenReturn(List.of());

      // When
      List<PublicUserResponse> blocked = blockService.getBlockedUsers(BLOCKER_ID);

      // Then
      assertThat(blocked).isEmpty();
      verify(userRepository, never()).findAllById(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("skips a blocked id whose user row is gone")
    void skipsMissingUser() {
      // Given
      when(userBlockRepository.findByIdBlockerIdOrderByCreatedAtDesc(BLOCKER_ID))
          .thenReturn(List.of(block(BLOCKER_ID, 9), block(BLOCKER_ID, 4)));
      when(userRepository.findAllById(List.of(9, 4))).thenReturn(List.of(user(9, "Nine")));

      // When
      List<PublicUserResponse> blocked = blockService.getBlockedUsers(BLOCKER_ID);

      // Then
      assertThat(blocked).extracting(PublicUserResponse::id).containsExactly(9);
    }
  }
}
