package com.socialapp.security.service;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.socialapp.common.utils.TokenHasher;
import com.socialapp.knowledge.entity.UserProfessionalProfileEntity;
import com.socialapp.knowledge.repository.UserProfessionalProfileRepository;
import com.socialapp.security.config.JwtProperties;
import com.socialapp.security.config.JwtProvider;
import com.socialapp.security.dto.AuthResponseDto;
import com.socialapp.security.entity.RefreshToken;
import com.socialapp.security.entity.UserEntity;
import com.socialapp.security.repository.RefreshTokenRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class TokenService {

  private static final String TOKEN_TYPE = "Bearer";

  private final JwtProvider jwtProvider;
  private final JwtProperties jwtProperties;
  private final RefreshTokenRepository refreshTokenRepository;
  private final UserProfessionalProfileRepository professionalProfileRepository;

  public AuthResponseDto issueTokens(UserEntity user) {
    return issueTokens(user, false, false);
  }

  public AuthResponseDto issueTokens(UserEntity user, boolean isAutoLinked, boolean isNewUser) {
    String jobTitle =
        professionalProfileRepository
            .findById(user.getId())
            .map(UserProfessionalProfileEntity::getJobTitle)
            .orElse(null);
    String accessToken = jwtProvider.generateAccessToken(user.getEmail(), jobTitle);
    String refreshToken = persistRefreshToken(user.getId());
    return new AuthResponseDto(
        accessToken,
        refreshToken,
        TOKEN_TYPE,
        jwtProperties.getAccessTokenExpirationMs() / 1000,
        isAutoLinked,
        isNewUser);
  }

  public boolean isRefreshTokenExpired(RefreshToken refreshToken) {
    return refreshToken.getExpiresAt().isBefore(OffsetDateTime.now());
  }

  /**
   * Issues a refresh token, storing only its hash, and returns the value the client must keep.
   *
   * <p>The row's primary key is {@code SHA-256(token)}, so the database never holds anything that
   * can be replayed — see {@link TokenHasher}. This is the only moment the raw value exists on the
   * server, which is why it is returned rather than read back off the entity.
   */
  private String persistRefreshToken(Integer userId) {
    String rawToken = UUID.randomUUID().toString();

    RefreshToken refreshToken = new RefreshToken();
    refreshToken.setToken(TokenHasher.hash(rawToken));
    refreshToken.setUserId(userId);
    refreshToken.setExpiresAt(OffsetDateTime.now().plus(jwtProperties.refreshTokenTtl()));
    refreshTokenRepository.save(refreshToken);

    return rawToken;
  }
}
