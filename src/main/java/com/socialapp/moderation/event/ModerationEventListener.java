package com.socialapp.moderation.event;

import java.util.List;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.socialapp.moderation.ai.ImageModerationService;
import com.socialapp.moderation.ai.ModerationDecisionEngine;
import com.socialapp.moderation.ai.TextModerationService;
import com.socialapp.moderation.dto.ImageSafeSearchResult;
import com.socialapp.moderation.dto.ModerationResult;
import com.socialapp.moderation.dto.ModerationScores;
import com.socialapp.moderation.entity.ModerationLogEntity;
import com.socialapp.moderation.enums.ModerationStatus;
import com.socialapp.moderation.repository.ModerationLogRepository;
import com.socialapp.moderation.service.UserBanService;
import com.socialapp.newsfeed.service.NewsfeedService;
import com.socialapp.posts.repository.PostRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class ModerationEventListener {
  private final TextModerationService textModerationService;
  private final ImageModerationService imageModerationService;
  private final ModerationDecisionEngine decisionEngine;
  private final ModerationLogRepository moderationLogRepository;
  private final PostRepository postRepository;
  private final NewsfeedService newsfeedService;
  private final UserBanService userBanService;

  @Async
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void handleModerationEvent(PostModerationEvent event) {
    Integer postId = event.getPostId();
    Integer authorId = event.getAuthorId();
    log.info("[postId={}, authorId={}] Processing moderation", postId, authorId);

    try {
      ModerationScores textScores =
          textModerationService.analyzeText(postId, authorId, event.getContent());
      ImageSafeSearchResult imageResult =
          imageModerationService.analyzeImages(postId, authorId, event.getImageUrls());
      ModerationResult result = decisionEngine.decide(postId, authorId, textScores, imageResult);

      updatePostStatus(postId, result.getStatus());
      saveModerationLog(postId, result);
      log.info(
          "[postId={}, authorId={}] Persisted moderation status={} and log entry",
          postId,
          authorId,
          result.getStatus());

      if (result.isRejected() && !result.getViolations().isEmpty()) {
        log.info(
            "[postId={}, authorId={}] Recording violation: {}",
            postId,
            authorId,
            result.getViolations().get(0));
        userBanService.recordViolation(
            authorId,
            postId,
            result.getViolations().get(0),
            "AI moderation detected violation: " + result.getViolations());
      }

      if (result.isApproved()) {
        log.info("[postId={}, authorId={}] Approved, fanning out to newsfeed", postId, authorId);
        newsfeedService.fanOutPost(postId);
      }

      log.info(
          "[postId={}, authorId={}] Moderation completed: status={}",
          postId,
          authorId,
          result.getStatus());
    } catch (Exception e) {
      log.error(
          "[postId={}, authorId={}] Moderation failed, falling back to PENDING_REVIEW",
          postId,
          authorId,
          e);
      updatePostStatus(postId, ModerationStatus.PENDING_REVIEW);
    }
  }

  private void updatePostStatus(Integer postId, ModerationStatus status) {
    postRepository
        .findById(postId)
        .ifPresent(
            post -> {
              post.setModerationStatus(status);
              postRepository.save(post);
            });
  }

  private void saveModerationLog(Integer postId, ModerationResult result) {
    List<String> violationNames = result.getViolations().stream().map(Enum::name).toList();

    ModerationLogEntity logEntity =
        ModerationLogEntity.builder()
            .postId(postId)
            .status(result.getStatus())
            .violationType(result.getViolations().isEmpty() ? null : result.getViolations().get(0))
            .textToxicityScore(result.getScores() != null ? result.getScores().getToxicity() : null)
            .imageSafeScore(
                result.getScores() != null ? result.getScores().getImageSafeScore() : null)
            .ruleViolations(violationNames.isEmpty() ? null : violationNames)
            .build();

    moderationLogRepository.save(logEntity);
  }
}
