package com.socialapp.moderation.service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialapp.common.exception.NotFoundException;
import com.socialapp.moderation.dto.ModerationScores;
import com.socialapp.moderation.dto.PendingReviewPostDto;
import com.socialapp.moderation.entity.ModerationLogEntity;
import com.socialapp.moderation.enums.Likelihood;
import com.socialapp.moderation.enums.ModerationStatus;
import com.socialapp.moderation.enums.ViolationType;
import com.socialapp.moderation.repository.ModerationLogRepository;
import com.socialapp.newsfeed.service.NewsfeedService;
import com.socialapp.posts.entity.PostEntity;
import com.socialapp.posts.repository.PostRepository;
import com.socialapp.security.entity.UserEntity;
import com.socialapp.security.repository.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminModerationService {
  private final PostRepository postRepository;
  private final UserRepository userRepository;
  private final ModerationLogRepository moderationLogRepository;
  private final NewsfeedService newsfeedService;
  private final UserBanService userBanService;

  public List<PendingReviewPostDto> getPendingReviewPosts() {
    List<PostEntity> posts = postRepository.findByModerationStatus(ModerationStatus.PENDING_REVIEW);
    return posts.stream().map(this::toPendingDto).toList();
  }

  @Transactional
  public void reviewPost(Integer postId, Likelihood decision, String feedback) {
    PostEntity post =
        postRepository
            .findById(postId)
            .orElseThrow(() -> new NotFoundException("Post not found: " + postId));

    if (!ModerationStatus.PENDING_REVIEW.equals(post.getModerationStatus())) {
      throw new IllegalStateException("Post is not in PENDING_REVIEW status");
    }

    boolean isViolation = decision.isAtLeast(Likelihood.LIKELY);

    if (isViolation) {
      post.setModerationStatus(ModerationStatus.REJECTED);
      postRepository.save(post);

      userBanService.recordViolation(
          post.getAuthorId(),
          post.getId(),
          ViolationType.HATE_SPEECH,
          "Admin manual review: " + Optional.ofNullable(feedback).orElse("content violation"));

      log.info("Admin rejected post {} (decision={})", postId, decision);
    } else {
      post.setModerationStatus(ModerationStatus.APPROVED);
      postRepository.save(post);
      newsfeedService.fanOutPost(postId);

      log.info("Admin approved post {} (decision={})", postId, decision);
    }

    saveModerationLog(postId, isViolation ? ModerationStatus.REJECTED : ModerationStatus.APPROVED);
  }

  private void saveModerationLog(Integer postId, ModerationStatus status) {
    ModerationLogEntity logEntity =
        ModerationLogEntity.builder()
            .postId(postId)
            .status(status)
            .reviewedAt(OffsetDateTime.now())
            .build();

    moderationLogRepository.save(logEntity);
  }

  private PendingReviewPostDto toPendingDto(PostEntity post) {
    String authorName =
        userRepository.findById(post.getAuthorId()).map(UserEntity::getFullName).orElse("Unknown");

    ModerationScores aiScores = getLatestScores(post.getId());

    return PendingReviewPostDto.builder()
        .postId(post.getId())
        .authorId(post.getAuthorId())
        .authorName(authorName)
        .content(post.getContent())
        .images(post.getImages())
        .currentStatus(post.getModerationStatus())
        .aiScores(aiScores)
        .createdAt(post.getCreatedAt())
        .build();
  }

  private ModerationScores getLatestScores(Integer postId) {
    List<ModerationLogEntity> logs = moderationLogRepository.findByPostId(postId);
    if (logs.isEmpty()) {
      return null;
    }

    ModerationLogEntity latest = logs.get(logs.size() - 1);
    return ModerationScores.builder()
        .toxicity(Optional.ofNullable(latest.getTextToxicityScore()).orElse(0.0))
        .imageSafeScore(Optional.ofNullable(latest.getImageSafeScore()).orElse(0.0))
        .build();
  }
}
