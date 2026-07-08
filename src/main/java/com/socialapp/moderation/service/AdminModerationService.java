package com.socialapp.moderation.service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialapp.common.exception.NotFoundException;
import com.socialapp.moderation.dto.BannedUserDto;
import com.socialapp.moderation.dto.ModerationLogDto;
import com.socialapp.moderation.dto.PostModerationDetailDto;
import com.socialapp.moderation.entity.ModerationLogEntity;
import com.socialapp.moderation.entity.UserBanEntity;
import com.socialapp.moderation.enums.Likelihood;
import com.socialapp.moderation.enums.ModerationStatus;
import com.socialapp.moderation.enums.ViolationType;
import com.socialapp.moderation.repository.ModerationLogRepository;
import com.socialapp.moderation.repository.UserBanRepository;
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
  private final UserBanRepository userBanRepository;
  private final NewsfeedService newsfeedService;
  private final UserBanService userBanService;

  public Page<PostModerationDetailDto> searchPosts(
      Integer postId, Integer authorId, ModerationStatus status, Pageable pageable) {
    return postRepository.search(postId, authorId, status, pageable).map(this::toDetailDto);
  }

  public Page<ModerationLogDto> searchLogs(
      Integer postId, Integer authorId, ModerationStatus status, Pageable pageable) {
    return moderationLogRepository.search(postId, authorId, status, pageable).map(this::toLogDto);
  }

  public Page<BannedUserDto> getBannedUsers(Pageable pageable) {
    Page<Integer> userIds = userBanRepository.findBannedUserIds(pageable);
    List<BannedUserDto> dtos = userIds.getContent().stream().map(this::toBannedUserDto).toList();
    return new PageImpl<>(dtos, pageable, userIds.getTotalElements());
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

    saveModerationLog(
        postId,
        isViolation ? ModerationStatus.REJECTED : ModerationStatus.APPROVED,
        isViolation ? ViolationType.HATE_SPEECH : null);
  }

  private void saveModerationLog(
      Integer postId, ModerationStatus status, ViolationType violationType) {
    ModerationLogEntity logEntity =
        ModerationLogEntity.builder()
            .postId(postId)
            .status(status)
            .violationType(violationType)
            .reviewedAt(OffsetDateTime.now())
            .build();

    moderationLogRepository.save(logEntity);
  }

  private PostModerationDetailDto toDetailDto(PostEntity post) {
    String authorName =
        userRepository.findById(post.getAuthorId()).map(UserEntity::getFullName).orElse("Unknown");

    List<ModerationLogDto> history =
        moderationLogRepository.findByPostIdOrderByCreatedAtAsc(post.getId()).stream()
            .map(this::toLogDto)
            .toList();

    return PostModerationDetailDto.builder()
        .postId(post.getId())
        .authorId(post.getAuthorId())
        .authorName(authorName)
        .content(post.getContent())
        .images(post.getImages())
        .currentStatus(post.getModerationStatus())
        .createdAt(post.getCreatedAt())
        .updatedAt(post.getUpdatedAt())
        .history(history)
        .build();
  }

  private ModerationLogDto toLogDto(ModerationLogEntity log) {
    return ModerationLogDto.builder()
        .id(log.getId())
        .postId(log.getPostId())
        .status(log.getStatus())
        .violationType(log.getViolationType())
        .textToxicityScore(log.getTextToxicityScore())
        .imageSafeScore(log.getImageSafeScore())
        .ruleViolations(log.getRuleViolations())
        .reviewedAt(log.getReviewedAt())
        .createdAt(log.getCreatedAt())
        .build();
  }

  private BannedUserDto toBannedUserDto(Integer userId) {
    UserEntity user =
        userRepository
            .findById(userId)
            .orElseThrow(() -> new NotFoundException("User not found: " + userId));

    List<UserBanEntity> bans = userBanRepository.findByUserIdOrderByCreatedAtDesc(userId);
    List<Integer> triggeringPostIds =
        bans.stream().map(UserBanEntity::getPostId).filter(Objects::nonNull).distinct().toList();

    long remainingSeconds =
        user.isBanned()
            ? Duration.between(OffsetDateTime.now(), user.getBannedUntil()).getSeconds()
            : 0;

    return BannedUserDto.builder()
        .userId(user.getId())
        .email(user.getEmail())
        .fullName(user.getFullName())
        .currentlyBanned(user.isBanned())
        .bannedUntil(user.getBannedUntil())
        .remainingSeconds(remainingSeconds)
        .banCount(bans.size())
        .triggeringPostIds(triggeringPostIds)
        .build();
  }
}
