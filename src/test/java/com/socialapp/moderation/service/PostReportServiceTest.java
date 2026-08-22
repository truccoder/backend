package com.socialapp.moderation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import com.socialapp.common.exception.NotFoundException;
import com.socialapp.common.exception.ValidationException;
import com.socialapp.moderation.dto.CreatePostReportRequestDto;
import com.socialapp.moderation.entity.ModerationLogEntity;
import com.socialapp.moderation.entity.PostReportEntity;
import com.socialapp.moderation.enums.ModerationStatus;
import com.socialapp.moderation.enums.ReportReason;
import com.socialapp.moderation.repository.ModerationLogRepository;
import com.socialapp.moderation.repository.PostReportRepository;
import com.socialapp.newsfeed.service.NewsfeedService;
import com.socialapp.posts.entity.PostEntity;
import com.socialapp.posts.repository.PostRepository;

/**
 * Component (unit) tests for {@link PostReportService}, per ISTQB CTFL v4.0.1 (Section 2.2.1
 * component testing, Section 4.2.2 boundary value analysis on the escalation threshold, Section
 * 4.3.2 branch testing over the moderation-status guard, Section 2.1.3 BDD Given/When/Then).
 *
 * <p><b>The threshold is the security property here, not a tuning knob.</b> A post that reaches
 * {@code PENDING_REVIEW} is hidden from everyone but its author, so "how many reports does it take"
 * is the same question as "how many accounts does it take to hide someone's post". The boundary
 * cases below (threshold − 1, threshold, threshold + 1) are what stop that from drifting to one.
 */
@ExtendWith(MockitoExtension.class)
class PostReportServiceTest {

  private static final Integer POST_ID = 5001;
  private static final Integer AUTHOR_ID = 9001;
  private static final Integer REPORTER_ID = 9002;
  private static final int THRESHOLD = 3;

  @Mock private PostReportRepository reportRepository;
  @Mock private PostRepository postRepository;
  @Mock private ModerationLogRepository moderationLogRepository;
  @Mock private NewsfeedService newsfeedService;

  @InjectMocks private PostReportService service;

  @Captor private ArgumentCaptor<PostReportEntity> reportCaptor;
  @Captor private ArgumentCaptor<ModerationLogEntity> logCaptor;

  @BeforeEach
  void setThreshold() {
    // @Value is not applied outside a Spring context, so the field would otherwise be 0 and every
    // single report would escalate — the exact behaviour these tests exist to rule out.
    ReflectionTestUtils.setField(service, "escalationThreshold", THRESHOLD);
  }

  private static PostEntity post(ModerationStatus status) {
    PostEntity post = new PostEntity();
    post.setId(POST_ID);
    post.setAuthorId(AUTHOR_ID);
    post.setModerationStatus(status);
    return post;
  }

  private static CreatePostReportRequestDto request() {
    return new CreatePostReportRequestDto(POST_ID, ReportReason.SPAM, "buy now buy now");
  }

  @Nested
  @DisplayName("report — rejection paths")
  class RejectionTests {

    @Test
    @DisplayName("should throw NotFound when the post does not exist")
    void shouldThrowWhenPostMissing() {
      // Given
      when(postRepository.findById(POST_ID)).thenReturn(Optional.empty());

      // When / Then
      assertThatThrownBy(() -> service.report(REPORTER_ID, request()))
          .isInstanceOf(NotFoundException.class);
      verifyNoInteractions(reportRepository);
    }

    @Test
    @DisplayName("should refuse a report filed by the post's own author")
    void shouldRefuseSelfReport() {
      // Given — otherwise an author could push their own post a third of the way to the queue
      when(postRepository.findById(POST_ID))
          .thenReturn(Optional.of(post(ModerationStatus.APPROVED)));

      // When / Then
      assertThatThrownBy(() -> service.report(AUTHOR_ID, request()))
          .isInstanceOf(ValidationException.class);
      verify(reportRepository, never()).saveAndFlush(any());
    }
  }

  @Nested
  @DisplayName("report — idempotence")
  class IdempotenceTests {

    @Test
    @DisplayName("should accept a repeat report from the same person and store nothing new")
    void shouldBeIdempotentPerReporter() {
      // Given
      when(postRepository.findById(POST_ID))
          .thenReturn(Optional.of(post(ModerationStatus.APPROVED)));
      when(reportRepository.existsByPostIdAndReporterId(POST_ID, REPORTER_ID)).thenReturn(true);

      // When
      service.report(REPORTER_ID, request());

      // Then — a 409 would confirm to the caller that an earlier report exists, which is a fact
      // about the queue they are not entitled to
      verify(reportRepository, never()).saveAndFlush(any());
      verify(reportRepository, never()).countDistinctReporters(anyInt());
    }

    @Test
    @DisplayName("should treat a lost insert race as the repeat it is")
    void shouldSwallowConstraintViolation() {
      // Given — two clicks slipping past the exists() check together; the unique constraint is
      // the real guard and one of them has to lose
      when(postRepository.findById(POST_ID))
          .thenReturn(Optional.of(post(ModerationStatus.APPROVED)));
      when(reportRepository.existsByPostIdAndReporterId(POST_ID, REPORTER_ID)).thenReturn(false);
      when(reportRepository.saveAndFlush(any()))
          .thenThrow(new DataIntegrityViolationException("uq_post_reports_post_reporter"));

      // When / Then — the loser does not escalate and does not fail the request
      service.report(REPORTER_ID, request());
      verify(reportRepository, never()).countDistinctReporters(anyInt());
    }
  }

  @Nested
  @DisplayName("report — escalation threshold")
  class EscalationTests {

    private void givenApprovedPostAndFirstReport() {
      when(postRepository.findById(POST_ID))
          .thenReturn(Optional.of(post(ModerationStatus.APPROVED)));
      when(reportRepository.existsByPostIdAndReporterId(POST_ID, REPORTER_ID)).thenReturn(false);
    }

    @Test
    @DisplayName("should store the report with the reason and details it was filed with")
    void shouldStoreReport() {
      // Given
      givenApprovedPostAndFirstReport();
      when(reportRepository.countDistinctReporters(POST_ID)).thenReturn(1L);

      // When
      service.report(REPORTER_ID, request());

      // Then
      verify(reportRepository).saveAndFlush(reportCaptor.capture());
      assertThat(reportCaptor.getValue().getPostId()).isEqualTo(POST_ID);
      assertThat(reportCaptor.getValue().getReporterId()).isEqualTo(REPORTER_ID);
      assertThat(reportCaptor.getValue().getReason()).isEqualTo(ReportReason.SPAM);
      assertThat(reportCaptor.getValue().getDetails()).isEqualTo("buy now buy now");
    }

    @Test
    @DisplayName("should not escalate one report below the threshold (boundary: threshold - 1)")
    void shouldNotEscalateBelowThreshold() {
      // Given — BVA: two distinct reporters, threshold three
      givenApprovedPostAndFirstReport();
      when(reportRepository.countDistinctReporters(POST_ID)).thenReturn((long) THRESHOLD - 1);

      // When
      service.report(REPORTER_ID, request());

      // Then — the post stays visible; this is what keeps reporting from being a takedown button
      verify(postRepository, never()).save(any());
      verifyNoInteractions(newsfeedService);
      verifyNoInteractions(moderationLogRepository);
    }

    @Test
    @DisplayName("should escalate exactly at the threshold (boundary: threshold)")
    void shouldEscalateAtThreshold() {
      // Given
      PostEntity post = post(ModerationStatus.APPROVED);
      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(post));
      when(reportRepository.existsByPostIdAndReporterId(POST_ID, REPORTER_ID)).thenReturn(false);
      when(reportRepository.countDistinctReporters(POST_ID)).thenReturn((long) THRESHOLD);

      // When
      service.report(REPORTER_ID, request());

      // Then
      assertThat(post.getModerationStatus()).isEqualTo(ModerationStatus.PENDING_REVIEW);
      verify(postRepository).save(post);
    }

    @Test
    @DisplayName("should pull the post out of the fan-out feed as well as flipping its status")
    void shouldRemoveFromFeedOnEscalation() {
      // Given — two stores answer "may I see this": Postgres via the status, Redis via the
      // cached fan-out list. The feed never consults the database, so flipping only the status
      // hides the post on its permalink while it keeps scrolling past in every follower's feed.
      PostEntity post = post(ModerationStatus.APPROVED);
      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(post));
      when(reportRepository.existsByPostIdAndReporterId(POST_ID, REPORTER_ID)).thenReturn(false);
      when(reportRepository.countDistinctReporters(POST_ID)).thenReturn((long) THRESHOLD);

      // When
      service.report(REPORTER_ID, request());

      // Then
      verify(newsfeedService).removePost(POST_ID, AUTHOR_ID, List.of());
    }

    @Test
    @DisplayName("should record why the post is in the queue, without recording a decision")
    void shouldWriteReferralLog() {
      // Given
      givenApprovedPostAndFirstReport();
      when(reportRepository.countDistinctReporters(POST_ID)).thenReturn((long) THRESHOLD);

      // When
      service.report(REPORTER_ID, request());

      // Then — reviewedAt stays null: nobody has reviewed anything, this row is the referral
      verify(moderationLogRepository).save(logCaptor.capture());
      assertThat(logCaptor.getValue().getStatus()).isEqualTo(ModerationStatus.PENDING_REVIEW);
      assertThat(logCaptor.getValue().getReviewedAt()).isNull();
      assertThat(logCaptor.getValue().getViolationType()).isNull();
      assertThat(logCaptor.getValue().getRuleViolations().get(0)).contains("USER_REPORTS");
    }

    @Test
    @DisplayName("should not re-escalate a post already awaiting review (boundary: threshold + 1)")
    void shouldNotReEscalatePendingReview() {
      // Given — BVA above the threshold, on a post that is already where escalation puts it
      when(postRepository.findById(POST_ID))
          .thenReturn(Optional.of(post(ModerationStatus.PENDING_REVIEW)));
      when(reportRepository.existsByPostIdAndReporterId(POST_ID, REPORTER_ID)).thenReturn(false);

      // When
      service.report(REPORTER_ID, request());

      // Then — the report is still filed; only the escalation is skipped
      verify(reportRepository).saveAndFlush(any());
      verify(postRepository, never()).save(any());
      verifyNoInteractions(newsfeedService);
    }

    @Test
    @DisplayName("should never drag a rejected post back into the queue")
    void shouldNotEscalateRejectedPost() {
      // Given — a moderator has already ruled; a crowd must not overwrite that
      when(postRepository.findById(POST_ID))
          .thenReturn(Optional.of(post(ModerationStatus.REJECTED)));
      when(reportRepository.existsByPostIdAndReporterId(POST_ID, REPORTER_ID)).thenReturn(false);

      // When
      service.report(REPORTER_ID, request());

      // Then
      verify(postRepository, never()).save(any());
      verify(reportRepository, never()).countDistinctReporters(anyInt());
    }
  }

  @Nested
  @DisplayName("getReports")
  class GetReportsTests {

    @Test
    @DisplayName("should read the whole queue when no post is named")
    void shouldReadWholeQueue() {
      // Given
      PostReportEntity entity =
          PostReportEntity.builder()
              .id(1)
              .postId(POST_ID)
              .reporterId(REPORTER_ID)
              .reason(ReportReason.HARASSMENT)
              .build();
      when(reportRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(0, 10)))
          .thenReturn(new PageImpl<>(List.of(entity)));

      // When
      var page = service.getReports(null, PageRequest.of(0, 10));

      // Then
      assertThat(page.getContent()).hasSize(1);
      assertThat(page.getContent().get(0).getReporterId()).isEqualTo(REPORTER_ID);
    }

    @Test
    @DisplayName("should narrow to one post when a post id is given")
    void shouldNarrowToOnePost() {
      // Given — the natural read when an admin opens the post reports put in front of them
      when(reportRepository.findByPostIdOrderByCreatedAtDesc(POST_ID, PageRequest.of(0, 10)))
          .thenReturn(new PageImpl<>(List.of()));

      // When
      var page = service.getReports(POST_ID, PageRequest.of(0, 10));

      // Then
      assertThat(page.getContent()).isEmpty();
      verify(reportRepository, never()).findAllByOrderByCreatedAtDesc(any());
    }
  }
}
