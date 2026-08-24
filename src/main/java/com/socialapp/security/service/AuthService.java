package com.socialapp.security.service;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.UUID;

import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.socialapp.common.exception.ValidationException;
import com.socialapp.common.ratelimit.AuthRateLimitProperties;
import com.socialapp.common.ratelimit.FixedWindowRateLimiter;
import com.socialapp.moderation.service.BanDetailsService;
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
import com.socialapp.security.util.UsernameSlugger;

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
  private static final String MAIL_RATE_LIMIT_KEY_PREFIX = "ratelimit:authmail:";

  private final UserRepository userRepository;
  private final PasswordEncoder passwordEncoder;
  private final TokenService tokenService;
  private final RefreshTokenRepository refreshTokenRepository;
  private final PasswordResetTokenRepository passwordResetTokenRepository;
  private final EmailVerificationTokenRepository emailVerificationTokenRepository;
  private final MagicLinkTokenRepository magicLinkTokenRepository;
  private final MailService mailService;
  private final ProfileService profileService;
  private final FixedWindowRateLimiter rateLimiter;
  private final AuthRateLimitProperties rateLimitProperties;

  // Describes why an account is locked, so the 403 carries structured banDetails rather than
  // only an English sentence with a date embedded in it.
  private final BanDetailsService banDetailsService;

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

    // Decided before save(), and carried INTO the INSERT rather than set after it — see
    // decideUsername() and UsernameDecision.
    UsernameDecision handle = decideUsername(request.username(), request.fullname());
    user.setUsername(handle.handleBeforeInsert());

    userRepository.save(user);

    // Only the branches that need the id reach the database twice, and only ever as
    // INSERT-then-UPDATE inside this one transaction.
    if (handle.needsUserId()) {
      user.setUsername(handle.handleFor(user.getId()));
    }

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

  /**
   * Decides the handle the new account's public profile URL will be built from, doing every part
   * of the decision that reads {@code t_users}.
   *
   * <p>Called <b>before</b> {@code save()}, and deliberately so, for two independent reasons.
   * The INSERT has to carry the handle (see {@link UsernameDecision}), and it can only carry what
   * was decided by the time {@code persist()} ran. On top of that, an availability check is a
   * query against {@code t_users}, so running it after {@code save()} auto-flushes the pending
   * INSERT out early — turning a handle clash into whatever the half-built row happens to
   * violate, instead of the {@code ValidationException} the caller should get.
   *
   * <p>A handle the caller chose is taken as-is and rejected if taken — telling someone their
   * requested handle is unavailable is normal, and silently handing them a different one would be
   * worse. A handle the server derives is never rejected: the user did not ask for it and cannot
   * do anything about a clash, so the id is appended and registration proceeds.
   */
  private UsernameDecision decideUsername(String requestedUsername, String fullName) {
    if (requestedUsername != null && !requestedUsername.isBlank()) {
      if (userRepository.existsByUsernameIgnoreCase(requestedUsername)) {
        throw new ValidationException("Username already taken");
      }
      return UsernameDecision.settled(requestedUsername);
    }

    String slug = UsernameSlugger.slugify(fullName);
    if (slug == null) {
      // Nothing usable in the name; the id is the only thing left that is guaranteed unique.
      return UsernameDecision.needsUserId(null);
    }
    if (!UsernameSlugger.isLongEnough(slug) || userRepository.existsByUsernameIgnoreCase(slug)) {
      return UsernameDecision.needsUserId(slug);
    }
    return UsernameDecision.settled(slug);
  }

  /**
   * The outcome of {@link #decideUsername}: either a handle that is already final, or the base a
   * final handle gets built from once the user id exists. Holds no repository access on purpose:
   * everything it could have queried was already queried by {@code decideUsername}, back when
   * querying was still safe.
   *
   * <p>The split exists because the INSERT cannot wait for the id. Hibernate captures the row's
   * column values when {@code persist()} runs, not when the flush happens, so a value assigned to
   * the entity after {@code save()} does not reach the INSERT at all — it reaches the database
   * only as a follow-up UPDATE, long after {@code V47}'s NOT NULL constraint has already rejected
   * the INSERT that carried {@code username} NULL. So every registration hands the INSERT a
   * non-null handle up front via {@link #handleBeforeInsert()}: the final one when it is already
   * known, and otherwise a placeholder that {@link #handleFor(Integer)} overwrites in that
   * follow-up UPDATE, once {@code GenerationType.SEQUENCE} has produced the id.
   *
   * @param settled the final handle — one the caller chose, or a derived slug that needs no
   *     disambiguation — or {@code null} when the handle still needs the user id
   * @param base the slug a final handle gets suffixed onto, or {@code null} when the name yields
   *     no usable slug at all; meaningful only while {@code settled} is {@code null}
   */
  private record UsernameDecision(String settled, String base) {

    /**
     * Longer than the 30 characters {@link UsernameSlugger#USERNAME_PATTERN} allows, so no user
     * can be holding it, and random, so two signups racing each other cannot collide on it either.
     * Never visible outside the transaction that writes it: the UPDATE that replaces it commits
     * together with the INSERT that wrote it.
     */
    private static String placeholder() {
      return "pending-registration-" + UUID.randomUUID();
    }

    static UsernameDecision settled(String handle) {
      return new UsernameDecision(handle, null);
    }

    static UsernameDecision needsUserId(String base) {
      return new UsernameDecision(null, base);
    }

    boolean needsUserId() {
      return settled == null;
    }

    /** The handle the INSERT carries — see the class comment for why it cannot be {@code null}. */
    String handleBeforeInsert() {
      return settled != null ? settled : placeholder();
    }

    String handleFor(Integer userId) {
      if (settled != null) {
        return settled;
      }
      // Same disambiguation as V47's backfill: suffix the id, which is already unique, instead of
      // looping over "-2", "-3", … and racing another registration between the check and the
      // write. A slug too short to be a legal handle is suffixed the same way, so every branch
      // that is not the bare slug is unique by construction.
      return base == null ? "user-" + userId : base + "-" + userId;
    }
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
      throw new AccountBannedException(
          banDetailsService.describe(user.getId(), user.getBannedUntil()));
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

    // Resetting a password is what somebody does when they think their account is compromised, so
    // the sessions opened before the reset are exactly the ones to end. Refresh tokens were only
    // ever deleted on refresh and logout, which left a stolen one valid for its full TTL after the
    // victim had already changed the password.
    refreshTokenRepository.deleteByUserId(user.getId());
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
      throw new AccountBannedException(
          banDetailsService.describe(user.getId(), user.getBannedUntil()));
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

    if (!mailBudgetAvailable(user.getEmail())) {
      return;
    }

    PasswordResetToken resetToken = new PasswordResetToken();
    resetToken.setToken(generateSecureToken());
    resetToken.setUserId(user.getId());
    resetToken.setExpiresAt(OffsetDateTime.now().plusHours(RESET_TOKEN_EXPIRATION_HOURS));
    passwordResetTokenRepository.save(resetToken);

    mailService.sendPasswordResetEmail(
        user.getEmail(), getRecipientName(user), resetToken.getToken());
  }

  /**
   * Whether another mail may be sent to this address right now.
   *
   * <p>{@link com.socialapp.common.ratelimit.AuthRateLimitFilter} caps requests per source IP, and
   * that is the wrong key for this particular abuse. Reset, verification and magic-link mails go to
   * an address that never asked for them, so burying one inbox only needs enough source addresses
   * for each to stay politely under the IP budget. Counting per recipient is the only key that sees
   * that pattern.
   *
   * <p><b>Over budget means send nothing and return normally — it must not raise.</b> These flows
   * deliberately answer the same way whether or not the account exists ({@code forgotPassword} is
   * an {@code ifPresent} with no else). A 429 raised only once a real account is found would
   * restore exactly the account-enumeration oracle that design avoids: an attacker would ask four
   * times per address and read existence off the status code.
   */
  private boolean mailBudgetAvailable(String email) {
    if (rateLimiter.isOverLimit(
        MAIL_RATE_LIMIT_KEY_PREFIX + EmailNormalizer.normalize(email),
        rateLimitProperties.getMailRequests(),
        rateLimitProperties.getMailWindow())) {
      log.warn("Mail budget exhausted for a recipient; skipping send");
      return false;
    }
    return true;
  }

  private void createVerificationTokenAndSendEmail(UserEntity user) {
    if (!mailBudgetAvailable(user.getEmail())) {
      return;
    }

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
    if (!mailBudgetAvailable(user.getEmail())) {
      return;
    }

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
      throw new AccountBannedException(
          banDetailsService.describe(user.getId(), user.getBannedUntil()));
    }

    return user;
  }
}
