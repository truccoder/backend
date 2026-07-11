package com.socialapp.security.service;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;

import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.socialapp.common.exception.ValidationException;
import com.socialapp.notifications.services.MailService;
import com.socialapp.security.dto.*;
import com.socialapp.security.entity.EmailVerificationToken;
import com.socialapp.security.entity.MagicLinkToken;
import com.socialapp.security.entity.PasswordResetToken;
import com.socialapp.security.entity.RefreshToken;
import com.socialapp.security.entity.UserEntity;
import com.socialapp.security.exception.AccountBannedException;
import com.socialapp.security.repository.EmailVerificationTokenRepository;
import com.socialapp.security.repository.MagicLinkTokenRepository;
import com.socialapp.security.repository.PasswordResetTokenRepository;
import com.socialapp.security.repository.RefreshTokenRepository;
import com.socialapp.security.repository.UserRepository;
import com.socialapp.security.util.EmailNormalizer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

  private static final String INVALID_CREDENTIALS = "Invalid credentials";
  private static final long RESET_TOKEN_EXPIRATION_HOURS = 1;
  private static final long VERIFICATION_TOKEN_EXPIRATION_HOURS = 24;
  private static final long MAGIC_LINK_EXPIRATION_MINUTES = 15;
  private static final SecureRandom SECURE_RANDOM = new SecureRandom();

  private final UserRepository userRepository;
  private final PasswordEncoder passwordEncoder;
  private final TokenService tokenService;
  private final RefreshTokenRepository refreshTokenRepository;
  private final PasswordResetTokenRepository passwordResetTokenRepository;
  private final EmailVerificationTokenRepository emailVerificationTokenRepository;
  private final MagicLinkTokenRepository magicLinkTokenRepository;
  private final MailService mailService;
  private final ProfileService profileService;

  @Transactional
  public void register(RegisterRequestDto request, MultipartFile profilePicture) {
    String email = EmailNormalizer.normalize(request.email());
    if (userRepository.existsByEmailIgnoreCase(email)) {
      throw new ValidationException("Email already exists");
    }

    UserEntity user = new UserEntity();
    user.setEmail(email);
    user.setPassword(passwordEncoder.encode(request.password()));
    user.setFullName(request.fullname());

    userRepository.save(user);

    // Upload the picture (if any) BEFORE sending the verification email. Both run inside this
    // same @Transactional method, but the email is an external side effect that @Transactional
    // rollback cannot undo — if changeProfilePicture() fails (bad file/upload error) after the
    // email had already gone out, the recipient would hold a verification link pointing at a
    // token that a rollback just deleted. Failing the upload first means the email is only ever
    // sent once we know the rest of registration actually succeeded.
    if (profilePicture != null && !profilePicture.isEmpty()) {
      profileService.changeProfilePicture(user.getId(), profilePicture);
    }

    createVerificationTokenAndSendEmail(user);
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

    if (user.isBanned()) {
      refreshTokenRepository.delete(stored);
      throw new AccountBannedException(user.getBannedUntil());
    }

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

    if (!user.isEmailVerified()) {
      throw new ValidationException("Please verify your email before resetting your password");
    }

    if (passwordEncoder.matches(request.newPassword(), user.getPassword())) {
      throw new ValidationException("New password must be different from the current password");
    }

    user.setPassword(passwordEncoder.encode(request.newPassword()));
    userRepository.save(user);
    passwordResetTokenRepository.delete(resetToken);
  }

  @Transactional
  public void verifyEmail(VerifyEmailRequestDto request) {
    EmailVerificationToken verificationToken =
        emailVerificationTokenRepository
            .findById(request.token())
            .orElseThrow(() -> new BadCredentialsException(INVALID_CREDENTIALS));

    if (verificationToken.getExpiresAt() == null
        || verificationToken.getExpiresAt().isBefore(OffsetDateTime.now())) {
      emailVerificationTokenRepository.delete(verificationToken);
      throw new BadCredentialsException(INVALID_CREDENTIALS);
    }

    UserEntity user =
        userRepository
            .findById(verificationToken.getUserId())
            .orElseThrow(() -> new BadCredentialsException(INVALID_CREDENTIALS));

    user.setEmailVerified(true);
    userRepository.save(user);
    emailVerificationTokenRepository.delete(verificationToken);
  }

  @Transactional
  public void requestMagicLink(MagicLinkRequestDto request) {
    userRepository
        .findByEmailIgnoreCase(EmailNormalizer.normalize(request.email()))
        .ifPresent(this::createMagicLinkTokenAndSendEmail);
  }

  @Transactional
  public AuthResponseDto loginWithMagicLink(MagicLinkLoginRequestDto request) {
    MagicLinkToken magicLinkToken =
        magicLinkTokenRepository
            .findById(request.token())
            .orElseThrow(() -> new BadCredentialsException(INVALID_CREDENTIALS));

    magicLinkTokenRepository.delete(magicLinkToken);

    if (magicLinkToken.getExpiresAt() == null
        || magicLinkToken.getExpiresAt().isBefore(OffsetDateTime.now())) {
      throw new BadCredentialsException(INVALID_CREDENTIALS);
    }

    UserEntity user =
        userRepository
            .findById(magicLinkToken.getUserId())
            .orElseThrow(() -> new BadCredentialsException(INVALID_CREDENTIALS));

    if (user.isBanned()) {
      throw new AccountBannedException(user.getBannedUntil());
    }

    return tokenService.issueTokens(user);
  }

  @Transactional
  public void logout(RefreshTokenRequestDto request) {
    refreshTokenRepository
        .findById(request.refreshToken())
        .ifPresent(refreshTokenRepository::delete);
  }

  private void createPasswordResetTokenAndSendEmail(UserEntity user) {
    passwordResetTokenRepository.deleteByUserId(user.getId());

    PasswordResetToken resetToken = new PasswordResetToken();
    resetToken.setToken(generateSecureToken());
    resetToken.setUserId(user.getId());
    resetToken.setExpiresAt(OffsetDateTime.now().plusHours(RESET_TOKEN_EXPIRATION_HOURS));
    passwordResetTokenRepository.save(resetToken);

    mailService.sendPasswordResetEmail(
        user.getEmail(), getRecipientName(user), resetToken.getToken());
  }

  private void createVerificationTokenAndSendEmail(UserEntity user) {
    emailVerificationTokenRepository.deleteByUserId(user.getId());

    EmailVerificationToken verificationToken = new EmailVerificationToken();
    verificationToken.setToken(generateSecureToken());
    verificationToken.setUserId(user.getId());
    verificationToken.setExpiresAt(
        OffsetDateTime.now().plusHours(VERIFICATION_TOKEN_EXPIRATION_HOURS));
    emailVerificationTokenRepository.save(verificationToken);

    mailService.sendVerificationEmail(
        user.getEmail(), getRecipientName(user), verificationToken.getToken());
  }

  private void createMagicLinkTokenAndSendEmail(UserEntity user) {
    magicLinkTokenRepository.deleteByUserId(user.getId());

    MagicLinkToken magicLinkToken = new MagicLinkToken();
    magicLinkToken.setToken(generateSecureToken());
    magicLinkToken.setUserId(user.getId());
    magicLinkToken.setExpiresAt(OffsetDateTime.now().plusMinutes(MAGIC_LINK_EXPIRATION_MINUTES));
    magicLinkTokenRepository.save(magicLinkToken);

    mailService.sendMagicLinkEmail(
        user.getEmail(), getRecipientName(user), magicLinkToken.getToken());
  }

  private String getRecipientName(UserEntity user) {
    if (user.getFullName() == null || user.getFullName().isBlank()) {
      return user.getEmail();
    }
    return user.getFullName();
  }

  private String generateSecureToken() {
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

    // Consistent with resetPassword(): password-based login requires a verified email.
    // Magic-link login is intentionally exempt since receiving the link already proves
    // ownership of the mailbox.
    if (!user.isEmailVerified()) {
      throw new ValidationException("Please verify your email before logging in");
    }

    if (user.isBanned()) {
      throw new AccountBannedException(user.getBannedUntil());
    }

    return user;
  }
}
