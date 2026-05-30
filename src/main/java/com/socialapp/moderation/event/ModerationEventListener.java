package com.socialapp.moderation.event;

import java.util.List;

import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

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
  @EventListener
  @Transactional
  public void handleModerationEvent(PostModerationEvent event) {
    log.info("Processing moderation for post {}", event.getPostId());

    try {
      ModerationScores textScores = textModerationService.analyzeText(event.getContent());
      ImageSafeSearchResult imageResult =
          imageModerationService.analyzeImages(event.getImageUrls());
      ModerationResult result = decisionEngine.decide(textScores, imageResult);

      updatePostStatus(event.getPostId(), result.getStatus());
      saveModerationLog(event.getPostId(), result);

      if (result.isRejected() && !result.getViolations().isEmpty()) {
        userBanService.recordViolation(
            event.getAuthorId(),
            event.getPostId(),
            result.getViolations().get(0),
            "AI moderation detected violation: " + result.getViolations());
      }

      if (result.isApproved()) {
        newsfeedService.fanOutPost(event.getPostId());
      }

      log.info(
          "Moderation completed for post {}: status={}", event.getPostId(), result.getStatus());
    } catch (Exception e) {
      log.error("Moderation failed for post {}", event.getPostId(), e);
      updatePostStatus(event.getPostId(), ModerationStatus.PENDING_REVIEW);
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
