package com.socialapp.moderation.service;

import java.time.OffsetDateTime;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialapp.moderation.entity.UserBanEntity;
import com.socialapp.moderation.entity.UserViolationEntity;
import com.socialapp.moderation.enums.ViolationSeverity;
import com.socialapp.moderation.enums.ViolationType;
import com.socialapp.moderation.repository.UserBanRepository;
import com.socialapp.moderation.repository.UserViolationRepository;
import com.socialapp.security.entity.UserEntity;
import com.socialapp.security.repository.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserBanService {
  private final UserViolationRepository violationRepository;
  private final UserBanRepository userBanRepository;
  private final UserRepository userRepository;

  private static final int VIOLATIONS_BEFORE_BAN = 2;
  private static final int BAN_DURATION_DAYS = 7;

  public boolean isUserBanned(Integer userId) {
    return userRepository.findById(userId).map(UserEntity::isBanned).orElse(false);
  }

  public OffsetDateTime getBanExpiry(Integer userId) {
    return userRepository.findById(userId).map(UserEntity::getBannedUntil).orElse(null);
  }

  @Transactional
  public void recordViolation(
      Integer userId, Integer postId, ViolationType violationType, String description) {
    ViolationSeverity severity = determineSeverity(violationType);

    UserViolationEntity violation =
        UserViolationEntity.builder()
            .userId(userId)
            .postId(postId)
            .violationType(violationType)
            .severity(severity)
            .description(description)
            .build();

    violationRepository.save(violation);
    log.info(
        "Recorded violation for user {}: type={}, severity={}", userId, violationType, severity);

    evaluateAndBanIfNeeded(userId, postId);
  }

  /**
   * Erases one violation and re-decides whether the user should still be banned.
   *
   * <p>Called when an appeal is upheld. Deleting the row rather than flagging it: the row exists to
   * be counted toward {@link #VIOLATIONS_BEFORE_BAN}, and a violation that has been judged wrong
   * must not keep counting. {@code UserViolationEntity} has no "voided" state to set, and adding
   * one would mean every count query in this class has to remember to exclude it — a filter that is
   * easy to forget in the next query someone writes.
   *
   * <p>Lifting the ban is not automatic: it is lifted only if what remains no longer reaches the
   * threshold. A user with three violations who successfully appeals one still has two, and two is
   * what got them banned.
   */
  @Transactional
  public void revokeViolation(Long violationId) {
    UserViolationEntity violation = violationRepository.findById(violationId).orElse(null);
    if (violation == null) {
      return;
    }

    Integer userId = violation.getUserId();
    violationRepository.delete(violation);
    // Flushed before recounting, or countRecentViolations still sees the row just deleted and the
    // ban is never lifted.
    violationRepository.flush();

    long remaining =
        violationRepository.countRecentViolations(userId, getViolationCountStartDate(userId));

    if (remaining < VIOLATIONS_BEFORE_BAN) {
      userRepository
          .findById(userId)
          .ifPresent(
              user -> {
                user.setBannedUntil(null);
                userRepository.save(user);
              });
      log.warn("Ban lifted for user {} after a violation was revoked ({} left)", userId, remaining);
    }
  }

  private void evaluateAndBanIfNeeded(Integer userId, Integer triggeringPostId) {
    OffsetDateTime countSince = getViolationCountStartDate(userId);
    long recentViolationCount = violationRepository.countRecentViolations(userId, countSince);

    if (recentViolationCount >= VIOLATIONS_BEFORE_BAN && !isUserBanned(userId)) {
      issueBan(userId, triggeringPostId);
    }
  }

  private OffsetDateTime getViolationCountStartDate(Integer userId) {
    OffsetDateTime bannedUntil = getBanExpiry(userId);
    if (Objects.nonNull(bannedUntil) && OffsetDateTime.now().isAfter(bannedUntil)) {
      return bannedUntil;
    }
    return OffsetDateTime.of(2000, 1, 1, 0, 0, 0, 0, java.time.ZoneOffset.UTC);
  }

  private void issueBan(Integer userId, Integer triggeringPostId) {
    OffsetDateTime expiresAt = OffsetDateTime.now().plusDays(BAN_DURATION_DAYS);

    userRepository
        .findById(userId)
        .ifPresent(
            user -> {
              user.setBannedUntil(expiresAt);
              userRepository.save(user);
            });

    userBanRepository.save(
        UserBanEntity.builder()
            .userId(userId)
            .postId(triggeringPostId)
            .bannedUntil(expiresAt)
            .build());

    log.warn("User {} has been banned until {}", userId, expiresAt);
  }

  private ViolationSeverity determineSeverity(ViolationType violationType) {
    return switch (violationType) {
      case HATE_SPEECH, VIOLENCE, THREAT -> ViolationSeverity.CRITICAL;
      case NSFW, SEXUALLY_EXPLICIT -> ViolationSeverity.HIGH;
      case INSULT, KEYWORD_BLACKLIST -> ViolationSeverity.MEDIUM;
      case SPAM, DUPLICATE_CONTENT -> ViolationSeverity.LOW;
    };
  }
}
