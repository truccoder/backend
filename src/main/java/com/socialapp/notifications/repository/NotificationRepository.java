package com.socialapp.notifications.repository;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import com.socialapp.notifications.entity.NotificationEntity;

public interface NotificationRepository extends JpaRepository<NotificationEntity, Integer> {

  Page<NotificationEntity> findByRecipientIdOrderByCreatedAtDesc(
      Integer recipientId, Pageable pageable);

  List<NotificationEntity> findByRecipientIdAndIsReadFalseOrderByCreatedAtDesc(Integer recipientId);

  int countByRecipientIdAndIsReadFalse(Integer recipientId);

  @Modifying
  @Query(
      "UPDATE NotificationEntity n SET n.isRead = true WHERE n.recipientId = :recipientId AND n.isRead = false")
  void markAllAsRead(Integer recipientId);
}
