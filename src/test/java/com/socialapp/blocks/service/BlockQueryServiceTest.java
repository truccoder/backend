package com.socialapp.blocks.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.socialapp.blocks.repository.UserBlockRepository;

/**
 * Component (unit) tests for {@link BlockQueryService}, per ISTQB CTFL v4.0.1 (Section 2.2.1
 * component testing, Section 2.1.3 BDD Given/When/Then).
 *
 * <p>The behaviour being pinned is that blocking filters <b>both ways</b>. Every other module
 * builds its filter out of this one method, so a regression to one-directional filtering here
 * would quietly reopen the leak in all seven places at once.
 */
@ExtendWith(MockitoExtension.class)
class BlockQueryServiceTest {

  private static final Integer USER_ID = 1;

  @Mock private UserBlockRepository userBlockRepository;

  @InjectMocks private BlockQueryService blockQueryService;

  @Nested
  @DisplayName("blockedPairIds")
  class BlockedPairIds {

    @Test
    @DisplayName("returns people the user blocked and people who blocked the user, as one set")
    void unionsBothDirections() {
      // Given
      when(userBlockRepository.findBlockedIds(USER_ID)).thenReturn(List.of(2, 3));
      when(userBlockRepository.findBlockerIds(USER_ID)).thenReturn(List.of(4));

      // When / Then
      assertThat(blockQueryService.blockedPairIds(USER_ID)).containsExactlyInAnyOrder(2, 3, 4);
    }

    @Test
    @DisplayName("de-duplicates a mutual block")
    void deduplicatesMutualBlock() {
      // Given: both blocked each other, so 2 appears in both halves
      when(userBlockRepository.findBlockedIds(USER_ID)).thenReturn(List.of(2));
      when(userBlockRepository.findBlockerIds(USER_ID)).thenReturn(List.of(2));

      // When / Then
      assertThat(blockQueryService.blockedPairIds(USER_ID)).containsExactly(2);
    }

    @Test
    @DisplayName("returns an empty set for a user with no blocks at all")
    void emptyForCleanUser() {
      // Given
      when(userBlockRepository.findBlockedIds(USER_ID)).thenReturn(List.of());
      when(userBlockRepository.findBlockerIds(USER_ID)).thenReturn(List.of());

      // When / Then
      assertThat(blockQueryService.blockedPairIds(USER_ID)).isEmpty();
    }
  }

  @Nested
  @DisplayName("isBlockedEitherWay")
  class IsBlockedEitherWay {

    @Test
    @DisplayName("delegates the pair check to a single query")
    void delegates() {
      // Given
      when(userBlockRepository.existsBetween(USER_ID, 2)).thenReturn(true);

      // When / Then
      assertThat(blockQueryService.isBlockedEitherWay(USER_ID, 2)).isTrue();
    }
  }

  @Nested
  @DisplayName("filterOutBlocked")
  class FilterOutBlocked {

    @Test
    @DisplayName("removes blocked candidates and keeps the rest")
    void removesBlocked() {
      // Given
      when(userBlockRepository.findBlockedIds(USER_ID)).thenReturn(List.of(3));
      when(userBlockRepository.findBlockerIds(USER_ID)).thenReturn(List.of(5));

      // When
      var allowed = blockQueryService.filterOutBlocked(USER_ID, List.of(2, 3, 4, 5));

      // Then
      assertThat(allowed).containsExactlyInAnyOrder(2, 4);
    }

    @Test
    @DisplayName("short-circuits on an empty candidate list without querying")
    void shortCircuitsOnEmptyInput() {
      // When
      var allowed = blockQueryService.filterOutBlocked(USER_ID, List.of());

      // Then
      assertThat(allowed).isEmpty();
      verify(userBlockRepository, never()).findBlockedIds(USER_ID);
    }
  }

  @Nested
  @DisplayName("guest (null user id)")
  class GuestCaller {

    @Test
    @DisplayName("has an empty block set, answered without touching the database")
    void emptyWithoutQuerying() {
      // When / Then — the guest-readable endpoints ask this on every request; making them pay two
      // queries to be told "nobody" would be a cost with no answer behind it
      assertThat(blockQueryService.blockedPairIds(null)).isEmpty();
      verifyNoInteractions(userBlockRepository);
    }

    @Test
    @DisplayName("is never in a block relationship with anyone")
    void neverBlocked() {
      // When / Then
      assertThat(blockQueryService.isBlockedEitherWay(null, 2)).isFalse();
      assertThat(blockQueryService.isBlockedEitherWay(1, null)).isFalse();
      verifyNoInteractions(userBlockRepository);
    }
  }
}
