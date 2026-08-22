package com.socialapp.security.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.multipart.MultipartFile;

import com.socialapp.common.exception.ValidationException;
import com.socialapp.common.ratelimit.AuthRateLimitProperties;
import com.socialapp.common.ratelimit.FixedWindowRateLimiter;
import com.socialapp.moderation.service.BanDetailsService;
import com.socialapp.notifications.services.MailService;
import com.socialapp.security.dto.AuthResponseDto;
import com.socialapp.security.dto.ForgotPasswordRequestDto;
import com.socialapp.security.dto.LoginRequestDto;
import com.socialapp.security.dto.MagicLinkLoginRequestDto;
import com.socialapp.security.dto.MagicLinkRequestDto;
import com.socialapp.security.dto.RefreshTokenRequestDto;
import com.socialapp.security.dto.RegisterRequestDto;
import com.socialapp.security.dto.ResetPasswordRequestDto;
import com.socialapp.security.dto.VerifyEmailRequestDto;
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

/**
 * Component (unit) tests for {@link AuthService}, per ISTQB CTFL v4.0.1 (Section 2.2.1 component
 * testing, Section 5.1.6 test pyramid, Section 4.3.2 branch testing, Section 2.1.3 BDD
 * Given/When/Then) — see {@code PostServiceTest} for the full rationale.
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

  private static final Integer USER_ID = 1;
  private static final String EMAIL = "user@example.com";
  private static final String RAW_PASSWORD = "password123";
  private static final String ENCODED_PASSWORD = "{bcrypt}encoded";

  @Mock private UserRepository userRepository;

  @Mock private BanDetailsService banDetailsService;
  @Mock private PasswordEncoder passwordEncoder;
  @Mock private TokenService tokenService;
  @Mock private RefreshTokenRepository refreshTokenRepository;
  @Mock private PasswordResetTokenRepository passwordResetTokenRepository;
  @Mock private EmailVerificationTokenRepository emailVerificationTokenRepository;
  @Mock private MagicLinkTokenRepository magicLinkTokenRepository;
  @Mock private MailService mailService;
  @Mock private ProfileService profileService;

  // Mockito returns false from isOverLimit by default, i.e. "budget available", so every existing
  // test keeps its original behaviour without having to stub this.
  @Mock private FixedWindowRateLimiter rateLimiter;

  @Spy private AuthRateLimitProperties rateLimitProperties = new AuthRateLimitProperties();

  @InjectMocks private AuthService authService;

  @Captor private ArgumentCaptor<UserEntity> userCaptor;

  private static UserEntity verifiedUser(Integer id, String email, boolean banned) {
    UserEntity user = new UserEntity();
    user.setId(id);
    user.setEmail(email);
    user.setPassword(ENCODED_PASSWORD);
    user.setFullName("Jane Doe");
    user.setEmailVerified(true);
    if (banned) {
      user.setBannedUntil(OffsetDateTime.now().plusDays(1));
    }
    return user;
  }

  // =====================================================================
  // register
  // =====================================================================

  @Nested
  @DisplayName("register")
  class RegisterTests {

    @Test
    @DisplayName("should reject when the email is already registered")
    void shouldThrowValidationException_whenEmailAlreadyExists() {
      // Given
      RegisterRequestDto request = new RegisterRequestDto(EMAIL, RAW_PASSWORD, "Jane Doe", null);
      when(userRepository.existsByEmailIgnoreCase(EMAIL)).thenReturn(true);

      // When / Then
      assertThatThrownBy(() -> authService.register(request, null))
          .isInstanceOf(ValidationException.class)
          .hasMessageContaining("Email already exists");
      verify(userRepository, never()).save(any());
      verifyNoInteractions(mailService, profileService);
    }

    @Test
    @DisplayName("should register without a profile picture when none is provided")
    void shouldRegister_withoutProfilePicture_whenNull() {
      // Given
      RegisterRequestDto request =
          new RegisterRequestDto(" User@Example.com ", RAW_PASSWORD, "Jane Doe", null);
      when(userRepository.existsByEmailIgnoreCase(EMAIL)).thenReturn(false);
      when(passwordEncoder.encode(RAW_PASSWORD)).thenReturn(ENCODED_PASSWORD);

      // When
      authService.register(request, null);

      // Then
      verify(userRepository).save(userCaptor.capture());
      assertThat(userCaptor.getValue().getEmail()).isEqualTo(EMAIL);
      assertThat(userCaptor.getValue().getPassword()).isEqualTo(ENCODED_PASSWORD);
      verifyNoInteractions(profileService);
      verify(emailVerificationTokenRepository).save(any());
      verify(mailService).sendVerificationEmail(eq(EMAIL), eq("Jane Doe"), any());
    }

    @Test
    @DisplayName("should skip the profile picture upload when the file is empty")
    void shouldRegister_withoutProfilePicture_whenEmpty() {
      // Given
      RegisterRequestDto request = new RegisterRequestDto(EMAIL, RAW_PASSWORD, "Jane Doe", null);
      MultipartFile picture = mock(MultipartFile.class);
      when(picture.isEmpty()).thenReturn(true);
      when(userRepository.existsByEmailIgnoreCase(EMAIL)).thenReturn(false);
      when(passwordEncoder.encode(RAW_PASSWORD)).thenReturn(ENCODED_PASSWORD);

      // When
      authService.register(request, picture);

      // Then
      verifyNoInteractions(profileService);
    }

    @Test
    @DisplayName("should upload the profile picture before sending the verification email")
    void shouldRegister_withProfilePicture_whenPresentAndNotEmpty() {
      // Given
      RegisterRequestDto request = new RegisterRequestDto(EMAIL, RAW_PASSWORD, "Jane Doe", null);
      MultipartFile picture = mock(MultipartFile.class);
      when(picture.isEmpty()).thenReturn(false);
      when(userRepository.existsByEmailIgnoreCase(EMAIL)).thenReturn(false);
      when(passwordEncoder.encode(RAW_PASSWORD)).thenReturn(ENCODED_PASSWORD);

      // When
      authService.register(request, picture);

      // Then
      verify(profileService).changeProfilePicture(any(), eq(picture));
    }

    @Test
    @DisplayName("should use the email as the recipient name when the full name is blank")
    void shouldUseEmailAsRecipientName_whenFullNameIsBlank() {
      // Given
      RegisterRequestDto request = new RegisterRequestDto(EMAIL, RAW_PASSWORD, "   ", null);
      when(userRepository.existsByEmailIgnoreCase(EMAIL)).thenReturn(false);
      when(passwordEncoder.encode(RAW_PASSWORD)).thenReturn(ENCODED_PASSWORD);

      // When
      authService.register(request, null);

      // Then
      verify(mailService).sendVerificationEmail(eq(EMAIL), eq(EMAIL), any());
    }

    @Test
    @DisplayName("should use the email as the recipient name when the full name is null")
    void shouldUseEmailAsRecipientName_whenFullNameIsNull() {
      // Given
      RegisterRequestDto request = new RegisterRequestDto(EMAIL, RAW_PASSWORD, null, null);
      when(userRepository.existsByEmailIgnoreCase(EMAIL)).thenReturn(false);
      when(passwordEncoder.encode(RAW_PASSWORD)).thenReturn(ENCODED_PASSWORD);

      // When
      authService.register(request, null);

      // Then
      verify(mailService).sendVerificationEmail(eq(EMAIL), eq(EMAIL), any());
    }
  }

  // =====================================================================
  // login
  // =====================================================================

  @Nested
  @DisplayName("login")
  class LoginTests {

    @Test
    @DisplayName("should reject when no account has that email")
    void shouldThrowBadCredentials_whenEmailNotFound() {
      // Given
      when(userRepository.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.empty());
      LoginRequestDto request = new LoginRequestDto(EMAIL, RAW_PASSWORD);

      // When / Then
      assertThatThrownBy(() -> authService.login(request))
          .isInstanceOf(BadCredentialsException.class);
      verifyNoInteractions(tokenService);
    }

    @Test
    @DisplayName("should reject when the password does not match")
    void shouldThrowBadCredentials_whenPasswordDoesNotMatch() {
      // Given
      UserEntity user = verifiedUser(USER_ID, EMAIL, false);
      when(userRepository.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(user));
      when(passwordEncoder.matches(RAW_PASSWORD, ENCODED_PASSWORD)).thenReturn(false);
      LoginRequestDto request = new LoginRequestDto(EMAIL, RAW_PASSWORD);

      // When / Then
      assertThatThrownBy(() -> authService.login(request))
          .isInstanceOf(BadCredentialsException.class);
      verifyNoInteractions(tokenService);
    }

    @Test
    @DisplayName("should reject when the email has not been verified")
    void shouldThrowValidationException_whenEmailNotVerified() {
      // Given
      UserEntity user = verifiedUser(USER_ID, EMAIL, false);
      user.setEmailVerified(false);
      when(userRepository.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(user));
      when(passwordEncoder.matches(RAW_PASSWORD, ENCODED_PASSWORD)).thenReturn(true);
      LoginRequestDto request = new LoginRequestDto(EMAIL, RAW_PASSWORD);

      // When / Then
      assertThatThrownBy(() -> authService.login(request))
          .isInstanceOf(ValidationException.class)
          .hasMessageContaining("verify your email");
      verifyNoInteractions(tokenService);
    }

    @Test
    @DisplayName("should reject a banned account")
    void shouldThrowAccountBanned_whenUserIsBanned() {
      // Given
      UserEntity user = verifiedUser(USER_ID, EMAIL, true);
      when(userRepository.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(user));
      when(passwordEncoder.matches(RAW_PASSWORD, ENCODED_PASSWORD)).thenReturn(true);
      LoginRequestDto request = new LoginRequestDto(EMAIL, RAW_PASSWORD);

      // When / Then
      assertThatThrownBy(() -> authService.login(request))
          .isInstanceOf(AccountBannedException.class);
      verifyNoInteractions(tokenService);
    }

    @Test
    @DisplayName("should issue tokens on successful login, normalizing the email first")
    void shouldIssueTokens_whenCredentialsValid() {
      // Given
      UserEntity user = verifiedUser(USER_ID, EMAIL, false);
      when(userRepository.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(user));
      when(passwordEncoder.matches(RAW_PASSWORD, ENCODED_PASSWORD)).thenReturn(true);
      AuthResponseDto expected =
          new AuthResponseDto("access", "refresh", "Bearer", 900, false, false);
      when(tokenService.issueTokens(user)).thenReturn(expected);
      LoginRequestDto request = new LoginRequestDto(" User@Example.com ", RAW_PASSWORD);

      // When
      AuthResponseDto result = authService.login(request);

      // Then
      assertThat(result).isEqualTo(expected);
    }
  }

  // =====================================================================
  // refresh
  // =====================================================================

  @Nested
  @DisplayName("refresh")
  class RefreshTests {

    @Test
    @DisplayName("should reject an unknown refresh token")
    void shouldThrowBadCredentials_whenRefreshTokenNotFound() {
      // Given
      when(refreshTokenRepository.findById("tok")).thenReturn(Optional.empty());

      // When / Then
      assertThatThrownBy(() -> authService.refresh(new RefreshTokenRequestDto("tok")))
          .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    @DisplayName("should delete and reject an expired refresh token")
    void shouldThrowBadCredentials_whenRefreshTokenExpired() {
      // Given
      RefreshToken stored = new RefreshToken("tok", USER_ID, OffsetDateTime.now().minusDays(1));
      when(refreshTokenRepository.findById("tok")).thenReturn(Optional.of(stored));
      when(tokenService.isRefreshTokenExpired(stored)).thenReturn(true);

      // When / Then
      assertThatThrownBy(() -> authService.refresh(new RefreshTokenRequestDto("tok")))
          .isInstanceOf(BadCredentialsException.class);
      verify(refreshTokenRepository).delete(stored);
      verifyNoInteractions(userRepository);
    }

    @Test
    @DisplayName("should reject when the token's owner no longer exists")
    void shouldThrowBadCredentials_whenOwnerNotFound() {
      // Given
      RefreshToken stored = new RefreshToken("tok", USER_ID, OffsetDateTime.now().plusDays(1));
      when(refreshTokenRepository.findById("tok")).thenReturn(Optional.of(stored));
      when(tokenService.isRefreshTokenExpired(stored)).thenReturn(false);
      when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

      // When / Then
      assertThatThrownBy(() -> authService.refresh(new RefreshTokenRequestDto("tok")))
          .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    @DisplayName("should delete the token and reject when the owner is banned")
    void shouldThrowAccountBanned_whenOwnerIsBanned() {
      // Given
      RefreshToken stored = new RefreshToken("tok", USER_ID, OffsetDateTime.now().plusDays(1));
      when(refreshTokenRepository.findById("tok")).thenReturn(Optional.of(stored));
      when(tokenService.isRefreshTokenExpired(stored)).thenReturn(false);
      when(userRepository.findById(USER_ID))
          .thenReturn(Optional.of(verifiedUser(USER_ID, EMAIL, true)));

      // When / Then
      assertThatThrownBy(() -> authService.refresh(new RefreshTokenRequestDto("tok")))
          .isInstanceOf(AccountBannedException.class);
      verify(refreshTokenRepository).delete(stored);
    }

    @Test
    @DisplayName("should rotate the refresh token and issue new tokens")
    void shouldIssueNewTokens_whenRefreshTokenValid() {
      // Given
      RefreshToken stored = new RefreshToken("tok", USER_ID, OffsetDateTime.now().plusDays(1));
      UserEntity user = verifiedUser(USER_ID, EMAIL, false);
      when(refreshTokenRepository.findById("tok")).thenReturn(Optional.of(stored));
      when(tokenService.isRefreshTokenExpired(stored)).thenReturn(false);
      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
      AuthResponseDto expected =
          new AuthResponseDto("access2", "refresh2", "Bearer", 900, false, false);
      when(tokenService.issueTokens(user)).thenReturn(expected);

      // When
      AuthResponseDto result = authService.refresh(new RefreshTokenRequestDto("tok"));

      // Then
      assertThat(result).isEqualTo(expected);
      verify(refreshTokenRepository).delete(stored);
    }
  }

  // =====================================================================
  // forgotPassword
  // =====================================================================

  @Nested
  @DisplayName("forgotPassword")
  class ForgotPasswordTests {

    @Test
    @DisplayName("should do nothing when no account has that email")
    void shouldDoNothing_whenEmailNotFound() {
      // Given
      when(userRepository.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.empty());

      // When
      authService.forgotPassword(new ForgotPasswordRequestDto(EMAIL));

      // Then
      verifyNoInteractions(mailService, passwordResetTokenRepository);
    }

    @Test
    @DisplayName("should issue a reset token and email it when the account exists")
    void shouldSendResetEmail_whenEmailFound() {
      // Given
      UserEntity user = verifiedUser(USER_ID, EMAIL, false);
      when(userRepository.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(user));

      // When
      authService.forgotPassword(new ForgotPasswordRequestDto(EMAIL));

      // Then
      verify(passwordResetTokenRepository).deleteByUserId(USER_ID);
      verify(passwordResetTokenRepository).save(any());
      verify(mailService).sendPasswordResetEmail(eq(EMAIL), eq("Jane Doe"), any());
    }

    @Test
    @DisplayName("should send nothing once the recipient's mail budget is spent")
    void shouldNotSendResetEmail_whenRecipientMailBudgetExhausted() {
      // Given an account that exists, but whose address has already received its allowance
      UserEntity user = verifiedUser(USER_ID, EMAIL, false);
      when(userRepository.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(user));
      when(rateLimiter.isOverLimit(anyString(), anyInt(), any())).thenReturn(true);

      // When
      authService.forgotPassword(new ForgotPasswordRequestDto(EMAIL));

      // Then no mail goes out, and no token is minted for one that will never arrive
      verifyNoInteractions(mailService);
      verify(passwordResetTokenRepository, never()).save(any());
    }

    @Test
    @DisplayName("should stay silent rather than raise, so the throttle is not an existence oracle")
    void shouldNotThrow_whenMailBudgetExhausted() {
      // Given
      UserEntity user = verifiedUser(USER_ID, EMAIL, false);
      when(userRepository.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(user));
      when(rateLimiter.isOverLimit(anyString(), anyInt(), any())).thenReturn(true);

      // When / Then — a 429 raised only once a real account is found would let an attacker read
      // account existence off the status code, defeating the point of the silent ifPresent above.
      assertThatCode(() -> authService.forgotPassword(new ForgotPasswordRequestDto(EMAIL)))
          .doesNotThrowAnyException();
    }
  }

  // =====================================================================
  // resetPassword
  // =====================================================================

  @Nested
  @DisplayName("resetPassword")
  class ResetPasswordTests {

    @Test
    @DisplayName("should reject an unknown reset token")
    void shouldThrowBadCredentials_whenTokenNotFound() {
      // Given
      when(passwordResetTokenRepository.findById("tok")).thenReturn(Optional.empty());

      // When / Then
      assertThatThrownBy(
              () -> authService.resetPassword(new ResetPasswordRequestDto("tok", "newpass1")))
          .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    @DisplayName("should delete and reject a reset token with no expiry set")
    void shouldThrowBadCredentials_whenExpiresAtIsNull() {
      // Given
      PasswordResetToken token = new PasswordResetToken("tok", USER_ID, null);
      when(passwordResetTokenRepository.findById("tok")).thenReturn(Optional.of(token));

      // When / Then
      assertThatThrownBy(
              () -> authService.resetPassword(new ResetPasswordRequestDto("tok", "newpass1")))
          .isInstanceOf(BadCredentialsException.class);
      verify(passwordResetTokenRepository).delete(token);
    }

    @Test
    @DisplayName("should delete and reject an expired reset token")
    void shouldThrowBadCredentials_whenTokenExpired() {
      // Given
      PasswordResetToken token =
          new PasswordResetToken("tok", USER_ID, OffsetDateTime.now().minusHours(1));
      when(passwordResetTokenRepository.findById("tok")).thenReturn(Optional.of(token));

      // When / Then
      assertThatThrownBy(
              () -> authService.resetPassword(new ResetPasswordRequestDto("tok", "newpass1")))
          .isInstanceOf(BadCredentialsException.class);
      verify(passwordResetTokenRepository).delete(token);
    }

    @Test
    @DisplayName("should reject when the token's owner no longer exists")
    void shouldThrowBadCredentials_whenOwnerNotFound() {
      // Given
      PasswordResetToken token =
          new PasswordResetToken("tok", USER_ID, OffsetDateTime.now().plusHours(1));
      when(passwordResetTokenRepository.findById("tok")).thenReturn(Optional.of(token));
      when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

      // When / Then
      assertThatThrownBy(
              () -> authService.resetPassword(new ResetPasswordRequestDto("tok", "newpass1")))
          .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    @DisplayName("should reject when the account's email is not verified")
    void shouldThrowValidationException_whenEmailNotVerified() {
      // Given
      PasswordResetToken token =
          new PasswordResetToken("tok", USER_ID, OffsetDateTime.now().plusHours(1));
      UserEntity user = verifiedUser(USER_ID, EMAIL, false);
      user.setEmailVerified(false);
      when(passwordResetTokenRepository.findById("tok")).thenReturn(Optional.of(token));
      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

      // When / Then
      assertThatThrownBy(
              () -> authService.resetPassword(new ResetPasswordRequestDto("tok", "newpass1")))
          .isInstanceOf(ValidationException.class)
          .hasMessageContaining("verify your email");
    }

    @Test
    @DisplayName("should reject when the new password matches the current one")
    void shouldThrowValidationException_whenNewPasswordSameAsCurrent() {
      // Given
      PasswordResetToken token =
          new PasswordResetToken("tok", USER_ID, OffsetDateTime.now().plusHours(1));
      UserEntity user = verifiedUser(USER_ID, EMAIL, false);
      when(passwordResetTokenRepository.findById("tok")).thenReturn(Optional.of(token));
      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
      when(passwordEncoder.matches("samepass", ENCODED_PASSWORD)).thenReturn(true);

      // When / Then
      assertThatThrownBy(
              () -> authService.resetPassword(new ResetPasswordRequestDto("tok", "samepass")))
          .isInstanceOf(ValidationException.class)
          .hasMessageContaining("must be different");
    }

    @Test
    @DisplayName("should update the password and delete the reset token on success")
    void shouldResetPassword_whenAllChecksPass() {
      // Given
      PasswordResetToken token =
          new PasswordResetToken("tok", USER_ID, OffsetDateTime.now().plusHours(1));
      UserEntity user = verifiedUser(USER_ID, EMAIL, false);
      when(passwordResetTokenRepository.findById("tok")).thenReturn(Optional.of(token));
      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
      when(passwordEncoder.matches("newpass1", ENCODED_PASSWORD)).thenReturn(false);
      when(passwordEncoder.encode("newpass1")).thenReturn("{bcrypt}newEncoded");

      // When
      authService.resetPassword(new ResetPasswordRequestDto("tok", "newpass1"));

      // Then
      assertThat(user.getPassword()).isEqualTo("{bcrypt}newEncoded");
      verify(userRepository).save(user);
      verify(passwordResetTokenRepository).delete(token);
    }
  }

  // =====================================================================
  // verifyEmail
  // =====================================================================

  @Nested
  @DisplayName("verifyEmail")
  class VerifyEmailTests {

    @Test
    @DisplayName("should reject an unknown verification token")
    void shouldThrowBadCredentials_whenTokenNotFound() {
      // Given
      when(emailVerificationTokenRepository.findById("tok")).thenReturn(Optional.empty());

      // When / Then
      assertThatThrownBy(() -> authService.verifyEmail(new VerifyEmailRequestDto("tok")))
          .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    @DisplayName("should delete and reject a verification token with no expiry set")
    void shouldThrowBadCredentials_whenExpiresAtIsNull() {
      // Given
      EmailVerificationToken token = new EmailVerificationToken("tok", USER_ID, null);
      when(emailVerificationTokenRepository.findById("tok")).thenReturn(Optional.of(token));

      // When / Then
      assertThatThrownBy(() -> authService.verifyEmail(new VerifyEmailRequestDto("tok")))
          .isInstanceOf(BadCredentialsException.class);
      verify(emailVerificationTokenRepository).delete(token);
    }

    @Test
    @DisplayName("should delete and reject an expired verification token")
    void shouldThrowBadCredentials_whenTokenExpired() {
      // Given
      EmailVerificationToken token =
          new EmailVerificationToken("tok", USER_ID, OffsetDateTime.now().minusHours(1));
      when(emailVerificationTokenRepository.findById("tok")).thenReturn(Optional.of(token));

      // When / Then
      assertThatThrownBy(() -> authService.verifyEmail(new VerifyEmailRequestDto("tok")))
          .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    @DisplayName("should reject when the token's owner no longer exists")
    void shouldThrowBadCredentials_whenOwnerNotFound() {
      // Given
      EmailVerificationToken token =
          new EmailVerificationToken("tok", USER_ID, OffsetDateTime.now().plusHours(1));
      when(emailVerificationTokenRepository.findById("tok")).thenReturn(Optional.of(token));
      when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

      // When / Then
      assertThatThrownBy(() -> authService.verifyEmail(new VerifyEmailRequestDto("tok")))
          .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    @DisplayName("should mark the account verified and delete the token on success")
    void shouldVerifyEmail_whenTokenValid() {
      // Given
      EmailVerificationToken token =
          new EmailVerificationToken("tok", USER_ID, OffsetDateTime.now().plusHours(1));
      UserEntity user = verifiedUser(USER_ID, EMAIL, false);
      user.setEmailVerified(false);
      when(emailVerificationTokenRepository.findById("tok")).thenReturn(Optional.of(token));
      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

      // When
      authService.verifyEmail(new VerifyEmailRequestDto("tok"));

      // Then
      assertThat(user.isEmailVerified()).isTrue();
      verify(userRepository).save(user);
      verify(emailVerificationTokenRepository).delete(token);
    }
  }

  // =====================================================================
  // requestMagicLink
  // =====================================================================

  @Nested
  @DisplayName("requestMagicLink")
  class RequestMagicLinkTests {

    @Test
    @DisplayName("should do nothing when no account has that email")
    void shouldDoNothing_whenEmailNotFound() {
      // Given
      when(userRepository.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.empty());

      // When
      authService.requestMagicLink(new MagicLinkRequestDto(EMAIL));

      // Then
      verifyNoInteractions(mailService, magicLinkTokenRepository);
    }

    @Test
    @DisplayName("should issue a magic link token and email it when the account exists")
    void shouldSendMagicLinkEmail_whenEmailFound() {
      // Given
      UserEntity user = verifiedUser(USER_ID, EMAIL, false);
      when(userRepository.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(user));

      // When
      authService.requestMagicLink(new MagicLinkRequestDto(EMAIL));

      // Then
      verify(magicLinkTokenRepository).deleteByUserId(USER_ID);
      verify(magicLinkTokenRepository).save(any());
      verify(mailService).sendMagicLinkEmail(eq(EMAIL), eq("Jane Doe"), any());
    }
  }

  // =====================================================================
  // loginWithMagicLink
  // =====================================================================

  @Nested
  @DisplayName("loginWithMagicLink")
  class LoginWithMagicLinkTests {

    @Test
    @DisplayName("should reject an unknown magic link token")
    void shouldThrowBadCredentials_whenTokenNotFound() {
      // Given
      when(magicLinkTokenRepository.findById("tok")).thenReturn(Optional.empty());

      // When / Then
      assertThatThrownBy(() -> authService.loginWithMagicLink(new MagicLinkLoginRequestDto("tok")))
          .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    @DisplayName("should delete the token then reject it if it has no expiry set")
    void shouldThrowBadCredentials_whenExpiresAtIsNull() {
      // Given
      MagicLinkToken token = new MagicLinkToken("tok", USER_ID, null);
      when(magicLinkTokenRepository.findById("tok")).thenReturn(Optional.of(token));

      // When / Then
      assertThatThrownBy(() -> authService.loginWithMagicLink(new MagicLinkLoginRequestDto("tok")))
          .isInstanceOf(BadCredentialsException.class);
      verify(magicLinkTokenRepository).delete(token);
    }

    @Test
    @DisplayName("should delete the token then reject it if it is expired")
    void shouldThrowBadCredentials_whenTokenExpired() {
      // Given
      MagicLinkToken token =
          new MagicLinkToken("tok", USER_ID, OffsetDateTime.now().minusMinutes(1));
      when(magicLinkTokenRepository.findById("tok")).thenReturn(Optional.of(token));

      // When / Then
      assertThatThrownBy(() -> authService.loginWithMagicLink(new MagicLinkLoginRequestDto("tok")))
          .isInstanceOf(BadCredentialsException.class);
      verify(magicLinkTokenRepository).delete(token);
    }

    @Test
    @DisplayName("should reject when the token's owner no longer exists")
    void shouldThrowBadCredentials_whenOwnerNotFound() {
      // Given
      MagicLinkToken token =
          new MagicLinkToken("tok", USER_ID, OffsetDateTime.now().plusMinutes(10));
      when(magicLinkTokenRepository.findById("tok")).thenReturn(Optional.of(token));
      when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

      // When / Then
      assertThatThrownBy(() -> authService.loginWithMagicLink(new MagicLinkLoginRequestDto("tok")))
          .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    @DisplayName("should reject a banned account")
    void shouldThrowAccountBanned_whenOwnerIsBanned() {
      // Given
      MagicLinkToken token =
          new MagicLinkToken("tok", USER_ID, OffsetDateTime.now().plusMinutes(10));
      when(magicLinkTokenRepository.findById("tok")).thenReturn(Optional.of(token));
      when(userRepository.findById(USER_ID))
          .thenReturn(Optional.of(verifiedUser(USER_ID, EMAIL, true)));

      // When / Then
      assertThatThrownBy(() -> authService.loginWithMagicLink(new MagicLinkLoginRequestDto("tok")))
          .isInstanceOf(AccountBannedException.class);
    }

    @Test
    @DisplayName("should issue tokens on a valid, unexpired magic link")
    void shouldIssueTokens_whenTokenValid() {
      // Given
      MagicLinkToken token =
          new MagicLinkToken("tok", USER_ID, OffsetDateTime.now().plusMinutes(10));
      UserEntity user = verifiedUser(USER_ID, EMAIL, false);
      when(magicLinkTokenRepository.findById("tok")).thenReturn(Optional.of(token));
      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
      AuthResponseDto expected =
          new AuthResponseDto("access", "refresh", "Bearer", 900, false, false);
      when(tokenService.issueTokens(user)).thenReturn(expected);

      // When
      AuthResponseDto result = authService.loginWithMagicLink(new MagicLinkLoginRequestDto("tok"));

      // Then
      assertThat(result).isEqualTo(expected);
      verify(magicLinkTokenRepository).delete(token);
    }
  }

  // =====================================================================
  // logout
  // =====================================================================

  @Nested
  @DisplayName("logout")
  class LogoutTests {

    @Test
    @DisplayName("should delete the refresh token when it exists")
    void shouldDeleteToken_whenFound() {
      // Given
      RefreshToken stored = new RefreshToken("tok", USER_ID, OffsetDateTime.now().plusDays(1));
      when(refreshTokenRepository.findById("tok")).thenReturn(Optional.of(stored));

      // When
      authService.logout(new RefreshTokenRequestDto("tok"));

      // Then
      verify(refreshTokenRepository).delete(stored);
    }

    @Test
    @DisplayName("should do nothing when the refresh token does not exist")
    void shouldDoNothing_whenNotFound() {
      // Given
      when(refreshTokenRepository.findById("tok")).thenReturn(Optional.empty());

      // When
      authService.logout(new RefreshTokenRequestDto("tok"));

      // Then
      verify(refreshTokenRepository, never()).delete(any());
    }
  }

  @Nested
  @DisplayName("register — username assignment")
  class RegisterUsernameTests {

    private void stubHappyPath() {
      when(userRepository.existsByEmailIgnoreCase(EMAIL)).thenReturn(false);
      when(passwordEncoder.encode(RAW_PASSWORD)).thenReturn(ENCODED_PASSWORD);
    }

    @Test
    @DisplayName("should derive a handle from the full name when the caller sends none")
    void shouldDeriveHandleFromFullName() {
      // Given: the old three-field body, which every existing client still sends
      stubHappyPath();
      RegisterRequestDto request =
          new RegisterRequestDto(EMAIL, RAW_PASSWORD, "Trần Phú Thịnh", null);

      // When
      authService.register(request, null);

      // Then — before this, a real signup stored username = NULL, which is why the one account in
      // the database that did not come from the seed script was the only one missing a handle
      verify(userRepository).save(userCaptor.capture());
      assertThat(userCaptor.getValue().getUsername()).isEqualTo("tran-phu-thinh");
    }

    @Test
    @DisplayName("should keep the handle the caller chose")
    void shouldKeepChosenHandle() {
      // Given
      stubHappyPath();
      when(userRepository.existsByUsernameIgnoreCase("ada-lovelace")).thenReturn(false);
      RegisterRequestDto request =
          new RegisterRequestDto(EMAIL, RAW_PASSWORD, "Ada Lovelace", "ada-lovelace");

      // When
      authService.register(request, null);

      // Then
      verify(userRepository).save(userCaptor.capture());
      assertThat(userCaptor.getValue().getUsername()).isEqualTo("ada-lovelace");
    }

    @Test
    @DisplayName("should reject a chosen handle that is taken, rather than silently changing it")
    void shouldRejectTakenHandle() {
      // Given
      when(userRepository.existsByEmailIgnoreCase(EMAIL)).thenReturn(false);
      when(passwordEncoder.encode(RAW_PASSWORD)).thenReturn(ENCODED_PASSWORD);
      when(userRepository.existsByUsernameIgnoreCase("ada")).thenReturn(true);
      RegisterRequestDto request =
          new RegisterRequestDto(EMAIL, RAW_PASSWORD, "Ada Lovelace", "ada");

      // When / Then — being told the handle is taken is normal; being handed a different one
      // without being told is not
      assertThatThrownBy(() -> authService.register(request, null))
          .isInstanceOf(ValidationException.class)
          .hasMessageContaining("Username already taken");
    }

    @Test
    @DisplayName("should suffix a derived handle that collides, instead of failing registration")
    void shouldSuffixCollidingDerivedHandle() {
      // Given: someone already holds the handle this name derives to
      stubHappyPath();
      when(userRepository.existsByUsernameIgnoreCase("ada-lovelace")).thenReturn(true);
      RegisterRequestDto request =
          new RegisterRequestDto(EMAIL, RAW_PASSWORD, "Ada Lovelace", null);

      // When
      authService.register(request, null);

      // Then — the user did not choose this handle and can do nothing about the clash, so a
      // derived handle is disambiguated rather than rejected. Id is null on an unsaved mock, which
      // is fine: what is pinned is that it is suffixed and registration proceeds.
      verify(userRepository).save(userCaptor.capture());
      assertThat(userCaptor.getValue().getUsername()).startsWith("ada-lovelace-");
    }

    @Test
    @DisplayName("should fall back to the id when the name yields no usable handle")
    void shouldFallBackToIdForUnusableName() {
      // Given: a name of punctuation only
      stubHappyPath();
      RegisterRequestDto request = new RegisterRequestDto(EMAIL, RAW_PASSWORD, "!!!", null);

      // When
      authService.register(request, null);

      // Then
      verify(userRepository).save(userCaptor.capture());
      assertThat(userCaptor.getValue().getUsername()).startsWith("user-");
    }
  }
}
