package com.socialapp.moderation.service;

import java.time.OffsetDateTime;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialapp.moderation.entity.UserViolationEntity;
import com.socialapp.moderation.enums.ViolationSeverity;
import com.socialapp.moderation.enums.ViolationType;
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

    evaluateAndBanIfNeeded(userId);
  }

  private void evaluateAndBanIfNeeded(Integer userId) {
    OffsetDateTime countSince = getViolationCountStartDate(userId);
    long recentViolationCount = violationRepository.countRecentViolations(userId, countSince);

    if (recentViolationCount >= VIOLATIONS_BEFORE_BAN && !isUserBanned(userId)) {
      issueBan(userId);
    }
  }

  private OffsetDateTime getViolationCountStartDate(Integer userId) {
    OffsetDateTime bannedUntil = getBanExpiry(userId);
    if (Objects.nonNull(bannedUntil) && OffsetDateTime.now().isAfter(bannedUntil)) {
      return bannedUntil;
    }
    return OffsetDateTime.MIN;
  }

  private void issueBan(Integer userId) {
    OffsetDateTime expiresAt = OffsetDateTime.now().plusDays(BAN_DURATION_DAYS);

    userRepository
        .findById(userId)
        .ifPresent(
            user -> {
              user.setBannedUntil(expiresAt);
              userRepository.save(user);
            });

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
