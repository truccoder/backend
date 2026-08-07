package com.socialapp.moderation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import com.socialapp.common.exception.NotFoundException;
import com.socialapp.common.exception.ValidationException;
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

/**
 * Component (unit) tests for {@link AdminModerationService}, per ISTQB CTFL v4.0.1 (Section 2.2.1
 * component testing, Section 5.1.6 test pyramid, Section 4.3.2 branch testing, Section 2.1.3 BDD
 * Given/When/Then) — see {@code PostServiceTest} for the full rationale.
 */
@ExtendWith(MockitoExtension.class)
class AdminModerationServiceTest {

  private static final Integer POST_ID = 100;
  private static final Integer AUTHOR_ID = 1;

  @Mock private PostRepository postRepository;
  @Mock private UserRepository userRepository;
  @Mock private ModerationLogRepository moderationLogRepository;
  @Mock private UserBanRepository userBanRepository;
  @Mock private NewsfeedService newsfeedService;
  @Mock private UserBanService userBanService;

  @InjectMocks private AdminModerationService adminModerationService;

  @Captor private ArgumentCaptor<PostEntity> postCaptor;
  @Captor private ArgumentCaptor<ModerationLogEntity> logCaptor;

  private static PostEntity post(Integer id, Integer authorId, ModerationStatus status) {
    PostEntity post = new PostEntity();
    post.setId(id);
    post.setAuthorId(authorId);
    post.setModerationStatus(status);
    return post;
  }

  private static UserEntity user(Integer id, OffsetDateTime bannedUntil) {
    UserEntity user = new UserEntity();
    user.setId(id);
    user.setEmail("user" + id + "@example.com");
    user.setFullName("User " + id);
    user.setBannedUntil(bannedUntil);
    return user;
  }

  // =====================================================================
  // searchPosts
  // =====================================================================

  @Nested
  @DisplayName("searchPosts")
  class SearchPostsTests {

    @Test
    @DisplayName("should map matching posts, resolving the author's name and moderation history")
    void shouldMapAuthorName_whenAuthorFound() {
      // Given
      PostEntity post = post(POST_ID, AUTHOR_ID, ModerationStatus.APPROVED);
      when(postRepository.search(any(), any(), any(), any()))
          .thenReturn(new PageImpl<>(List.of(post)));
      when(userRepository.findById(AUTHOR_ID)).thenReturn(Optional.of(user(AUTHOR_ID, null)));
      when(moderationLogRepository.findByPostIdOrderByCreatedAtAsc(POST_ID)).thenReturn(List.of());

      // When
      Page<PostModerationDetailDto> result =
          adminModerationService.searchPosts(
              POST_ID, AUTHOR_ID, ModerationStatus.APPROVED, PageRequest.of(0, 10));

      // Then
      assertThat(result.getContent().get(0).getAuthorName()).isEqualTo("User 1");
    }

    @Test
    @DisplayName("should use \"Unknown\" as the author name when the author no longer exists")
    void shouldUseUnknown_whenAuthorNotFound() {
      // Given
      PostEntity post = post(POST_ID, AUTHOR_ID, ModerationStatus.APPROVED);
      when(postRepository.search(any(), any(), any(), any()))
          .thenReturn(new PageImpl<>(List.of(post)));
      when(userRepository.findById(AUTHOR_ID)).thenReturn(Optional.empty());
      when(moderationLogRepository.findByPostIdOrderByCreatedAtAsc(POST_ID)).thenReturn(List.of());

      // When
      Page<PostModerationDetailDto> result =
          adminModerationService.searchPosts(POST_ID, AUTHOR_ID, null, PageRequest.of(0, 10));

      // Then
      assertThat(result.getContent().get(0).getAuthorName()).isEqualTo("Unknown");
    }
  }

  // =====================================================================
  // searchLogs
  // =====================================================================

  @Nested
  @DisplayName("searchLogs")
  class SearchLogsTests {

    @Test
    @DisplayName("should map matching moderation logs")
    void shouldMapLogs() {
      // Given
      ModerationLogEntity log =
          ModerationLogEntity.builder()
              .id(1L)
              .postId(POST_ID)
              .status(ModerationStatus.APPROVED)
              .build();
      when(moderationLogRepository.search(any(), any(), any(), any()))
          .thenReturn(new PageImpl<>(List.of(log)));

      // When
      Page<ModerationLogDto> result =
          adminModerationService.searchLogs(
              POST_ID, AUTHOR_ID, ModerationStatus.APPROVED, PageRequest.of(0, 10));

      // Then
      assertThat(result.getContent()).hasSize(1);
      assertThat(result.getContent().get(0).getPostId()).isEqualTo(POST_ID);
    }
  }

  // =====================================================================
  // getBannedUsers
  // =====================================================================

  @Nested
  @DisplayName("getBannedUsers")
  class GetBannedUsersTests {

    @Test
    @DisplayName("should compute the remaining ban time for a currently banned user")
    void shouldComputeRemainingSeconds_whenCurrentlyBanned() {
      // Given
      when(userBanRepository.findBannedUserIds(any()))
          .thenReturn(new PageImpl<>(List.of(AUTHOR_ID)));
      when(userRepository.findById(AUTHOR_ID))
          .thenReturn(Optional.of(user(AUTHOR_ID, OffsetDateTime.now().plusHours(1))));
      when(userBanRepository.findByUserIdOrderByCreatedAtDesc(AUTHOR_ID))
          .thenReturn(List.of(UserBanEntity.builder().userId(AUTHOR_ID).postId(POST_ID).build()));

      // When
      Page<BannedUserDto> result = adminModerationService.getBannedUsers(PageRequest.of(0, 10));

      // Then
      BannedUserDto dto = result.getContent().get(0);
      assertThat(dto.isCurrentlyBanned()).isTrue();
      assertThat(dto.getRemainingSeconds()).isGreaterThan(0);
      assertThat(dto.getTriggeringPostIds()).containsExactly(POST_ID);
    }

    @Test
    @DisplayName("should report zero remaining seconds when the user is not currently banned")
    void shouldReportZeroRemainingSeconds_whenNotBanned() {
      // Given
      when(userBanRepository.findBannedUserIds(any()))
          .thenReturn(new PageImpl<>(List.of(AUTHOR_ID)));
      when(userRepository.findById(AUTHOR_ID)).thenReturn(Optional.of(user(AUTHOR_ID, null)));
      when(userBanRepository.findByUserIdOrderByCreatedAtDesc(AUTHOR_ID)).thenReturn(List.of());

      // When
      Page<BannedUserDto> result = adminModerationService.getBannedUsers(PageRequest.of(0, 10));

      // Then
      BannedUserDto dto = result.getContent().get(0);
      assertThat(dto.isCurrentlyBanned()).isFalse();
      assertThat(dto.getRemainingSeconds()).isZero();
    }

    @Test
    @DisplayName("should reject when a banned user's account no longer exists")
    void shouldThrowNotFoundException_whenUserDeleted() {
      // Given
      when(userBanRepository.findBannedUserIds(any()))
          .thenReturn(new PageImpl<>(List.of(AUTHOR_ID)));
      when(userRepository.findById(AUTHOR_ID)).thenReturn(Optional.empty());

      // When / Then
      assertThatThrownBy(() -> adminModerationService.getBannedUsers(PageRequest.of(0, 10)))
          .isInstanceOf(NotFoundException.class);
    }
  }

  // =====================================================================
  // reviewPost
  // =====================================================================

  @Nested
  @DisplayName("reviewPost")
  class ReviewPostTests {

    @Test
    @DisplayName("should reject when the post does not exist")
    void shouldThrowNotFoundException_whenPostDoesNotExist() {
      // Given
      when(postRepository.findById(POST_ID)).thenReturn(Optional.empty());

      // When / Then
      assertThatThrownBy(
              () ->
                  adminModerationService.reviewPost(
                      POST_ID, Likelihood.LIKELY, ViolationType.SPAM, null))
          .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("should reject when the post is not in PENDING_REVIEW status")
    void shouldThrowIllegalStateException_whenNotPendingReview() {
      // Given
      when(postRepository.findById(POST_ID))
          .thenReturn(Optional.of(post(POST_ID, AUTHOR_ID, ModerationStatus.APPROVED)));

      // When / Then
      assertThatThrownBy(
              () ->
                  adminModerationService.reviewPost(
                      POST_ID, Likelihood.LIKELY, ViolationType.SPAM, null))
          .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName(
        "should reject the post and record a violation when the decision is at least LIKELY")
    void shouldRejectPost_whenDecisionIsViolation() {
      // Given
      PostEntity post = post(POST_ID, AUTHOR_ID, ModerationStatus.PENDING_REVIEW);
      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(post));

      // When
      adminModerationService.reviewPost(
          POST_ID, Likelihood.VERY_LIKELY, ViolationType.SEXUALLY_EXPLICIT, "explicit content");

      // Then
      verify(postRepository).save(postCaptor.capture());
      assertThat(postCaptor.getValue().getModerationStatus()).isEqualTo(ModerationStatus.REJECTED);
      // The type the admin picked, not HATE_SPEECH: this call used to hardcode HATE_SPEECH for
      // every rejection, which rated every takedown CRITICAL toward the 7-day ban and told the
      // user they had posted hate speech whatever they had actually done.
      verify(userBanService)
          .recordViolation(
              AUTHOR_ID,
              POST_ID,
              ViolationType.SEXUALLY_EXPLICIT,
              "Admin manual review: explicit content");
      verify(newsfeedService, never()).fanOutPost(any());
      verify(moderationLogRepository).save(logCaptor.capture());
      assertThat(logCaptor.getValue().getStatus()).isEqualTo(ModerationStatus.REJECTED);
      assertThat(logCaptor.getValue().getViolationType())
          .isEqualTo(ViolationType.SEXUALLY_EXPLICIT);
    }

    @Test
    @DisplayName("should refuse a rejection that names no violation type")
    void shouldRejectMissingViolationType() {
      // Given
      when(postRepository.findById(POST_ID))
          .thenReturn(Optional.of(post(POST_ID, AUTHOR_ID, ModerationStatus.PENDING_REVIEW)));

      // When / Then: refusing beats defaulting — a default constant is how the old bug worked
      assertThatThrownBy(
              () -> adminModerationService.reviewPost(POST_ID, Likelihood.LIKELY, null, null))
          .isInstanceOf(ValidationException.class);
      verify(userBanService, never()).recordViolation(any(), any(), any(), any());
    }

    @Test
    @DisplayName("should approve the post and fan it out when the decision is below LIKELY")
    void shouldApprovePost_whenDecisionIsNotViolation() {
      // Given
      PostEntity post = post(POST_ID, AUTHOR_ID, ModerationStatus.PENDING_REVIEW);
      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(post));

      // When
      adminModerationService.reviewPost(POST_ID, Likelihood.POSSIBLE, null, null);

      // Then
      verify(postRepository).save(postCaptor.capture());
      assertThat(postCaptor.getValue().getModerationStatus()).isEqualTo(ModerationStatus.APPROVED);
      verify(newsfeedService).fanOutPost(POST_ID);
      verify(userBanService, never()).recordViolation(any(), any(), any(), any());
      verify(moderationLogRepository).save(logCaptor.capture());
      assertThat(logCaptor.getValue().getStatus()).isEqualTo(ModerationStatus.APPROVED);
      assertThat(logCaptor.getValue().getViolationType()).isNull();
    }

    @Test
    @DisplayName("should default the violation description when no feedback is given")
    void shouldUseDefaultFeedback_whenFeedbackIsNull() {
      // Given
      PostEntity post = post(POST_ID, AUTHOR_ID, ModerationStatus.PENDING_REVIEW);
      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(post));

      // When
      adminModerationService.reviewPost(POST_ID, Likelihood.LIKELY, ViolationType.SPAM, null);

      // Then
      verify(userBanService)
          .recordViolation(
              AUTHOR_ID, POST_ID, ViolationType.SPAM, "Admin manual review: content violation");
    }
  }
}
