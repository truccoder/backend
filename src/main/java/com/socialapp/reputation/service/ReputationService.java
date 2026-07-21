package com.socialapp.reputation.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialapp.common.exception.NotFoundException;
import com.socialapp.reputation.RepLevel;
import com.socialapp.reputation.dto.ReputationResponseDto;
import com.socialapp.reputation.entity.enums.RepSourceType;
import com.socialapp.reputation.repository.ReputationEventRepository;
import com.socialapp.roadmap.enums.VerificationStatus;
import com.socialapp.roadmap.repository.UserRoadmapProgressRepository;
import com.socialapp.security.entity.UserEntity;
import com.socialapp.security.repository.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReputationService {
  private final ReputationEventRepository reputationEventRepository;
  private final UserRepository userRepository;
  private final UserRoadmapProgressRepository userRoadmapProgressRepository;

  /**
   * Awards points for one signal, identified by ({@code userId}, {@code sourceType}, {@code
   * sourceId}). Idempotent: re-awarding the same triple is a no-op, guarded by the DB unique
   * constraint rather than a check-then-act read.
   */
  @Transactional
  public void award(Integer userId, RepSourceType sourceType, String sourceId) {
    int inserted =
        reputationEventRepository.insertIfAbsent(
            userId, sourceType.name(), sourceId, sourceType.getPoints());
    if (inserted > 0) {
      userRepository.adjustEliteScore(userId, sourceType.getPoints());
    } else {
      log.debug(
          "Reputation event already recorded, skipping award: user={} source={} id={}",
          userId,
          sourceType,
          sourceId);
    }
  }

  /** Reverses a previously awarded signal, e.g. a reaction being removed. */
  @Transactional
  public void revoke(Integer userId, RepSourceType sourceType, String sourceId) {
    int deleted =
        reputationEventRepository.deleteByUserIdAndSourceTypeAndSourceId(
            userId, sourceType, sourceId);
    if (deleted > 0) {
      userRepository.adjustEliteScore(userId, -sourceType.getPoints());
    }
  }

  public RepLevel repLevel(int score) {
    return RepLevel.forScore(score);
  }

  @Transactional(readOnly = true)
  public ReputationResponseDto getReputation(Integer userId) {
    UserEntity user =
        userRepository
            .findById(userId)
            .orElseThrow(() -> new NotFoundException("User not found with ID: " + userId));

    int score = user.getEliteScore();
    RepLevel level = repLevel(score);
    RepLevel next = level.next();

    boolean verifiedExpert =
        userRoadmapProgressRepository.existsByUserIdAndStatus(userId, VerificationStatus.VERIFIED);

    return ReputationResponseDto.builder()
        .eliteScore(score)
        .level(level.getLevel())
        .levelName(level.getDisplayName())
        .nextLevelMin(next != null ? next.getMin() : null)
        .verifiedExpert(verifiedExpert)
        .build();
  }

  /** Recomputes and persists {@code elite_score} from the ledger; used by the nightly reconcile job. */
  @Transactional
  public void reconcile(Integer userId) {
    int total = reputationEventRepository.sumPointsByUserId(userId);
    userRepository.setEliteScore(userId, total);
  }
}
