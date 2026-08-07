package com.socialapp.search.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.socialapp.blocks.service.BlockQueryService;
import com.socialapp.bookstore.entity.BookEntity;
import com.socialapp.bookstore.repository.BookRepository;
import com.socialapp.bookstore.service.BookStorageService;
import com.socialapp.search.dto.SuggestionDto;
import com.socialapp.search.dto.SuggestionType;
import com.socialapp.security.entity.UserEntity;
import com.socialapp.security.repository.UserRepository;

/**
 * Component (unit) tests for {@link SuggestService}, per ISTQB CTFL v4.0.1 (Section 2.2.1 component
 * testing, Section 4.3.2 branch testing, Section 2.1.3 BDD Given/When/Then).
 */
@ExtendWith(MockitoExtension.class)
class SuggestServiceTest {

  private static final Integer CURRENT_USER_ID = 1;

  @Mock private BlockQueryService blockQueryService;
  @Mock private UserRepository userRepository;
  @Mock private BookRepository bookRepository;
  @Mock private BookStorageService bookStorageService;

  @InjectMocks private SuggestService suggestService;

  private static UserEntity user(Integer id, String fullName, String username) {
    UserEntity u = new UserEntity();
    u.setId(id);
    u.setFullName(fullName);
    u.setUsername(username);
    return u;
  }

  private static BookEntity book(Integer id, String title) {
    BookEntity b = new BookEntity();
    b.setId(id);
    b.setTitle(title);
    return b;
  }

  @Nested
  @DisplayName("suggest")
  class SuggestTests {

    @Test
    @DisplayName("should return people first, then books, in one flat list")
    void shouldReturnUsersThenBooks() {
      // Given
      when(blockQueryService.blockedPairIds(CURRENT_USER_ID)).thenReturn(Set.of());
      when(userRepository.suggest(any(), any(), any()))
          .thenReturn(List.of(user(1, "Nguyen Truc", "nguyentruc")));
      when(bookRepository.suggestByTitle(any(), any()))
          .thenReturn(List.of(book(9, "Lap trinh Java")));

      // When
      List<SuggestionDto> result = suggestService.suggest("lap", 8, CURRENT_USER_ID);

      // Then
      assertThat(result).hasSize(2);
      assertThat(result.get(0).type()).isEqualTo(SuggestionType.USER);
      assertThat(result.get(0).sublabel()).isEqualTo("@nguyentruc");
      assertThat(result.get(1).type()).isEqualTo(SuggestionType.BOOK);
      assertThat(result.get(1).label()).isEqualTo("Lap trinh Java");
    }

    @Test
    @DisplayName("should pass the caller's block set to the query as the exclusion list")
    void shouldPassBlockSetAsExclusion() {
      // Given: a dropdown that completes the name of someone who blocked the viewer hands back
      // exactly the link the block exists to take away
      when(blockQueryService.blockedPairIds(CURRENT_USER_ID)).thenReturn(Set.of(99));
      when(userRepository.suggest(any(), any(), any())).thenReturn(List.of());
      when(bookRepository.suggestByTitle(any(), any())).thenReturn(List.of());

      // When
      suggestService.suggest("x", 8, CURRENT_USER_ID);

      // Then
      verify(userRepository).suggest(any(), eq(Set.of(99)), any());
    }

    @Test
    @DisplayName("should substitute a sentinel id when the caller has blocked nobody")
    void shouldUseSentinelWhenNoBlocks() {
      // Given: NOT IN () is not valid SQL, and having blocked nobody is the normal case
      when(blockQueryService.blockedPairIds(CURRENT_USER_ID)).thenReturn(Set.of());
      when(userRepository.suggest(any(), any(), any())).thenReturn(List.of());
      when(bookRepository.suggestByTitle(any(), any())).thenReturn(List.of());

      // When
      suggestService.suggest("x", 8, CURRENT_USER_ID);

      // Then
      verify(userRepository).suggest(any(), eq(List.of(-1)), any());
    }

    @Test
    @DisplayName("should never return more rows than the requested limit")
    void shouldTrimToLimit() {
      // Given
      when(blockQueryService.blockedPairIds(CURRENT_USER_ID)).thenReturn(Set.of());
      when(userRepository.suggest(any(), any(), any()))
          .thenReturn(List.of(user(1, "A", "a"), user(2, "B", "b")));
      when(bookRepository.suggestByTitle(any(), any())).thenReturn(List.of(book(9, "C")));

      // When: both lists are filled to limit and the whole thing trimmed afterwards, so a query
      // matching only books still fills the dropdown
      List<SuggestionDto> result = suggestService.suggest("x", 2, CURRENT_USER_ID);

      // Then
      assertThat(result).hasSize(2);
    }

    @Test
    @DisplayName("should tolerate a user with no handle")
    void shouldHandleNullUsername() {
      // Given
      when(blockQueryService.blockedPairIds(CURRENT_USER_ID)).thenReturn(Set.of());
      when(userRepository.suggest(any(), any(), any()))
          .thenReturn(List.of(user(1, "No Handle", null)));
      when(bookRepository.suggestByTitle(any(), any())).thenReturn(List.of());

      // When
      List<SuggestionDto> result = suggestService.suggest("x", 8, CURRENT_USER_ID);

      // Then: null rather than a bare "@"
      assertThat(result.get(0).sublabel()).isNull();
    }
  }
}
