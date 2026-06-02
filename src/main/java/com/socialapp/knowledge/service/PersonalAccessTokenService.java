package com.socialapp.knowledge.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialapp.common.exception.NotFoundException;
import com.socialapp.knowledge.dto.CreateTokenRequestDto;
import com.socialapp.knowledge.dto.CreateTokenResponseDto;
import com.socialapp.knowledge.entity.PersonalAccessTokenEntity;
import com.socialapp.knowledge.entity.enums.VaultPermission;
import com.socialapp.knowledge.repository.PersonalAccessTokenRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class PersonalAccessTokenService {
  private final PersonalAccessTokenRepository tokenRepository;

  private static final SecureRandom SECURE_RANDOM = new SecureRandom();

  @Transactional
  public CreateTokenResponseDto createToken(Integer userId, CreateTokenRequestDto request) {
    String rawToken = generateToken();
    String tokenHash = hashToken(rawToken);

    OffsetDateTime expiresAt = null;
    if (Objects.nonNull(request.getExpiresInDays()) && request.getExpiresInDays() > 0) {
      expiresAt = OffsetDateTime.now().plusDays(request.getExpiresInDays());
    }

    VaultPermission permission =
        Objects.nonNull(request.getVaultPermission())
            ? request.getVaultPermission()
            : VaultPermission.WRITE_ONLY;

    PersonalAccessTokenEntity entity =
        PersonalAccessTokenEntity.builder()
            .userId(userId)
            .tokenHash(tokenHash)
            .name(request.getName())
            .expiresAt(expiresAt)
            .vaultPermission(permission)
            .build();

    tokenRepository.save(entity);

    return CreateTokenResponseDto.builder()
        .id(entity.getId())
        .token(rawToken)
        .name(entity.getName())
        .expiresAt(entity.getExpiresAt())
        .build();
  }

  public Integer validateToken(String rawToken) {
    return validateTokenAndGetEntity(rawToken).getUserId();
  }

  public PersonalAccessTokenEntity validateTokenAndGetEntity(String rawToken) {
    String tokenHash = hashToken(rawToken);
    PersonalAccessTokenEntity entity =
        tokenRepository
            .findByTokenHash(tokenHash)
            .orElseThrow(() -> new NotFoundException("Invalid token"));

    if (Objects.nonNull(entity.getExpiresAt())
        && OffsetDateTime.now().isAfter(entity.getExpiresAt())) {
      throw new NotFoundException("Token expired");
    }

    entity.setLastUsedAt(OffsetDateTime.now());
    tokenRepository.save(entity);
    return entity;
  }

  public List<PersonalAccessTokenEntity> listTokens(Integer userId) {
    return tokenRepository.findByUserId(userId);
  }

  @Transactional
  public void revokeToken(Integer userId, Integer tokenId) {
    PersonalAccessTokenEntity token =
        tokenRepository
            .findById(tokenId)
            .orElseThrow(() -> new NotFoundException("Token not found"));

    if (!token.getUserId().equals(userId)) {
      throw new NotFoundException("Token not found");
    }
    tokenRepository.delete(token);
  }

  private String generateToken() {
    byte[] bytes = new byte[32];
    SECURE_RANDOM.nextBytes(bytes);
    return "sk_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private String hashToken(String token) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(token.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash);
    } catch (Exception e) {
      throw new RuntimeException("Failed to hash token", e);
    }
  }
}
