package com.socialapp.moderation.service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialapp.common.exception.ForbiddenException;
import com.socialapp.common.exception.NotFoundException;
import com.socialapp.common.exception.ValidationException;
import com.socialapp.moderation.dto.AppealDto;
import com.socialapp.moderation.dto.AppealRequestDto;
import com.socialapp.moderation.dto.UserViolationDto;
import com.socialapp.moderation.entity.ModerationAppealEntity;
import com.socialapp.moderation.entity.UserViolationEntity;
import com.socialapp.moderation.enums.AppealStatus;
import com.socialapp.moderation.repository.ModerationAppealRepository;
import com.socialapp.moderation.repository.UserViolationRepository;
import com.socialapp.notifications.NotificationMessages;
import com.socialapp.notifications.dto.SendNotificationRequest;
import com.socialapp.notifications.entity.enums.NotificationType;
import com.socialapp.notifications.services.NotificationService;
import com.socialapp.security.entity.UserEntity;
import com.socialapp.security.repository.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * The appeal flow: a sanctioned user disputes a violation, an admin decides.
 *
 * <p>This is the missing other half of moderation. The system could record a violation, count it
 * toward a seven-day ban and refuse every request with a 403 telling the user to "contact support"
 * — with no support to contact and no way to see what they were accused of. Everything here exists
 * so that a wrong sanction is correctable by the person it lands on rather than only by someone
 * with database access.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AppealService {

  private final ModerationAppealRepository appealRepository;
  private final UserViolationRepository violationRepository;
  private final UserRepository userRepository;
  private final UserBanService userBanService;
  private final NotificationService notificationService;

  /** What has been recorded against the caller, and whether each item is already under appeal. */
  @Transactional(readOnly = true)
  public List<UserViolationDto> getMyViolations(Integer userId) {
    List<UserViolationEntity> violations =
        violationRepository.findByUserIdOrderByCreatedAtDesc(userId);
    if (violations.isEmpty()) {
      return List.of();
    }

    // One query for the whole page rather than an exists() per row: this used to cost a round
    // trip per violation just to draw the "under appeal" badge.
    Set<Long> underAppeal =
        Set.copyOf(
            appealRepository.findViolationIdsWithStatus(
                violations.stream().map(UserViolationEntity::getId).toList(),
                AppealStatus.PENDING));

    return violations.stream()
        .map(v -> UserViolationDto.from(v, underAppeal.contains(v.getId())))
        .toList();
  }

  /**
   * Files an appeal against one violation.
   *
   * @throws ForbiddenException if the violation belongs to somebody else. A 403 rather than a 404:
   *     the violation does exist, and pretending otherwise would be a different lie. Either way the
   *     caller learns nothing about whose it is.
   * @throws ValidationException if an appeal for this violation is already waiting on an admin.
   *     Enforced for real by the partial unique index in {@code V49}; this check only turns the
   *     ordinary double-submit into a clear message instead of a constraint violation.
   */
  @Transactional
  public AppealDto submitAppeal(Integer userId, AppealRequestDto request) {
    UserViolationEntity violation =
        violationRepository
            .findById(request.getViolationId())
            .orElseThrow(
                () ->
                    new NotFoundException(
                        "Violation not found with ID: " + request.getViolationId()));

    if (!violation.getUserId().equals(userId)) {
      throw new ForbiddenException("You can only appeal a violation recorded against you");
    }

    if (appealRepository.existsByViolationIdAndStatus(violation.getId(), AppealStatus.PENDING)) {
      throw new ValidationException("An appeal for this violation is already under review");
    }

    ModerationAppealEntity appeal =
        appealRepository.save(
            ModerationAppealEntity.builder()
                .userId(userId)
                .violationId(violation.getId())
                .reason(request.getReason())
                .status(AppealStatus.PENDING)
                .build());

    log.info("User {} appealed violation {}", userId, violation.getId());
    return toDto(appeal, violation, null);
  }

  /** The caller's own appeals and how they went. */
  @Transactional(readOnly = true)
  public List<AppealDto> getMyAppeals(Integer userId) {
    List<ModerationAppealEntity> appeals =
        appealRepository.findByUserIdOrderByCreatedAtDesc(userId);
    Map<Long, UserViolationEntity> violations = loadViolations(appeals);

    return appeals.stream().map(a -> toDto(a, violationOf(violations, a), null)).toList();
  }

  /**
   * The admin queue, oldest first.
   *
   * <p>Three queries for a page regardless of its size: the appeals, then their violations, then
   * their authors. Reading each appellant inside the loop would be one user query per row.
   */
  @Transactional(readOnly = true)
  public Page<AppealDto> getAppeals(AppealStatus status, Pageable pageable) {
    Page<ModerationAppealEntity> page =
        appealRepository.findByStatusOrderByCreatedAtAsc(status, pageable);

    Map<Long, UserViolationEntity> violations = loadViolations(page.getContent());
    Map<Integer, UserEntity> users =
        userRepository
            .findAllById(
                page.getContent().stream()
                    .map(ModerationAppealEntity::getUserId)
                    .distinct()
                    .toList())
            .stream()
            .collect(Collectors.toMap(UserEntity::getId, Function.identity()));

    List<AppealDto> dtos =
        page.getContent().stream()
            .map(a -> toDto(a, violationOf(violations, a), users.get(a.getUserId())))
            .toList();

    return new PageImpl<>(dtos, pageable, page.getTotalElements());
  }

  /**
   * Upholds an appeal: the violation is erased and any ban that rested on it is re-evaluated.
   *
   * <p>Approving without undoing the violation would make the appeal a formality — the user would
   * be told they were right and stay locked out. Whether the ban actually lifts is {@code
   * UserBanService.revokeViolation}'s call, because a user with other violations may still be over
   * the threshold.
   */
  @Transactional
  public AppealDto approve(Long appealId, Integer reviewerId, String note) {
    ModerationAppealEntity appeal = requirePending(appealId);
    Long violationId = appeal.getViolationId();

    appeal.setStatus(AppealStatus.APPROVED);
    stampReview(appeal, reviewerId, note);

    // Detach the appeal from the violation, and persist that, BEFORE deleting the violation.
    //
    // The order is load-bearing. This entity is managed, so its violation_id would be flushed
    // back at commit — pointing at a row that revokeViolation has just deleted, which the foreign
    // key rejects. Clearing it first also means the appeal survives its own success: the column
    // is ON DELETE SET NULL rather than CASCADE precisely so that upholding an appeal does not
    // erase the user's record of having won it (see V49).
    appeal.setViolationId(null);
    ModerationAppealEntity saved = appealRepository.saveAndFlush(appeal);

    if (violationId != null) {
      userBanService.revokeViolation(violationId);
    }
    log.info("Appeal {} approved by {}; violation {} revoked", appealId, reviewerId, violationId);

    notifyAppellant(saved, NotificationType.APPEAL_APPROVED, NotificationMessages.APPEAL_APPROVED);

    // Nothing left to describe: the violation this disputed no longer exists.
    return toDto(saved, null, null);
  }

  /** Rejects an appeal. The violation and any ban stand. */
  @Transactional
  public AppealDto reject(Long appealId, Integer reviewerId, String note) {
    ModerationAppealEntity appeal = requirePending(appealId);

    appeal.setStatus(AppealStatus.REJECTED);
    stampReview(appeal, reviewerId, note);
    ModerationAppealEntity saved = appealRepository.save(appeal);

    log.info("Appeal {} rejected by {}", appealId, reviewerId);
    notifyAppellant(saved, NotificationType.APPEAL_REJECTED, NotificationMessages.APPEAL_REJECTED);

    return toDto(
        saved,
        saved.getViolationId() == null
            ? null
            : violationRepository.findById(saved.getViolationId()).orElse(null),
        null);
  }

  /**
   * Tells the appellant how their appeal was decided (B44 in {@code docs/backend-plan.md}) — until
   * now the only way to find out was to reopen the appeal list and notice the status had changed.
   */
  private void notifyAppellant(
      ModerationAppealEntity appeal, NotificationType type, String messageKey) {
    boolean approved = NotificationType.APPEAL_APPROVED.equals(type);
    notificationService.send(
        SendNotificationRequest.builder()
            .recipientId(appeal.getUserId())
            .type(type)
            .title(approved ? "Appeal approved" : "Appeal rejected")
            .body(
                approved
                    ? "Your appeal was approved and the violation was removed"
                    : "Your appeal was rejected")
            .messageKey(messageKey)
            .build());
  }

  private ModerationAppealEntity requirePending(Long appealId) {
    ModerationAppealEntity appeal =
        appealRepository
            .findById(appealId)
            .orElseThrow(() -> new NotFoundException("Appeal not found with ID: " + appealId));

    // An already-decided appeal cannot be decided again: the approval path deletes a violation,
    // and a second approval would go looking for one that is gone.
    if (!AppealStatus.PENDING.equals(appeal.getStatus())) {
      throw new ValidationException(
          "This appeal has already been " + appeal.getStatus().name().toLowerCase(Locale.ROOT));
    }
    return appeal;
  }

  private void stampReview(ModerationAppealEntity appeal, Integer reviewerId, String note) {
    appeal.setReviewerId(reviewerId);
    appeal.setReviewerNote(note);
    appeal.setReviewedAt(OffsetDateTime.now());
  }

  /** Null-safe lookup: an upheld appeal no longer points at a violation. */
  private UserViolationEntity violationOf(
      Map<Long, UserViolationEntity> byId, ModerationAppealEntity appeal) {
    return appeal.getViolationId() == null ? null : byId.get(appeal.getViolationId());
  }

  private Map<Long, UserViolationEntity> loadViolations(List<ModerationAppealEntity> appeals) {
    // Nulls are filtered, not incidental: an upheld appeal has had its violation deleted and its
    // violation_id set to null (V49), so a decided-appeals list legitimately contains them —
    // and findAllById would choke on a null id.
    List<Long> ids =
        appeals.stream()
            .map(ModerationAppealEntity::getViolationId)
            .filter(java.util.Objects::nonNull)
            .distinct()
            .toList();
    if (ids.isEmpty()) {
      return Map.of();
    }
    return violationRepository.findAllById(ids).stream()
        .collect(Collectors.toMap(UserViolationEntity::getId, Function.identity()));
  }

  /**
   * {@code violation} may be null — an approved appeal has deleted the very row it disputed, and a
   * user reading their own list does not need the appellant hydrated ({@code appellant} null).
   */
  private AppealDto toDto(
      ModerationAppealEntity appeal, UserViolationEntity violation, UserEntity appellant) {
    return AppealDto.builder()
        .id(appeal.getId())
        .userId(appeal.getUserId())
        .username(appellant == null ? null : appellant.getUsername())
        .userFullName(appellant == null ? null : appellant.getFullName())
        .violationId(appeal.getViolationId())
        .violationType(violation == null ? null : violation.getViolationType())
        .violationDescription(violation == null ? null : violation.getDescription())
        .reason(appeal.getReason())
        .status(appeal.getStatus())
        .reviewerNote(appeal.getReviewerNote())
        .reviewedAt(appeal.getReviewedAt())
        .createdAt(appeal.getCreatedAt())
        .build();
  }
}
