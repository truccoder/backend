package com.socialapp.search.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.socialapp.friendships.repository.FriendshipRepository;

/**
 * Component (unit) test for {@link FriendshipQueryService}, per ISTQB CTFL v4.0.1 (Section 2.2.1
 * component testing, Section 2.1.3 BDD Given/When/Then). The class is a single-line delegate with
 * no branches, so this exists for behavioral correctness/line coverage rather than to satisfy a
 * branch-coverage requirement that doesn't apply here.
 */
@ExtendWith(MockitoExtension.class)
class FriendshipQueryServiceTest {

  @Mock private FriendshipRepository friendshipRepository;

  @InjectMocks private FriendshipQueryService friendshipQueryService;

  @Test
  @DisplayName("should delegate to the friendship repository")
  void shouldDelegateToRepository() {
    // Given
    when(friendshipRepository.findFriendIds(1)).thenReturn(List.of(2, 3));

    // When
    List<Integer> result = friendshipQueryService.getFriendIds(1);

    // Then
    assertThat(result).containsExactly(2, 3);
  }
}
