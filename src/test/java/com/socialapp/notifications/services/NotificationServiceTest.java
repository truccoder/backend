package com.socialapp.notifications.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
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
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import com.socialapp.blocks.service.BlockQueryService;
import com.socialapp.notifications.dto.NotificationPreferenceResponseDto;
import com.socialapp.notifications.dto.SendNotificationRequest;
import com.socialapp.notifications.dto.UpdatePreferenceRequestDto;
import com.socialapp.notifications.entity.NotificationEntity;
import com.socialapp.notifications.entity.NotificationPreferenceEntity;
import com.socialapp.notifications.entity.enums.EmailFrequency;
import com.socialapp.notifications.entity.enums.NotificationChannel;
import com.socialapp.notifications.entity.enums.NotificationType;
import com.socialapp.notifications.repository.NotificationPreferenceRepository;
import com.socialapp.notifications.repository.NotificationRepository;
import com.socialapp.notifications.sse.NotificationStreamService;
import com.socialapp.security.entity.UserEntity;
import com.socialapp.security.repository.UserRepository;

/**
 * Component (unit) tests for {@link NotificationService}, per ISTQB CTFL v4.0.1 (Section 2.2.1
 * component testing, Section 5.1.6 test pyramid, Section 4.3.2 branch testing, Section 2.1.3 BDD
 * Given/When/Then) — see {@code PostServiceTest} for the full rationale.
 */
@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

  private static final Integer RECIPIENT_ID = 1;
  private static final Integer ACTOR_ID = 2;

  @Mock private NotificationRepository notificationRepository;
  @Mock private NotificationPreferenceRepository preferenceRepository;
  @Mock private PushNotificationService pushService;
  @Mock private MailService mailService;
  @Mock private UserRepository userRepository;
  @Mock private BlockQueryService blockQueryService;
  @Mock private NotificationStreamService streamService;

  @InjectMocks private NotificationService notificationService;

  @Captor private ArgumentCaptor<NotificationEntity> entityCaptor;

  private static NotificationPreferenceEntity preference(
      Boolean pushEnabled, Boolean emailEnabled, String playerId, List<String> mutedTypes) {
    return NotificationPreferenceEntity.builder()
        .userId(RECIPIENT_ID)
        .pushEnabled(pushEnabled)
        .emailEnabled(emailEnabled)
        .onesignalPlayerId(playerId)
        .mutedTypes(mutedTypes)
        .build();
  }

  private static SendNotificationRequest.SendNotificationRequestBuilder baseRequest(
      NotificationChannel channel) {
    return SendNotificationRequest.builder()
        .recipientId(RECIPIENT_ID)
        .actorId(ACTOR_ID)
        .type(NotificationType.FRIEND_REQUEST)
        .title("Title")
        .body("Body")
        .channel(channel);
  }

  private static UserEntity user(Integer id, String email, String fullName) {
    UserEntity user = new UserEntity();
    user.setId(id);
    user.setEmail(email);
    user.setFullName(fullName);
    return user;
  }

  // =====================================================================
  // send
  // =====================================================================

  @Nested
  @DisplayName("send")
  class SendTests {

    @Test
    @DisplayName("should skip everything when the notification type is muted")
    void shouldSkipEverything_whenTypeIsMuted() {
      // Given
      NotificationPreferenceEntity prefs =
          preference(true, true, "player-1", List.of("FRIEND_REQUEST"));
      when(preferenceRepository.findByUserId(RECIPIENT_ID)).thenReturn(Optional.of(prefs));

      // When
      notificationService.send(baseRequest(NotificationChannel.BOTH).build());

      // Then
      verify(notificationRepository, never()).save(any());
      verify(pushService, never()).sendToPlayer(any(), any(), any(), any());
      verify(mailService, never()).sendNotificationEmail(any(), any(), any(), any());
    }

    @Test
    @DisplayName("should proceed when mutedTypes is null")
    void shouldProceed_whenMutedTypesIsNull() {
      // Given
      NotificationPreferenceEntity prefs = preference(false, false, null, null);
      when(preferenceRepository.findByUserId(RECIPIENT_ID)).thenReturn(Optional.of(prefs));
      when(notificationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

      // When
      notificationService.send(baseRequest(NotificationChannel.BOTH).build());

      // Then — saved once inside saveNotification() and again at the end of send() to persist
      // sentAt, so both invocations are expected here.
      verify(notificationRepository, times(2)).save(any());
    }

    @Test
    @DisplayName("should proceed when mutedTypes does not contain this type")
    void shouldProceed_whenMutedTypesDoesNotContainType() {
      // Given
      NotificationPreferenceEntity prefs = preference(false, false, null, List.of("OTHER_TYPE"));
      when(preferenceRepository.findByUserId(RECIPIENT_ID)).thenReturn(Optional.of(prefs));
      when(notificationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

      // When
      notificationService.send(baseRequest(NotificationChannel.BOTH).build());

      // Then — saved once inside saveNotification() and again at the end of send() to persist
      // sentAt, so both invocations are expected here.
      verify(notificationRepository, times(2)).save(any());
    }

    @Test
    @DisplayName("should persist the post a COMMENT reference lives under")
    void shouldPersistPostId_whenReferenceIsAComment() {
      // Given — a notification about a comment. referenceId is deliberately the comment id, so
      // that a client opens the thread at the reply in question rather than the top of the page —
      // but no client route is keyed by a comment id, so on its own it addresses nothing.
      NotificationPreferenceEntity prefs = preference(false, false, null, null);
      when(preferenceRepository.findByUserId(RECIPIENT_ID)).thenReturn(Optional.of(prefs));
      when(notificationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

      // When
      notificationService.send(
          baseRequest(NotificationChannel.BOTH)
              .type(NotificationType.USER_MENTIONED)
              .referenceId(88)
              .referenceType("COMMENT")
              .postId(500)
              .build());

      // Then — written at send time rather than resolved on read: the emitting side already holds
      // the post, while the read path maps a whole page outside a transaction
      verify(notificationRepository, times(2)).save(entityCaptor.capture());
      assertThat(entityCaptor.getValue().getReferenceId()).isEqualTo(88);
      assertThat(entityCaptor.getValue().getReferenceType()).isEqualTo("COMMENT");
      assertThat(entityCaptor.getValue().getPostId()).isEqualTo(500);
    }

    @Test
    @DisplayName("should leave the post null for a reference that is not a comment")
    void shouldLeavePostIdNull_whenReferenceIsNotAComment() {
      // Given — a friend request has no post, and NULL is the correct state rather than missing
      // data. The response DTO drops the key entirely in this case.
      NotificationPreferenceEntity prefs = preference(false, false, null, null);
      when(preferenceRepository.findByUserId(RECIPIENT_ID)).thenReturn(Optional.of(prefs));
      when(notificationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

      // When
      notificationService.send(baseRequest(NotificationChannel.BOTH).build());

      // Then
      verify(notificationRepository, times(2)).save(entityCaptor.capture());
      assertThat(entityCaptor.getValue().getPostId()).isNull();
    }

    @Test
    @DisplayName("should send a push notification when channel is PUSH, enabled, with a player id")
    void shouldSendPush_whenChannelIsPush() {
      // Given
      NotificationPreferenceEntity prefs = preference(true, false, "player-1", null);
      when(preferenceRepository.findByUserId(RECIPIENT_ID)).thenReturn(Optional.of(prefs));
      when(notificationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

      // When
      notificationService.send(baseRequest(NotificationChannel.PUSH).build());

      // Then
      verify(pushService).sendToPlayer(eq("player-1"), eq("Title"), eq("Body"), anyMap());
      verify(notificationRepository, times(2)).save(entityCaptor.capture());
      assertThat(entityCaptor.getValue().getSentAt()).isNotNull();
    }

    @Test
    @DisplayName("should send a push notification when channel is BOTH")
    void shouldSendPush_whenChannelIsBoth() {
      // Given
      NotificationPreferenceEntity prefs = preference(true, false, "player-1", null);
      when(preferenceRepository.findByUserId(RECIPIENT_ID)).thenReturn(Optional.of(prefs));
      when(notificationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

      // When
      notificationService.send(baseRequest(NotificationChannel.BOTH).build());

      // Then
      verify(pushService).sendToPlayer(eq("player-1"), any(), any(), anyMap());
    }

    @Test
    @DisplayName("should pass empty push data when the request has none")
    void shouldPassEmptyPushData_whenRequestHasNone() {
      // Given
      NotificationPreferenceEntity prefs = preference(true, false, "player-1", null);
      when(preferenceRepository.findByUserId(RECIPIENT_ID)).thenReturn(Optional.of(prefs));
      when(notificationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

      // When
      notificationService.send(baseRequest(NotificationChannel.PUSH).build());

      // Then
      verify(pushService).sendToPlayer(any(), any(), any(), eq(Map.of()));
    }

    @Test
    @DisplayName("should skip push when the channel is EMAIL only")
    void shouldSkipPush_whenChannelIsEmailOnly() {
      // Given
      NotificationPreferenceEntity prefs = preference(true, true, "player-1", null);
      when(preferenceRepository.findByUserId(RECIPIENT_ID)).thenReturn(Optional.of(prefs));
      when(userRepository.findById(RECIPIENT_ID)).thenReturn(Optional.empty());
      when(notificationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

      // When
      notificationService.send(baseRequest(NotificationChannel.EMAIL).build());

      // Then
      verify(pushService, never()).sendToPlayer(any(), any(), any(), any());
    }

    @Test
    @DisplayName("should skip push when push is not enabled")
    void shouldSkipPush_whenPushNotEnabled() {
      // Given
      NotificationPreferenceEntity prefs = preference(false, false, "player-1", null);
      when(preferenceRepository.findByUserId(RECIPIENT_ID)).thenReturn(Optional.of(prefs));
      when(notificationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

      // When
      notificationService.send(baseRequest(NotificationChannel.PUSH).build());

      // Then
      verify(pushService, never()).sendToPlayer(any(), any(), any(), any());
    }

    @Test
    @DisplayName("should skip push when the pushEnabled flag is null")
    void shouldSkipPush_whenPushEnabledIsNull() {
      // Given
      NotificationPreferenceEntity prefs = preference(null, false, "player-1", null);
      when(preferenceRepository.findByUserId(RECIPIENT_ID)).thenReturn(Optional.of(prefs));
      when(notificationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

      // When
      notificationService.send(baseRequest(NotificationChannel.PUSH).build());

      // Then
      verify(pushService, never()).sendToPlayer(any(), any(), any(), any());
    }

    @Test
    @DisplayName("should skip push when no OneSignal player id is configured")
    void shouldSkipPush_whenNoPlayerIdConfigured() {
      // Given
      NotificationPreferenceEntity prefs = preference(true, false, null, null);
      when(preferenceRepository.findByUserId(RECIPIENT_ID)).thenReturn(Optional.of(prefs));
      when(notificationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

      // When
      notificationService.send(baseRequest(NotificationChannel.PUSH).build());

      // Then
      verify(pushService, never()).sendToPlayer(any(), any(), any(), any());
    }

    @Test
    @DisplayName(
        "should send an email when channel is EMAIL, enabled, and the recipient has an email")
    void shouldSendEmail_whenChannelIsEmail() {
      // Given
      NotificationPreferenceEntity prefs = preference(false, true, null, null);
      when(preferenceRepository.findByUserId(RECIPIENT_ID)).thenReturn(Optional.of(prefs));
      when(userRepository.findById(RECIPIENT_ID))
          .thenReturn(Optional.of(user(RECIPIENT_ID, "user@example.com", "Alice")));
      when(notificationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

      // When
      notificationService.send(baseRequest(NotificationChannel.EMAIL).build());

      // Then
      verify(mailService).sendNotificationEmail("user@example.com", "Alice", "Title", "Body");
      verify(notificationRepository, times(2)).save(entityCaptor.capture());
      assertThat(entityCaptor.getValue().getSentAt()).isNotNull();
    }

    @Test
    @DisplayName("should send an email when channel is BOTH")
    void shouldSendEmail_whenChannelIsBoth() {
      // Given
      NotificationPreferenceEntity prefs = preference(false, true, null, null);
      when(preferenceRepository.findByUserId(RECIPIENT_ID)).thenReturn(Optional.of(prefs));
      when(userRepository.findById(RECIPIENT_ID))
          .thenReturn(Optional.of(user(RECIPIENT_ID, "user@example.com", "Alice")));
      when(notificationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

      // When
      notificationService.send(baseRequest(NotificationChannel.BOTH).build());

      // Then
      verify(mailService).sendNotificationEmail(any(), any(), any(), any());
    }

    @Test
    @DisplayName("should not send an email when the recipient asked for NONE")
    void shouldNotSendEmail_whenFrequencyIsNone() {
      // Given — everything else says "send": channel EMAIL and emailEnabled true. Only the
      // frequency objects. This is the case that used to send anyway, because shouldSendEmail
      // never read emailFrequency.
      NotificationPreferenceEntity prefs = preference(false, true, null, null);
      prefs.setEmailFrequency(EmailFrequency.NONE);
      when(preferenceRepository.findByUserId(RECIPIENT_ID)).thenReturn(Optional.of(prefs));
      when(notificationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

      // When
      notificationService.send(baseRequest(NotificationChannel.EMAIL).build());

      // Then
      verify(mailService, never()).sendNotificationEmail(any(), any(), any(), any());
    }

    @Test
    @DisplayName("should send an email when the stored frequency is null")
    void shouldSendEmail_whenFrequencyIsNull() {
      // Given — rows written before email_frequency had a value read back as null. The check is
      // deliberately NONE.equals(stored) rather than !INSTANT.equals(stored) so null keeps the
      // old behaviour of sending instead of silently muting someone.
      NotificationPreferenceEntity prefs = preference(false, true, null, null);
      prefs.setEmailFrequency(null);
      when(preferenceRepository.findByUserId(RECIPIENT_ID)).thenReturn(Optional.of(prefs));
      when(userRepository.findById(RECIPIENT_ID))
          .thenReturn(Optional.of(user(RECIPIENT_ID, "user@example.com", "Alice")));
      when(notificationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

      // When
      notificationService.send(baseRequest(NotificationChannel.EMAIL).build());

      // Then
      verify(mailService).sendNotificationEmail(any(), any(), any(), any());
    }

    @Test
    @DisplayName("should default to \"User\" when the recipient's full name is null")
    void shouldDefaultToUser_whenRecipientFullNameIsNull() {
      // Given
      NotificationPreferenceEntity prefs = preference(false, true, null, null);
      when(preferenceRepository.findByUserId(RECIPIENT_ID)).thenReturn(Optional.of(prefs));
      when(userRepository.findById(RECIPIENT_ID))
          .thenReturn(Optional.of(user(RECIPIENT_ID, "user@example.com", null)));
      when(notificationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

      // When
      notificationService.send(baseRequest(NotificationChannel.EMAIL).build());

      // Then
      verify(mailService).sendNotificationEmail(eq("user@example.com"), eq("User"), any(), any());
    }

    @Test
    @DisplayName("should skip email when the channel is PUSH only")
    void shouldSkipEmail_whenChannelIsPushOnly() {
      // Given
      NotificationPreferenceEntity prefs = preference(true, true, "player-1", null);
      when(preferenceRepository.findByUserId(RECIPIENT_ID)).thenReturn(Optional.of(prefs));
      when(notificationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

      // When
      notificationService.send(baseRequest(NotificationChannel.PUSH).build());

      // Then
      verify(mailService, never()).sendNotificationEmail(any(), any(), any(), any());
      verify(userRepository, never()).findById(any());
    }

    @Test
    @DisplayName("should skip email when email is not enabled")
    void shouldSkipEmail_whenEmailNotEnabled() {
      // Given
      NotificationPreferenceEntity prefs = preference(false, false, null, null);
      when(preferenceRepository.findByUserId(RECIPIENT_ID)).thenReturn(Optional.of(prefs));
      when(notificationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

      // When
      notificationService.send(baseRequest(NotificationChannel.EMAIL).build());

      // Then
      verify(mailService, never()).sendNotificationEmail(any(), any(), any(), any());
    }

    @Test
    @DisplayName("should not send an email when the recipient no longer exists")
    void shouldSkipEmailSend_whenRecipientNotFound() {
      // Given
      NotificationPreferenceEntity prefs = preference(false, true, null, null);
      when(preferenceRepository.findByUserId(RECIPIENT_ID)).thenReturn(Optional.of(prefs));
      when(userRepository.findById(RECIPIENT_ID)).thenReturn(Optional.empty());
      when(notificationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

      // When
      notificationService.send(baseRequest(NotificationChannel.EMAIL).build());

      // Then
      verify(mailService, never()).sendNotificationEmail(any(), any(), any(), any());
    }

    @Test
    @DisplayName("should not send an email when the recipient has no email address")
    void shouldSkipEmailSend_whenRecipientHasNoEmail() {
      // Given
      NotificationPreferenceEntity prefs = preference(false, true, null, null);
      when(preferenceRepository.findByUserId(RECIPIENT_ID)).thenReturn(Optional.of(prefs));
      when(userRepository.findById(RECIPIENT_ID))
          .thenReturn(Optional.of(user(RECIPIENT_ID, null, "Alice")));
      when(notificationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

      // When
      notificationService.send(baseRequest(NotificationChannel.EMAIL).build());

      // Then
      verify(mailService, never()).sendNotificationEmail(any(), any(), any(), any());
    }

    @Test
    @DisplayName("should create a new preference record when none exists yet")
    void shouldCreateNewPreference_whenNoneExistsYet() {
      // Given
      when(preferenceRepository.findByUserId(RECIPIENT_ID)).thenReturn(Optional.empty());
      when(preferenceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
      when(notificationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

      // When
      notificationService.send(baseRequest(NotificationChannel.PUSH).build());

      // Then
      verify(preferenceRepository).save(any());
      verify(pushService, never()).sendToPlayer(any(), any(), any(), any());
    }
  }

  // =====================================================================
  // getNotifications
  // =====================================================================

  @Nested
  @DisplayName("getNotifications")
  class GetNotificationsTests {

    @Test
    @DisplayName("should return the page mapped to response DTOs")
    void shouldReturnMappedPage() {
      // Given
      NotificationEntity entity =
          NotificationEntity.builder()
              .id(1)
              .actorId(ACTOR_ID)
              .type(NotificationType.FRIEND_REQUEST)
              .title("t")
              .body("b")
              .channel(NotificationChannel.BOTH)
              .isRead(false)
              .build();
      when(notificationRepository.findByRecipientIdOrderByCreatedAtDesc(
              eq(RECIPIENT_ID), eq(PageRequest.of(0, 10))))
          .thenReturn(new PageImpl<>(List.of(entity)));

      // When
      var result = notificationService.getNotifications(RECIPIENT_ID, 1, 10);

      // Then
      assertThat(result.getContent()).hasSize(1);
      assertThat(result.getContent().get(0).getId()).isEqualTo(1);
    }

    @Test
    @DisplayName("should carry the post through to the DTO for a COMMENT reference")
    void shouldMapPostId() {
      // Given — a stored comment notification, the shape V72's backfill also produces for rows
      // written before the column existed
      NotificationEntity entity =
          NotificationEntity.builder()
              .id(2)
              .actorId(ACTOR_ID)
              .type(NotificationType.COMMENT_LIKED)
              .title("t")
              .body("b")
              .referenceId(88)
              .referenceType("COMMENT")
              .postId(500)
              .channel(NotificationChannel.BOTH)
              .isRead(false)
              .build();
      when(notificationRepository.findByRecipientIdOrderByCreatedAtDesc(
              eq(RECIPIENT_ID), eq(PageRequest.of(0, 10))))
          .thenReturn(new PageImpl<>(List.of(entity)));

      // When
      var result = notificationService.getNotifications(RECIPIENT_ID, 1, 10);

      // Then — read straight off the row. Resolving it here instead would be an N+1 across the
      // page, and this method has no transaction to hold it open.
      assertThat(result.getContent().get(0).getPostId()).isEqualTo(500);
    }
  }

  // =====================================================================
  // getUnreadCount
  // =====================================================================

  @Nested
  @DisplayName("getUnreadCount")
  class GetUnreadCountTests {

    @Test
    @DisplayName("should return the unread count from the repository")
    void shouldReturnCount() {
      // Given
      when(notificationRepository.countByRecipientIdAndIsReadFalse(RECIPIENT_ID)).thenReturn(5);

      // When / Then
      assertThat(notificationService.getUnreadCount(RECIPIENT_ID)).isEqualTo(5);
    }
  }

  // =====================================================================
  // markAsRead
  // =====================================================================

  @Nested
  @DisplayName("markAsRead")
  class MarkAsReadTests {

    @Test
    @DisplayName("should mark the notification read when it belongs to the caller")
    void shouldMarkAsRead_whenBelongsToUser() {
      // Given
      NotificationEntity entity =
          NotificationEntity.builder().id(9).recipientId(RECIPIENT_ID).isRead(false).build();
      when(notificationRepository.findById(9)).thenReturn(Optional.of(entity));

      // When
      notificationService.markAsRead(RECIPIENT_ID, 9);

      // Then
      assertThat(entity.getIsRead()).isTrue();
      verify(notificationRepository).save(entity);
    }

    @Test
    @DisplayName("should not mark it read when it belongs to someone else")
    void shouldNotMarkAsRead_whenBelongsToSomeoneElse() {
      // Given
      NotificationEntity entity =
          NotificationEntity.builder().id(9).recipientId(99).isRead(false).build();
      when(notificationRepository.findById(9)).thenReturn(Optional.of(entity));

      // When
      notificationService.markAsRead(RECIPIENT_ID, 9);

      // Then
      assertThat(entity.getIsRead()).isFalse();
      verify(notificationRepository, never()).save(any());
    }

    @Test
    @DisplayName("should do nothing when the notification does not exist")
    void shouldDoNothing_whenNotFound() {
      // Given
      when(notificationRepository.findById(9)).thenReturn(Optional.empty());

      // When
      notificationService.markAsRead(RECIPIENT_ID, 9);

      // Then
      verify(notificationRepository, never()).save(any());
    }
  }

  // =====================================================================
  // markAllAsRead
  // =====================================================================

  @Nested
  @DisplayName("markAllAsRead")
  class MarkAllAsReadTests {

    @Test
    @DisplayName("should delegate to the repository's bulk update")
    void shouldDelegateToRepository() {
      // When
      notificationService.markAllAsRead(RECIPIENT_ID);

      // Then
      verify(notificationRepository).markAllAsRead(RECIPIENT_ID);
    }
  }

  // =====================================================================
  // updatePreference
  // =====================================================================

  @Nested
  @DisplayName("updatePreference")
  class UpdatePreferenceTests {

    @Test
    @DisplayName("should update every field that is present in the request")
    void shouldUpdateAllFields_whenAllPresent() {
      // Given
      NotificationPreferenceEntity existing = preference(true, true, "old-player", null);
      when(preferenceRepository.findByUserId(RECIPIENT_ID)).thenReturn(Optional.of(existing));
      when(preferenceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

      UpdatePreferenceRequestDto request = new UpdatePreferenceRequestDto();
      request.setPushEnabled(false);
      request.setEmailEnabled(false);
      request.setOnesignalPlayerId("new-player");
      request.setEmailFrequency(EmailFrequency.NONE);
      request.setMutedTypes(List.of("SYSTEM"));

      // When
      NotificationPreferenceResponseDto result =
          notificationService.updatePreference(RECIPIENT_ID, request);

      // Then
      assertThat(result.getPushEnabled()).isFalse();
      assertThat(result.getEmailEnabled()).isFalse();
      assertThat(result.getEmailFrequency()).isEqualTo(EmailFrequency.NONE);
      assertThat(result.getMutedTypes()).containsExactly("SYSTEM");
      // The device token is persisted but deliberately kept out of the response DTO.
      assertThat(existing.getOnesignalPlayerId()).isEqualTo("new-player");
    }

    @Test
    @DisplayName("should leave every field unchanged when the request has none set")
    void shouldLeaveFieldsUnchanged_whenAllNull() {
      // Given
      NotificationPreferenceEntity existing = preference(true, true, "old-player", null);
      when(preferenceRepository.findByUserId(RECIPIENT_ID)).thenReturn(Optional.of(existing));
      when(preferenceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

      UpdatePreferenceRequestDto request = new UpdatePreferenceRequestDto();

      // When
      NotificationPreferenceResponseDto result =
          notificationService.updatePreference(RECIPIENT_ID, request);

      // Then
      assertThat(result.getPushEnabled()).isTrue();
      assertThat(result.getEmailEnabled()).isTrue();
      assertThat(existing.getOnesignalPlayerId()).isEqualTo("old-player");
    }
  }

  // =====================================================================
  // getPreference
  // =====================================================================

  @Nested
  @DisplayName("getPreference")
  class GetPreferenceTests {

    @Test
    @DisplayName("should return the existing preference when present")
    void shouldReturnExistingPreference() {
      // Given
      NotificationPreferenceEntity existing = preference(true, true, null, null);
      when(preferenceRepository.findByUserId(RECIPIENT_ID)).thenReturn(Optional.of(existing));

      // When
      NotificationPreferenceResponseDto result = notificationService.getPreference(RECIPIENT_ID);

      // Then
      assertThat(result.getUserId()).isEqualTo(RECIPIENT_ID);
      assertThat(result.getPushEnabled()).isTrue();
      assertThat(result.getEmailEnabled()).isTrue();
    }

    @Test
    @DisplayName("should create and return a new preference when none exists")
    void shouldCreateNewPreference_whenNoneExists() {
      // Given
      when(preferenceRepository.findByUserId(RECIPIENT_ID)).thenReturn(Optional.empty());
      when(preferenceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

      // When
      NotificationPreferenceResponseDto result = notificationService.getPreference(RECIPIENT_ID);

      // Then
      assertThat(result.getUserId()).isEqualTo(RECIPIENT_ID);
      verify(preferenceRepository).save(any());
    }
  }

  @Nested
  @DisplayName("send — block filtering")
  class SendBlockFilteringTests {

    @Test
    @DisplayName("should drop the notification entirely when a block stands between the two")
    void shouldDropNotificationAcrossABlock() {
      // Given
      when(blockQueryService.isBlockedEitherWay(RECIPIENT_ID, ACTOR_ID)).thenReturn(true);

      // When
      notificationService.send(baseRequest(NotificationChannel.BOTH).build());

      // Then — nothing stored and nothing sent: a notification names its actor, so delivering one
      // across a block tells each side the other is still reaching them
      verifyNoInteractions(notificationRepository);
      verifyNoInteractions(pushService);
      verifyNoInteractions(mailService);
      verifyNoInteractions(preferenceRepository);
    }

    @Test
    @DisplayName("should not consult the block set for a system notification with no actor")
    void shouldSkipBlockCheckWithoutActor() {
      // Given: a notification with no actor cannot be "from" anyone to block
      when(preferenceRepository.findByUserId(RECIPIENT_ID))
          .thenReturn(Optional.of(preference(false, false, null, null)));
      // save() has to hand the row back: send() pushes the stored notification to any open SSE
      // stream, so a mock returning null here is not a stand-in for the real repository.
      when(notificationRepository.save(any())).thenAnswer(call -> call.getArgument(0));

      // When
      notificationService.send(baseRequest(NotificationChannel.PUSH).actorId(null).build());

      // Then
      verify(blockQueryService, never()).isBlockedEitherWay(any(), any());
      // Saved rather than dropped — send() writes the row and then writes it again with sentAt,
      // so the count here is about the notification surviving the block check, not about how
      // many times the row is persisted.
      verify(notificationRepository, atLeastOnce()).save(any());
    }
  }
}
