package com.socialapp.security.service;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;

import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialapp.common.exception.ValidationException;
import com.socialapp.notifications.services.MailService;
import com.socialapp.security.config.AuthProperties;
import com.socialapp.security.dto.*;
import com.socialapp.security.entity.PasswordResetToken;
import com.socialapp.security.entity.RefreshToken;
import com.socialapp.security.entity.UserEntity;
import com.socialapp.security.repository.PasswordResetTokenRepository;
import com.socialapp.security.repository.RefreshTokenRepository;
import com.socialapp.security.repository.UserRepository;
import com.socialapp.security.util.EmailNormalizer;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AuthService {

  private static final String INVALID_CREDENTIALS = "Invalid credentials";
  private static final long RESET_TOKEN_EXPIRATION_HOURS = 1;
  private static final SecureRandom SECURE_RANDOM = new SecureRandom();

  private final UserRepository userRepository;
  private final PasswordEncoder passwordEncoder;
  private final TokenService tokenService;
  private final RefreshTokenRepository refreshTokenRepository;
  private final PasswordResetTokenRepository passwordResetTokenRepository;
  private final MailService mailService;
  private final AuthProperties authProperties;

  @Transactional
  public void register(RegisterRequestDto request) {
    String email = EmailNormalizer.normalize(request.email());
    if (userRepository.existsByEmailIgnoreCase(email)) {
      throw new ValidationException("Email already exists");
    }

    UserEntity user = new UserEntity();
    user.setEmail(email);
    user.setPassword(passwordEncoder.encode(request.password()));
    user.setFullName(request.fullname());
    user.setProfilePictureUrl(request.profilePictureUrl());

    userRepository.save(user);
  }

  @Transactional
  public AuthResponseDto login(LoginRequestDto request) {
    UserEntity user = authenticate(request.email(), request.password());
    return tokenService.issueTokens(user);
  }

  @Transactional
  public AuthResponseDto refresh(RefreshTokenRequestDto request) {
    RefreshToken stored =
        refreshTokenRepository
            .findById(request.refreshToken())
            .orElseThrow(() -> new BadCredentialsException(INVALID_CREDENTIALS));

    if (tokenService.isRefreshTokenExpired(stored)) {
      refreshTokenRepository.delete(stored);
      throw new BadCredentialsException(INVALID_CREDENTIALS);
    }

    UserEntity user =
        userRepository
            .findById(stored.getUserId())
            .orElseThrow(() -> new BadCredentialsException(INVALID_CREDENTIALS));

    refreshTokenRepository.delete(stored);
    return tokenService.issueTokens(user);
  }

  @Transactional
  public void forgotPassword(ForgotPasswordRequestDto request) {
    userRepository
        .findByEmailIgnoreCase(EmailNormalizer.normalize(request.email()))
        .ifPresent(this::createPasswordResetTokenAndSendEmail);
  }

  @Transactional
  public void resetPassword(ResetPasswordRequestDto request) {
    PasswordResetToken resetToken =
        passwordResetTokenRepository
            .findById(request.token())
            .orElseThrow(() -> new BadCredentialsException(INVALID_CREDENTIALS));

    if (resetToken.getExpiresAt() == null
        || resetToken.getExpiresAt().isBefore(OffsetDateTime.now())) {
      passwordResetTokenRepository.delete(resetToken);
      throw new BadCredentialsException(INVALID_CREDENTIALS);
    }

    UserEntity user =
        userRepository
            .findById(resetToken.getUserId())
            .orElseThrow(() -> new BadCredentialsException(INVALID_CREDENTIALS));

    user.setPassword(passwordEncoder.encode(request.newPassword()));
    userRepository.save(user);
    passwordResetTokenRepository.delete(resetToken);
  }

  private void createPasswordResetTokenAndSendEmail(UserEntity user) {
    passwordResetTokenRepository.deleteByUserId(user.getId());

    PasswordResetToken resetToken = new PasswordResetToken();
    resetToken.setToken(generateResetToken());
    resetToken.setUserId(user.getId());
    resetToken.setExpiresAt(OffsetDateTime.now().plusHours(RESET_TOKEN_EXPIRATION_HOURS));
    passwordResetTokenRepository.save(resetToken);

    mailService.sendPasswordResetEmail(
        user.getEmail(), getRecipientName(user), resetToken.getToken());
  }

  private String getRecipientName(UserEntity user) {
    if (user.getFullName() == null || user.getFullName().isBlank()) {
      return user.getEmail();
    }
    return user.getFullName();
  }

  private String generateResetToken() {
    byte[] randomBytes = new byte[32];
    SECURE_RANDOM.nextBytes(randomBytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
  }

  private UserEntity authenticate(String email, String password) {
    UserEntity user =
        userRepository
            .findByEmailIgnoreCase(EmailNormalizer.normalize(email))
            .orElseThrow(() -> new BadCredentialsException(INVALID_CREDENTIALS));

    if (!passwordEncoder.matches(password, user.getPassword())) {
      throw new BadCredentialsException(INVALID_CREDENTIALS);
    }

    return user;
  }
}
