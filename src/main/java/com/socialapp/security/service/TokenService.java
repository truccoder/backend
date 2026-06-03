package com.socialapp.security.service;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.socialapp.security.config.JwtProperties;
import com.socialapp.security.config.JwtProvider;
import com.socialapp.security.dto.AuthResponse;
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

  public AuthResponse issueTokens(UserEntity user) {
    String accessToken = jwtProvider.generateAccessToken(user.getEmail());
    RefreshToken refreshToken = persistRefreshToken(user.getId());
    return toAuthResponse(accessToken, refreshToken.getToken());
  }

  public boolean isRefreshTokenExpired(RefreshToken refreshToken) {
    return refreshToken.getExpiresAt().isBefore(OffsetDateTime.now());
  }

  private RefreshToken persistRefreshToken(Integer userId) {
    RefreshToken refreshToken = new RefreshToken();
    refreshToken.setToken(UUID.randomUUID().toString());
    refreshToken.setUserId(userId);
    refreshToken.setExpiresAt(OffsetDateTime.now().plus(jwtProperties.refreshTokenTtl()));
    return refreshTokenRepository.save(refreshToken);
  }

  private AuthResponse toAuthResponse(String accessToken, String refreshToken) {
    return new AuthResponse(
        accessToken, refreshToken, TOKEN_TYPE, jwtProperties.getAccessTokenExpirationMs() / 1000);
  }
}
