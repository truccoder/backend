package com.socialapp.moderation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.socialapp.moderation.dto.BanDetailsDto;
import com.socialapp.moderation.entity.UserViolationEntity;
import com.socialapp.moderation.enums.ViolationType;
import com.socialapp.moderation.repository.UserViolationRepository;

/**
 * Component (unit) tests for {@link BanDetailsService}, per ISTQB CTFL v4.0.1 (Section 2.2.1
 * component testing, Section 4.3.2 branch testing, Section 2.1.3 BDD Given/When/Then).
 */
@ExtendWith(MockitoExtension.class)
class BanDetailsServiceTest {

  private static final Integer USER_ID = 9001;
  private static final OffsetDateTime BANNED_UNTIL = OffsetDateTime.parse("2026-12-31T00:00:00Z");

  @Mock private UserViolationRepository userViolationRepository;

  @InjectMocks private BanDetailsService banDetailsService;

  @Test
  @DisplayName("should describe the ban using the most recent violation")
  void shouldUseMostRecentViolation() {
    // Given: the repository returns newest first
    when(userViolationRepository.findByUserIdOrderByCreatedAtDesc(USER_ID))
        .thenReturn(
            List.of(
                UserViolationEntity.builder()
                    .id(2L)
                    .userId(USER_ID)
                    .violationType(ViolationType.SPAM)
                    .description("Admin manual review: repeated advertising")
                    .build(),
                UserViolationEntity.builder()
                    .id(1L)
                    .userId(USER_ID)
                    .violationType(ViolationType.INSULT)
                    .description("older")
                    .build()));

    // When
    BanDetailsDto result = banDetailsService.describe(USER_ID, BANNED_UNTIL);

    // Then: a ban follows the last straw, and that is the one the user will recognise
    assertThat(result.getBannedUntil()).isEqualTo(BANNED_UNTIL);
    assertThat(result.getViolationType()).isEqualTo(ViolationType.SPAM);
    assertThat(result.getReason()).isEqualTo("Admin manual review: repeated advertising");
  }

  @Test
  @DisplayName("should return the date alone when nothing was recorded")
  void shouldReturnDateOnlyWhenNoViolations() {
    // Given: a ban applied straight in the database, or one predating the violation log
    when(userViolationRepository.findByUserIdOrderByCreatedAtDesc(USER_ID)).thenReturn(List.of());

    // When
    BanDetailsDto result = banDetailsService.describe(USER_ID, BANNED_UNTIL);

    // Then: null rather than a guessed reason — the date alone is still more than the prose
    // sentence gave a client
    assertThat(result.getBannedUntil()).isEqualTo(BANNED_UNTIL);
    assertThat(result.getViolationType()).isNull();
    assertThat(result.getReason()).isNull();
  }
}
