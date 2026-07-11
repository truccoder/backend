package com.socialapp.security.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.socialapp.knowledge.entity.UserProfessionalProfileEntity;
import com.socialapp.knowledge.repository.UserProfessionalProfileRepository;
import com.socialapp.security.config.JwtProperties;
import com.socialapp.security.config.JwtProvider;
import com.socialapp.security.dto.AuthResponseDto;
import com.socialapp.security.entity.RefreshToken;
import com.socialapp.security.entity.UserEntity;
import com.socialapp.security.repository.RefreshTokenRepository;

/**
 * Component (unit) tests for {@link TokenService}, per ISTQB CTFL v4.0.1 (Section 2.2.1 component
 * testing, Section 5.1.6 test pyramid, Section 2.1.3 BDD Given/When/Then) — see {@code
 * PostServiceTest} for the full rationale. {@code JwtProperties} is a plain settings holder (no
 * behavior beyond deriving a {@code Duration} from its own fields), so a real instance is used
 * instead of a mock.
 */
@ExtendWith(MockitoExtension.class)
class TokenServiceTest {

  private static final Integer USER_ID = 1;

  @Mock private JwtProvider jwtProvider;
  @Mock private RefreshTokenRepository refreshTokenRepository;
  @Mock private UserProfessionalProfileRepository professionalProfileRepository;

  private JwtProperties jwtProperties;
  private TokenService tokenService;

  @org.junit.jupiter.api.BeforeEach
  void setUp() {
    jwtProperties = new JwtProperties();
    tokenService =
        new TokenService(
            jwtProvider, jwtProperties, refreshTokenRepository, professionalProfileRepository);
  }

  private static UserEntity user(Integer id, String email) {
    UserEntity user = new UserEntity();
    user.setId(id);
    user.setEmail(email);
    return user;
  }

  // =====================================================================
  // issueTokens
  // =====================================================================

  @Nested
  @DisplayName("issueTokens")
  class IssueTokensTests {

    @Test
    @DisplayName(
        "should include the job title in the access token when a professional profile exists")
    void shouldIssueTokens_withJobTitle_whenProfileExists() {
      // Given
      UserEntity user = user(USER_ID, "user@example.com");
      UserProfessionalProfileEntity profile = new UserProfessionalProfileEntity();
      profile.setUserId(USER_ID);
      profile.setJobTitle("Backend Engineer");
      when(professionalProfileRepository.findById(USER_ID)).thenReturn(Optional.of(profile));
      when(jwtProvider.generateAccessToken("user@example.com", "Backend Engineer"))
          .thenReturn("access-token");
      when(refreshTokenRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

      // When
      AuthResponseDto result = tokenService.issueTokens(user);

      // Then
      assertThat(result.accessToken()).isEqualTo("access-token");
      assertThat(result.tokenType()).isEqualTo("Bearer");
      assertThat(result.expiresIn()).isEqualTo(jwtProperties.getAccessTokenExpirationMs() / 1000);
      assertThat(result.refreshToken()).isNotBlank();
    }

    @Test
    @DisplayName("should issue a token with a null job title when no professional profile exists")
    void shouldIssueTokens_withNullJobTitle_whenProfileDoesNotExist() {
      // Given
      UserEntity user = user(USER_ID, "user@example.com");
      when(professionalProfileRepository.findById(USER_ID)).thenReturn(Optional.empty());
      when(jwtProvider.generateAccessToken("user@example.com", null)).thenReturn("access-token");
      when(refreshTokenRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

      // When
      AuthResponseDto result = tokenService.issueTokens(user);

      // Then
      assertThat(result.accessToken()).isEqualTo("access-token");
    }

    @Test
    @DisplayName("should persist a refresh token with an expiry derived from the configured TTL")
    void shouldPersistRefreshTokenWithConfiguredTtl() {
      // Given
      UserEntity user = user(USER_ID, "user@example.com");
      when(professionalProfileRepository.findById(USER_ID)).thenReturn(Optional.empty());
      when(refreshTokenRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
      OffsetDateTime before =
          OffsetDateTime.now().plus(jwtProperties.refreshTokenTtl()).minusSeconds(5);

      // When
      tokenService.issueTokens(user);

      // Then
      org.mockito.ArgumentCaptor<RefreshToken> captor =
          org.mockito.ArgumentCaptor.forClass(RefreshToken.class);
      org.mockito.Mockito.verify(refreshTokenRepository).save(captor.capture());
      assertThat(captor.getValue().getUserId()).isEqualTo(USER_ID);
      assertThat(captor.getValue().getExpiresAt()).isAfter(before);
    }
  }

  // =====================================================================
  // isRefreshTokenExpired
  // =====================================================================

  @Nested
  @DisplayName("isRefreshTokenExpired")
  class IsRefreshTokenExpiredTests {

    @Test
    @DisplayName("should return true when the refresh token's expiry is in the past")
    void shouldReturnTrue_whenExpired() {
      // Given
      RefreshToken token = new RefreshToken("tok", USER_ID, OffsetDateTime.now().minusMinutes(1));

      // When / Then
      assertThat(tokenService.isRefreshTokenExpired(token)).isTrue();
    }

    @Test
    @DisplayName("should return false when the refresh token's expiry is in the future")
    void shouldReturnFalse_whenNotExpired() {
      // Given
      RefreshToken token = new RefreshToken("tok", USER_ID, OffsetDateTime.now().plusMinutes(1));

      // When / Then
      assertThat(tokenService.isRefreshTokenExpired(token)).isFalse();
    }
  }
}
