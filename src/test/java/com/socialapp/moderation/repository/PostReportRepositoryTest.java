package com.socialapp.moderation.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import com.socialapp.AbstractIntegrationTest;
import com.socialapp.moderation.entity.PostReportEntity;
import com.socialapp.moderation.enums.ReportReason;
import com.socialapp.posts.entity.PostEntity;
import com.socialapp.posts.repository.PostRepository;
import com.socialapp.security.entity.UserEntity;
import com.socialapp.security.repository.UserRepository;

/**
 * Component integration tests for {@link PostReportRepository} against a real PostgreSQL instance
 * (Testcontainers), per ISTQB CTFL v4.0.1 Section 2.2.1. Each test rolls back its own transaction.
 *
 * <p><b>The unique constraint is the reason this class exists.</b> {@code
 * uq_post_reports_post_reporter} is what turns "number of reports" into "number of people", and the
 * escalation threshold that hides a post from every reader is compared against that number. A
 * service-level mock cannot tell whether the constraint is really in the schema — only running
 * {@code V62__create_post_reports.sql} against Postgres can, which is what happens here.
 */
@Transactional
class PostReportRepositoryTest extends AbstractIntegrationTest {

  @Autowired private PostReportRepository reportRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private PostRepository postRepository;

  private Integer authorId;
  private Integer reporterId;
  private Integer otherReporterId;
  private Integer postId;

  @BeforeEach
  void seedPeopleAndPost() {
    authorId = userRepository.saveAndFlush(user("author@example.com", "report_author")).getId();
    reporterId = userRepository.saveAndFlush(user("r1@example.com", "report_one")).getId();
    otherReporterId = userRepository.saveAndFlush(user("r2@example.com", "report_two")).getId();
    postId = postRepository.saveAndFlush(post(authorId)).getId();
  }

  private static UserEntity user(String email, String username) {
    UserEntity user = new UserEntity();
    user.setEmail(email);
    user.setPassword("hashed-password");
    user.setUsername(username);
    user.setFullName("Test User");
    return user;
  }

  private static PostEntity post(Integer authorId) {
    PostEntity post = new PostEntity();
    post.setContent("post content");
    post.setAuthorId(authorId);
    return post;
  }

  private PostReportEntity report(Integer reporterId, ReportReason reason) {
    return PostReportEntity.builder()
        .postId(postId)
        .reporterId(reporterId)
        .reason(reason)
        .details("why")
        .build();
  }

  @Nested
  @DisplayName("uq_post_reports_post_reporter")
  class UniqueConstraint {

    @Test
    @DisplayName("refuses a second report on the same post by the same person")
    void refusesDuplicateReporter() {
      // Given
      reportRepository.saveAndFlush(report(reporterId, ReportReason.SPAM));

      // When / Then — without this, one person clicking ten times clears the escalation threshold
      // alone and reporting becomes a takedown button
      assertThatThrownBy(
              () -> reportRepository.saveAndFlush(report(reporterId, ReportReason.HARASSMENT)))
          .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("allows two different people to report the same post")
    void allowsDistinctReporters() {
      // Given / When
      reportRepository.saveAndFlush(report(reporterId, ReportReason.SPAM));
      reportRepository.saveAndFlush(report(otherReporterId, ReportReason.SPAM));

      // Then
      assertThat(reportRepository.countDistinctReporters(postId)).isEqualTo(2);
    }
  }

  @Nested
  @DisplayName("existsByPostIdAndReporterId")
  class Exists {

    @Test
    @DisplayName("is true once that person has reported that post")
    void trueAfterReporting() {
      // Given
      reportRepository.saveAndFlush(report(reporterId, ReportReason.VIOLENCE));

      // When / Then
      assertThat(reportRepository.existsByPostIdAndReporterId(postId, reporterId)).isTrue();
    }

    @Test
    @DisplayName("is false for somebody who has not reported it")
    void falseForOtherPerson() {
      // Given
      reportRepository.saveAndFlush(report(reporterId, ReportReason.VIOLENCE));

      // When / Then
      assertThat(reportRepository.existsByPostIdAndReporterId(postId, otherReporterId)).isFalse();
    }
  }

  @Nested
  @DisplayName("countDistinctReporters")
  class CountReporters {

    @Test
    @DisplayName("is zero for a post nobody has reported")
    void zeroWhenUnreported() {
      // When / Then — BVA: the empty case, which must not read as "threshold reached"
      assertThat(reportRepository.countDistinctReporters(postId)).isZero();
    }

    @Test
    @DisplayName("counts only reports on the post it was asked about")
    void scopedToOnePost() {
      // Given
      Integer otherPostId = postRepository.saveAndFlush(post(authorId)).getId();
      reportRepository.saveAndFlush(report(reporterId, ReportReason.SPAM));
      reportRepository.saveAndFlush(
          PostReportEntity.builder()
              .postId(otherPostId)
              .reporterId(otherReporterId)
              .reason(ReportReason.SPAM)
              .build());

      // When / Then
      assertThat(reportRepository.countDistinctReporters(postId)).isEqualTo(1);
    }
  }

  @Nested
  @DisplayName("findByPostIdOrderByCreatedAtDesc")
  class QueueReads {

    @Test
    @DisplayName("returns the reports filed against one post")
    void returnsReportsForPost() {
      // Given
      reportRepository.saveAndFlush(report(reporterId, ReportReason.MISINFORMATION));

      // When
      Page<PostReportEntity> page =
          reportRepository.findByPostIdOrderByCreatedAtDesc(postId, PageRequest.of(0, 10));

      // Then
      assertThat(page.getContent())
          .extracting(PostReportEntity::getReason)
          .containsExactly(ReportReason.MISINFORMATION);
    }

    @Test
    @DisplayName("keeps the reason and details it was filed with")
    void roundTripsReasonAndDetails() {
      // Given — reason is stored as the enum name through @Enumerated(STRING); an ordinal would
      // silently repoint every stored row the day a constant is inserted in the middle
      reportRepository.saveAndFlush(report(reporterId, ReportReason.ADULT_CONTENT));

      // When
      PostReportEntity stored =
          reportRepository
              .findByPostIdOrderByCreatedAtDesc(postId, PageRequest.of(0, 10))
              .getContent()
              .get(0);

      // Then
      assertThat(stored.getReason()).isEqualTo(ReportReason.ADULT_CONTENT);
      assertThat(stored.getDetails()).isEqualTo("why");
      assertThat(stored.getCreatedAt()).isNotNull();
    }
  }
}
