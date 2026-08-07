package com.socialapp.knowledge.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.socialapp.common.exception.NotFoundException;
import com.socialapp.knowledge.dto.CreateTokenRequestDto;
import com.socialapp.knowledge.dto.CreateTokenResponseDto;
import com.socialapp.knowledge.dto.PersonalAccessTokenResponseDto;
import com.socialapp.knowledge.entity.PersonalAccessTokenEntity;
import com.socialapp.knowledge.entity.enums.VaultPermission;
import com.socialapp.knowledge.repository.PersonalAccessTokenRepository;

/**
 * Component (unit) tests for {@link PersonalAccessTokenService}, per ISTQB CTFL v4.0.1 (Section
 * 2.2.1 component testing, Section 5.1.6 test pyramid, Section 4.3.2 branch testing, Section 2.1.3
 * BDD Given/When/Then) — see {@code PostServiceTest} for the full rationale.
 */
@ExtendWith(MockitoExtension.class)
class PersonalAccessTokenServiceTest {

  private static final Integer USER_ID = 1;

  @Mock private PersonalAccessTokenRepository tokenRepository;

  @InjectMocks private PersonalAccessTokenService personalAccessTokenService;

  @Captor private ArgumentCaptor<PersonalAccessTokenEntity> tokenCaptor;

  private static CreateTokenRequestDto request(
      String name, Integer expiresInDays, VaultPermission permission) {
    CreateTokenRequestDto dto = new CreateTokenRequestDto();
    dto.setName(name);
    dto.setExpiresInDays(expiresInDays);
    dto.setVaultPermission(permission);
    return dto;
  }

  private static PersonalAccessTokenEntity token(
      Integer id, Integer userId, OffsetDateTime expiresAt) {
    return PersonalAccessTokenEntity.builder().id(id).userId(userId).expiresAt(expiresAt).build();
  }

  // =====================================================================
  // createToken
  // =====================================================================

  @Nested
  @DisplayName("createToken")
  class CreateTokenTests {

    @Test
    @DisplayName("should set an expiry when expiresInDays is positive")
    void shouldSetExpiry_whenExpiresInDaysPositive() {
      // Given
      when(tokenRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

      // When
      CreateTokenResponseDto response =
          personalAccessTokenService.createToken(USER_ID, request("My Token", 30, null));

      // Then
      verify(tokenRepository).save(tokenCaptor.capture());
      assertThat(tokenCaptor.getValue().getExpiresAt()).isNotNull();
      assertThat(response.getToken()).startsWith("sk_");
    }

    @Test
    @DisplayName("should not set an expiry when expiresInDays is null")
    void shouldNotSetExpiry_whenExpiresInDaysNull() {
      // Given
      when(tokenRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

      // When
      personalAccessTokenService.createToken(USER_ID, request("My Token", null, null));

      // Then
      verify(tokenRepository).save(tokenCaptor.capture());
      assertThat(tokenCaptor.getValue().getExpiresAt()).isNull();
    }

    @Test
    @DisplayName("should not set an expiry when expiresInDays is zero or negative")
    void shouldNotSetExpiry_whenExpiresInDaysNonPositive() {
      // Given
      when(tokenRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

      // When
      personalAccessTokenService.createToken(USER_ID, request("My Token", 0, null));

      // Then
      verify(tokenRepository).save(tokenCaptor.capture());
      assertThat(tokenCaptor.getValue().getExpiresAt()).isNull();
    }

    @Test
    @DisplayName("should use the requested vault permission when provided")
    void shouldUseProvidedPermission_whenSet() {
      // Given
      when(tokenRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

      // When
      personalAccessTokenService.createToken(
          USER_ID, request("My Token", null, VaultPermission.BIDIRECTIONAL));

      // Then
      verify(tokenRepository).save(tokenCaptor.capture());
      assertThat(tokenCaptor.getValue().getVaultPermission())
          .isEqualTo(VaultPermission.BIDIRECTIONAL);
    }

    @Test
    @DisplayName("should default to WRITE_ONLY when no vault permission is provided")
    void shouldDefaultToWriteOnly_whenPermissionNotSet() {
      // Given
      when(tokenRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

      // When
      personalAccessTokenService.createToken(USER_ID, request("My Token", null, null));

      // Then
      verify(tokenRepository).save(tokenCaptor.capture());
      assertThat(tokenCaptor.getValue().getVaultPermission()).isEqualTo(VaultPermission.WRITE_ONLY);
    }
  }

  // =====================================================================
  // validateToken
  // =====================================================================

  @Nested
  @DisplayName("validateToken")
  class ValidateTokenTests {

    @Test
    @DisplayName("should return the owning user's id for a valid token")
    void shouldReturnUserId_whenTokenValid() {
      // Given
      PersonalAccessTokenEntity entity = token(1, USER_ID, null);
      when(tokenRepository.findByTokenHash(any())).thenReturn(Optional.of(entity));

      // When / Then
      assertThat(personalAccessTokenService.validateToken("raw-token")).isEqualTo(USER_ID);
    }
  }

  // =====================================================================
  // validateTokenAndGetEntity
  // =====================================================================

  @Nested
  @DisplayName("validateTokenAndGetEntity")
  class ValidateTokenAndGetEntityTests {

    @Test
    @DisplayName("should reject an unknown token")
    void shouldThrowNotFoundException_whenTokenDoesNotExist() {
      // Given
      when(tokenRepository.findByTokenHash(any())).thenReturn(Optional.empty());

      // When / Then
      assertThatThrownBy(() -> personalAccessTokenService.validateTokenAndGetEntity("raw-token"))
          .isInstanceOf(NotFoundException.class)
          .hasMessageContaining("Invalid token");
    }

    @Test
    @DisplayName("should reject an expired token")
    void shouldThrowNotFoundException_whenTokenExpired() {
      // Given
      PersonalAccessTokenEntity entity = token(1, USER_ID, OffsetDateTime.now().minusDays(1));
      when(tokenRepository.findByTokenHash(any())).thenReturn(Optional.of(entity));

      // When / Then
      assertThatThrownBy(() -> personalAccessTokenService.validateTokenAndGetEntity("raw-token"))
          .isInstanceOf(NotFoundException.class)
          .hasMessageContaining("expired");
      verify(tokenRepository, never()).save(any());
    }

    @Test
    @DisplayName("should accept and refresh lastUsedAt for a token with no expiry")
    void shouldReturnEntity_whenTokenHasNoExpiry() {
      // Given
      PersonalAccessTokenEntity entity = token(1, USER_ID, null);
      when(tokenRepository.findByTokenHash(any())).thenReturn(Optional.of(entity));

      // When
      PersonalAccessTokenEntity result =
          personalAccessTokenService.validateTokenAndGetEntity("raw-token");

      // Then
      assertThat(result.getLastUsedAt()).isNotNull();
      verify(tokenRepository).save(entity);
    }

    @Test
    @DisplayName("should accept a token that has not yet expired")
    void shouldReturnEntity_whenTokenNotYetExpired() {
      // Given
      PersonalAccessTokenEntity entity = token(1, USER_ID, OffsetDateTime.now().plusDays(1));
      when(tokenRepository.findByTokenHash(any())).thenReturn(Optional.of(entity));

      // When
      PersonalAccessTokenEntity result =
          personalAccessTokenService.validateTokenAndGetEntity("raw-token");

      // Then
      assertThat(result.getLastUsedAt()).isNotNull();
    }
  }

  // =====================================================================
  // listTokens
  // =====================================================================

  @Nested
  @DisplayName("listTokens")
  class ListTokensTests {

    @Test
    @DisplayName("should return every token owned by the user, mapped to a response DTO")
    void shouldReturnTokensForUser() {
      // Given
      PersonalAccessTokenEntity entity =
          PersonalAccessTokenEntity.builder()
              .id(1)
              .userId(USER_ID)
              .tokenHash("super-secret-hash")
              .name("My Token")
              .vaultPermission(VaultPermission.WRITE_ONLY)
              .build();
      when(tokenRepository.findByUserId(USER_ID)).thenReturn(List.of(entity));

      // When
      List<PersonalAccessTokenResponseDto> result = personalAccessTokenService.listTokens(USER_ID);

      // Then — PersonalAccessTokenResponseDto has no tokenHash field at all, so the hash
      // cannot leak into the API response regardless of what the entity holds.
      assertThat(result).hasSize(1);
      assertThat(result.get(0).getId()).isEqualTo(1);
      assertThat(result.get(0).getName()).isEqualTo("My Token");
      assertThat(result.get(0).getVaultPermission()).isEqualTo(VaultPermission.WRITE_ONLY);
    }
  }

  // =====================================================================
  // revokeToken
  // =====================================================================

  @Nested
  @DisplayName("revokeToken")
  class RevokeTokenTests {

    @Test
    @DisplayName("should reject when the token does not exist")
    void shouldThrowNotFoundException_whenTokenDoesNotExist() {
      // Given
      when(tokenRepository.findById(9)).thenReturn(Optional.empty());

      // When / Then
      assertThatThrownBy(() -> personalAccessTokenService.revokeToken(USER_ID, 9))
          .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("should reject when the token belongs to another user")
    void shouldThrowNotFoundException_whenTokenBelongsToAnotherUser() {
      // Given
      when(tokenRepository.findById(9)).thenReturn(Optional.of(token(9, 999, null)));

      // When / Then
      assertThatThrownBy(() -> personalAccessTokenService.revokeToken(USER_ID, 9))
          .isInstanceOf(NotFoundException.class);
      verify(tokenRepository, never()).delete(any());
    }

    @Test
    @DisplayName("should delete the token when owned by the caller")
    void shouldDeleteToken_whenOwnedByUser() {
      // Given
      PersonalAccessTokenEntity entity = token(9, USER_ID, null);
      when(tokenRepository.findById(9)).thenReturn(Optional.of(entity));

      // When
      personalAccessTokenService.revokeToken(USER_ID, 9);

      // Then
      verify(tokenRepository).delete(entity);
    }
  }
}
