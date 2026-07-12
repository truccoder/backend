package com.socialapp.notifications.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import com.socialapp.AbstractIntegrationTest;
import com.socialapp.notifications.entity.NotificationEntity;
import com.socialapp.notifications.entity.enums.NotificationChannel;
import com.socialapp.notifications.entity.enums.NotificationType;
import com.socialapp.security.entity.UserEntity;
import com.socialapp.security.repository.UserRepository;

import jakarta.persistence.EntityManager;

/**
 * Component integration tests for {@link NotificationRepository} against a real PostgreSQL
 * instance (Testcontainers), per ISTQB CTFL v4.0.1 Section 2.2.1. Each test rolls back its own
 * transaction, so no manual cleanup is needed. {@code t_notifications.recipient_id} is a
 * required foreign key to a real user, so each test seeds one first.
 */
@Transactional
class NotificationRepositoryTest extends AbstractIntegrationTest {

  @Autowired private NotificationRepository notificationRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private EntityManager entityManager;

  private Integer recipientId;

  @BeforeEach
  void seedRecipient() {
    recipientId = userRepository.saveAndFlush(user("recipient@example.com", "recipient")).getId();
  }

  private static UserEntity user(String email, String username) {
    UserEntity user = new UserEntity();
    user.setEmail(email);
    user.setPassword("hashed-password");
    user.setUsername(username);
    user.setFullName("Test User");
    return user;
  }

  private static NotificationEntity notification(Integer recipientId, boolean isRead) {
    return NotificationEntity.builder()
        .recipientId(recipientId)
        .type(NotificationType.POST_LIKED)
        .title("Someone liked your post")
        .channel(NotificationChannel.PUSH)
        .isRead(isRead)
        .build();
  }

  @Nested
  @DisplayName("findByRecipientIdOrderByCreatedAtDesc")
  class FindByRecipientIdOrderByCreatedAtDesc {

    @Test
    @DisplayName("orders the recipient's notifications newest first")
    void ordersNewestFirst() {
      // Given
      NotificationEntity first =
          notificationRepository.saveAndFlush(notification(recipientId, false));
      NotificationEntity second =
          notificationRepository.saveAndFlush(notification(recipientId, false));

      // When
      Page<NotificationEntity> result =
          notificationRepository.findByRecipientIdOrderByCreatedAtDesc(
              recipientId, PageRequest.of(0, 10));

      // Then
      assertThat(result.getContent())
          .extracting(NotificationEntity::getId)
          .containsExactly(second.getId(), first.getId());
    }

    @Test
    @DisplayName("returns an empty page when the recipient has no notifications")
    void returnsEmptyPageWhenNoNotifications() {
      // When
      Page<NotificationEntity> result =
          notificationRepository.findByRecipientIdOrderByCreatedAtDesc(
              recipientId, PageRequest.of(0, 10));

      // Then
      assertThat(result.getContent()).isEmpty();
    }
  }

  @Nested
  @DisplayName("findByRecipientIdAndIsReadFalseOrderByCreatedAtDesc")
  class FindUnreadNotifications {

    @Test
    @DisplayName("returns only unread notifications, newest first")
    void returnsOnlyUnreadNotifications() {
      // Given
      NotificationEntity unread =
          notificationRepository.saveAndFlush(notification(recipientId, false));
      notificationRepository.saveAndFlush(notification(recipientId, true));

      // When
      List<NotificationEntity> result =
          notificationRepository.findByRecipientIdAndIsReadFalseOrderByCreatedAtDesc(recipientId);

      // Then
      assertThat(result).extracting(NotificationEntity::getId).containsExactly(unread.getId());
    }

    @Test
    @DisplayName("returns an empty list when every notification has been read")
    void returnsEmptyListWhenAllRead() {
      // Given
      notificationRepository.saveAndFlush(notification(recipientId, true));

      // When
      List<NotificationEntity> result =
          notificationRepository.findByRecipientIdAndIsReadFalseOrderByCreatedAtDesc(recipientId);

      // Then
      assertThat(result).isEmpty();
    }
  }

  @Nested
  @DisplayName("countByRecipientIdAndIsReadFalse")
  class CountByRecipientIdAndIsReadFalse {

    @Test
    @DisplayName("counts only unread notifications")
    void countsUnreadNotifications() {
      // Given
      notificationRepository.saveAndFlush(notification(recipientId, false));
      notificationRepository.saveAndFlush(notification(recipientId, false));
      notificationRepository.saveAndFlush(notification(recipientId, true));

      // When
      int result = notificationRepository.countByRecipientIdAndIsReadFalse(recipientId);

      // Then
      assertThat(result).isEqualTo(2);
    }

    @Test
    @DisplayName("returns zero when every notification has been read")
    void returnsZeroWhenAllRead() {
      // Given
      notificationRepository.saveAndFlush(notification(recipientId, true));

      // When
      int result = notificationRepository.countByRecipientIdAndIsReadFalse(recipientId);

      // Then
      assertThat(result).isEqualTo(0);
    }
  }

  @Nested
  @DisplayName("markAllAsRead")
  class MarkAllAsRead {

    @Test
    @DisplayName("marks every unread notification of the recipient as read")
    void marksUnreadNotificationsAsRead() {
      // Given
      NotificationEntity unread1 =
          notificationRepository.saveAndFlush(notification(recipientId, false));
      NotificationEntity unread2 =
          notificationRepository.saveAndFlush(notification(recipientId, false));

      // When
      notificationRepository.markAllAsRead(recipientId);
      entityManager.clear();

      // Then
      assertThat(notificationRepository.findById(unread1.getId()))
          .get()
          .extracting(NotificationEntity::getIsRead)
          .isEqualTo(true);
      assertThat(notificationRepository.findById(unread2.getId()))
          .get()
          .extracting(NotificationEntity::getIsRead)
          .isEqualTo(true);
    }

    @Test
    @DisplayName("does not affect notifications of a different recipient")
    void doesNotAffectOtherRecipients() {
      // Given
      Integer otherRecipientId =
          userRepository.saveAndFlush(user("other@example.com", "other")).getId();
      NotificationEntity othersNotification =
          notificationRepository.saveAndFlush(notification(otherRecipientId, false));

      // When
      notificationRepository.markAllAsRead(recipientId);
      entityManager.clear();

      // Then
      assertThat(notificationRepository.findById(othersNotification.getId()))
          .get()
          .extracting(NotificationEntity::getIsRead)
          .isEqualTo(false);
    }
  }
}
