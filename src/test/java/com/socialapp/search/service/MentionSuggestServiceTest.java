package com.socialapp.search.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collection;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.socialapp.blocks.service.BlockQueryService;
import com.socialapp.friendships.service.FriendshipService;
import com.socialapp.search.dto.MentionSuggestionDto;
import com.socialapp.security.entity.UserEntity;
import com.socialapp.security.repository.UserRepository;

/**
 * Component (unit) tests for {@link MentionSuggestService}, per ISTQB CTFL v4.0.1 (Section 2.2.1
 * component testing, Section 4.3.2 branch testing, Section 2.1.3 BDD Given/When/Then).
 *
 * <p>The branch that matters most is the one on a blank query: it decides between "your friends"
 * and "everyone whose name contains nothing in particular", and getting it wrong turns an
 * autocomplete into a user-directory dump.
 */
@ExtendWith(MockitoExtension.class)
class MentionSuggestServiceTest {

  private static final Integer CURRENT_USER_ID = 1;

  @Mock private BlockQueryService blockQueryService;
  @Mock private FriendshipService friendshipService;
  @Mock private UserRepository userRepository;

  @InjectMocks private MentionSuggestService mentionSuggestService;

  @Captor private ArgumentCaptor<Collection<Integer>> idsCaptor;

  private static UserEntity user(Integer id, String fullName, String username) {
    UserEntity u = new UserEntity();
    u.setId(id);
    u.setFullName(fullName);
    u.setUsername(username);
    return u;
  }

  @Nested
  @DisplayName("suggest — nothing typed after the @")
  class BlankQueryTests {

    @Test
    @DisplayName("should answer with friends only, never with a slice of the user table")
    void shouldReturnFriendsOnly() {
      // Given
      when(friendshipService.getFriendIds(CURRENT_USER_ID)).thenReturn(List.of(2));
      when(blockQueryService.blockedPairIds(CURRENT_USER_ID)).thenReturn(Set.of());
      when(userRepository.findMentionableFriends(any(), any(), any()))
          .thenReturn(List.of(user(2, "Nguyen Truc", "nguyentruc")));

      // When
      List<MentionSuggestionDto> result = mentionSuggestService.suggest("", 8, CURRENT_USER_ID);

      // Then
      assertThat(result).extracting(MentionSuggestionDto::username).containsExactly("nguyentruc");
      assertThat(result.get(0).isFriend()).isTrue();
      verify(userRepository, never()).suggestMentions(anyString(), any(), any(), any());
    }

    @Test
    @DisplayName("should treat a whitespace-only query as nothing typed")
    void shouldTreatBlankAsNothingTyped() {
      // Given
      when(friendshipService.getFriendIds(CURRENT_USER_ID)).thenReturn(List.of(2));
      when(blockQueryService.blockedPairIds(CURRENT_USER_ID)).thenReturn(Set.of());
      when(userRepository.findMentionableFriends(any(), any(), any())).thenReturn(List.of());

      // When
      mentionSuggestService.suggest("   ", 8, CURRENT_USER_ID);

      // Then
      verify(userRepository, never()).suggestMentions(anyString(), any(), any(), any());
    }

    @Test
    @DisplayName("should send a sentinel friend id when the caller has no friends")
    void shouldSendSentinelWhenFriendless() {
      // Given: IN () is not valid SQL, and a friendless caller is an ordinary state
      when(friendshipService.getFriendIds(CURRENT_USER_ID)).thenReturn(List.of());
      when(blockQueryService.blockedPairIds(CURRENT_USER_ID)).thenReturn(Set.of());
      when(userRepository.findMentionableFriends(any(), any(), any())).thenReturn(List.of());

      // When
      List<MentionSuggestionDto> result = mentionSuggestService.suggest("", 8, CURRENT_USER_ID);

      // Then
      assertThat(result).isEmpty();
      verify(userRepository).findMentionableFriends(idsCaptor.capture(), any(), any());
      assertThat(idsCaptor.getValue()).containsExactly(-1);
    }
  }

  @Nested
  @DisplayName("suggest — something typed after the @")
  class TypedQueryTests {

    @Test
    @DisplayName("should flag which rows are friends, so the client can group them")
    void shouldFlagFriends() {
      // Given
      when(friendshipService.getFriendIds(CURRENT_USER_ID)).thenReturn(List.of(2));
      when(blockQueryService.blockedPairIds(CURRENT_USER_ID)).thenReturn(Set.of());
      when(userRepository.suggestMentions(anyString(), any(), any(), any()))
          .thenReturn(
              List.of(user(2, "Nguyen Truc", "nguyentruc"), user(3, "Tran Thinh", "tranthinh")));

      // When
      List<MentionSuggestionDto> result = mentionSuggestService.suggest("tr", 8, CURRENT_USER_ID);

      // Then
      assertThat(result).extracting(MentionSuggestionDto::isFriend).containsExactly(true, false);
    }

    @Test
    @DisplayName("should drop the @ a client may have left on the front of the query")
    void shouldStripLeadingAt() {
      // Given: no stored handle contains an @, so passing it through matches nobody
      when(friendshipService.getFriendIds(CURRENT_USER_ID)).thenReturn(List.of());
      when(blockQueryService.blockedPairIds(CURRENT_USER_ID)).thenReturn(Set.of());
      when(userRepository.suggestMentions(anyString(), any(), any(), any())).thenReturn(List.of());

      // When
      mentionSuggestService.suggest("@tru", 8, CURRENT_USER_ID);

      // Then
      verify(userRepository).suggestMentions(eq("tru"), any(), any(), any());
    }

    @Test
    @DisplayName("should escape a LIKE wildcard typed into the query")
    void shouldSanitizeWildcards() {
      // Given
      when(friendshipService.getFriendIds(CURRENT_USER_ID)).thenReturn(List.of());
      when(blockQueryService.blockedPairIds(CURRENT_USER_ID)).thenReturn(Set.of());
      when(userRepository.suggestMentions(anyString(), any(), any(), any())).thenReturn(List.of());

      // When
      mentionSuggestService.suggest("100%", 8, CURRENT_USER_ID);

      // Then
      verify(userRepository).suggestMentions(eq("100\\%"), any(), any(), any());
    }

    @Test
    @DisplayName("should hide a handle the mention scanner would not find again")
    void shouldHideUnmentionableHandle() {
      // Given: "ly" is two characters, below MentionScanner's floor — tagging it notifies nobody
      when(friendshipService.getFriendIds(CURRENT_USER_ID)).thenReturn(List.of());
      when(blockQueryService.blockedPairIds(CURRENT_USER_ID)).thenReturn(Set.of());
      when(userRepository.suggestMentions(anyString(), any(), any(), any()))
          .thenReturn(List.of(user(2, "Ly Nguyen", "ly"), user(3, "Ly Tran", "lytran")));

      // When
      List<MentionSuggestionDto> result = mentionSuggestService.suggest("ly", 8, CURRENT_USER_ID);

      // Then
      assertThat(result).extracting(MentionSuggestionDto::username).containsExactly("lytran");
    }

    @Test
    @DisplayName("should return at most the requested limit, however many were fetched_boundary")
    void shouldTrimToLimit() {
      // Given: the query is over-fetched so the unmentionable filter cannot short the dropdown
      when(friendshipService.getFriendIds(CURRENT_USER_ID)).thenReturn(List.of());
      when(blockQueryService.blockedPairIds(CURRENT_USER_ID)).thenReturn(Set.of());
      when(userRepository.suggestMentions(anyString(), any(), any(), any()))
          .thenReturn(
              List.of(
                  user(2, "Tran A", "tran-a"),
                  user(3, "Tran B", "tran-b"),
                  user(4, "Tran C", "tran-c"),
                  user(5, "Tran D", "tran-d")));

      // When
      List<MentionSuggestionDto> result = mentionSuggestService.suggest("tran", 2, CURRENT_USER_ID);

      // Then
      assertThat(result).hasSize(2);
    }

    @Test
    @DisplayName("should exclude the caller and their block set in both directions")
    void shouldExcludeCallerAndBlocks() {
      // Given: completing the name of someone who blocked you undoes the block
      when(friendshipService.getFriendIds(CURRENT_USER_ID)).thenReturn(List.of());
      when(blockQueryService.blockedPairIds(CURRENT_USER_ID)).thenReturn(Set.of(99));
      when(userRepository.suggestMentions(anyString(), any(), any(), any())).thenReturn(List.of());

      // When
      mentionSuggestService.suggest("tr", 8, CURRENT_USER_ID);

      // Then
      verify(userRepository).suggestMentions(anyString(), any(), idsCaptor.capture(), any());
      assertThat(idsCaptor.getValue()).containsExactlyInAnyOrder(99, CURRENT_USER_ID);
    }
  }
}
