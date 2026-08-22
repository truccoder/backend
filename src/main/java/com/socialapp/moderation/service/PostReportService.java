package com.socialapp.moderation.service;

import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialapp.common.exception.NotFoundException;
import com.socialapp.common.exception.ValidationException;
import com.socialapp.moderation.dto.CreatePostReportRequestDto;
import com.socialapp.moderation.dto.PostReportDto;
import com.socialapp.moderation.entity.ModerationLogEntity;
import com.socialapp.moderation.entity.PostReportEntity;
import com.socialapp.moderation.enums.ModerationStatus;
import com.socialapp.moderation.repository.ModerationLogRepository;
import com.socialapp.moderation.repository.PostReportRepository;
import com.socialapp.newsfeed.service.NewsfeedService;
import com.socialapp.posts.entity.PostEntity;
import com.socialapp.posts.entity.PostTagEntity;
import com.socialapp.posts.repository.PostRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * The missing direction of moderation: content going <i>into</i> the review queue because a person
 * put it there.
 *
 * <p>Until this existed the queue had exactly one producer — the AI scorer that runs at publish
 * time. Everything it missed stayed missed, because there was no user-facing way to say so. ({@code
 * AppealService} is the opposite direction: disputing a decision already made.)
 *
 * <p><b>One report does not take a post down; the third distinct reporter escalates it.</b> A
 * single report moving a post to {@code PENDING_REVIEW} would make every account a takedown button,
 * because {@code PostVisibilityService} hides anything that is not {@code APPROVED} from everyone
 * but its author. A threshold, plus the unique {@code (post_id, reporter_id)} constraint that makes
 * the count a count of people, means the button takes three separate accounts to press. That is
 * still a trade — three coordinating accounts can hide a post until a moderator looks at it — and
 * the answer to that is the moderator, not a larger number.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PostReportService {

  private final PostReportRepository reportRepository;
  private final PostRepository postRepository;
  private final ModerationLogRepository moderationLogRepository;
  private final NewsfeedService newsfeedService;

  /**
   * How many distinct people have to report a post before it is pulled for review.
   *
   * <p>Configurable so an environment with a handful of accounts — a demo, a dev machine — can
   * lower it without a code change, and a production instance can raise it as the user base grows.
   */
  @Value("${moderation.reports.escalation-threshold:3}")
  private int escalationThreshold;

  /**
   * Files one report.
   *
   * <p>Idempotent per (post, reporter): a second report from the same person is accepted and
   * changes nothing. The alternative — a 409 — would confirm to the caller that an earlier report
   * exists, which is a fact about the queue, and would give an automated client a way to enumerate
   * what has already been reported.
   */
  @Transactional
  public void report(Integer reporterId, CreatePostReportRequestDto request) {
    PostEntity post =
        postRepository
            .findById(request.getPostId())
            .orElseThrow(() -> new NotFoundException("Post not found: " + request.getPostId()));

    // Reporting yourself is not a moderation signal, and counting it would let an author push their
    // own post a third of the way into the queue on their own.
    if (post.getAuthorId().equals(reporterId)) {
      throw new ValidationException("You cannot report your own post");
    }

    if (reportRepository.existsByPostIdAndReporterId(post.getId(), reporterId)) {
      return;
    }

    try {
      reportRepository.saveAndFlush(
          PostReportEntity.builder()
              .postId(post.getId())
              .reporterId(reporterId)
              .reason(request.getReason())
              .details(request.getDetails())
              .build());
    } catch (DataIntegrityViolationException e) {
      // Two clicks racing each other past the exists() check above. The unique constraint is the
      // real guard; this branch only makes the loser of the race look like the idempotent repeat
      // that it is.
      log.debug("Duplicate report for post {} by user {}", post.getId(), reporterId);
      return;
    }

    escalateIfEnoughReporters(post);
  }

  /**
   * Moves a reported post into the human queue once enough separate people have flagged it.
   *
   * <p>Only ever escalates an {@code APPROVED} post. One already in {@code PENDING_REVIEW} is
   * already where this would put it; one that is {@code REJECTED} or {@code PENDING_MODERATION} has
   * either been ruled on or has not been served to anyone yet, and dragging either backwards would
   * let a crowd overwrite a decision.
   *
   * <p>The post is pulled out of Redis as well as flipped in Postgres. Those are two different
   * stores answering the same question: the status hides the post everywhere it is read from the
   * database, but the fan-out feed never consults the database — it serves whatever was cached at
   * publish time. Flipping only the status would leave the post hidden on its own permalink and
   * still scrolling past in every follower's feed.
   */
  private void escalateIfEnoughReporters(PostEntity post) {
    if (!ModerationStatus.APPROVED.equals(post.getModerationStatus())) {
      return;
    }

    long reporters = reportRepository.countDistinctReporters(post.getId());
    if (reporters < escalationThreshold) {
      return;
    }

    post.setModerationStatus(ModerationStatus.PENDING_REVIEW);
    postRepository.save(post);

    newsfeedService.removePost(post.getId(), post.getAuthorId(), taggedUserIds(post));

    // So the admin who opens the post sees why it is in front of them. reviewedAt stays null —
    // nobody has reviewed anything yet; this row records the referral, not a decision.
    moderationLogRepository.save(
        ModerationLogEntity.builder()
            .postId(post.getId())
            .status(ModerationStatus.PENDING_REVIEW)
            .ruleViolations(List.of("USER_REPORTS: " + reporters + " distinct reporters"))
            .createdAt(OffsetDateTime.now())
            .build());

    log.info("Post {} escalated to review after {} distinct reports", post.getId(), reporters);
  }

  private List<Integer> taggedUserIds(PostEntity post) {
    return post.getTags() == null
        ? List.of()
        : post.getTags().stream().map(PostTagEntity::getTaggedUserId).toList();
  }

  /** The report queue, newest first — admin-only, see {@link PostReportDto}. */
  @Transactional(readOnly = true)
  public Page<PostReportDto> getReports(Integer postId, Pageable pageable) {
    Page<PostReportEntity> page =
        postId == null
            ? reportRepository.findAllByOrderByCreatedAtDesc(pageable)
            : reportRepository.findByPostIdOrderByCreatedAtDesc(postId, pageable);
    return page.map(PostReportDto::from);
  }
}
