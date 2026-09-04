package com.socialapp.notifications.repository;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.socialapp.notifications.entity.NotificationEntity;
import com.socialapp.notifications.entity.enums.NotificationType;

public interface NotificationRepository extends JpaRepository<NotificationEntity, Integer> {

  Page<NotificationEntity> findByRecipientIdOrderByCreatedAtDesc(
      Integer recipientId, Pageable pageable);

  List<NotificationEntity> findByRecipientIdAndIsReadFalseOrderByCreatedAtDesc(Integer recipientId);

  int countByRecipientIdAndIsReadFalse(Integer recipientId);

  /**
   * Whether this exact notification has already been written. The event reminder job uses it as
   * its "already sent" record, which is why it needs no table of its own: a reminder IS a row in
   * here, keyed by recipient, type and the post it points at.
   */
  boolean existsByRecipientIdAndTypeAndReferenceId(
      Integer recipientId, NotificationType type, Integer referenceId);

  @Modifying
  @Query(
      "UPDATE NotificationEntity n SET n.isRead = true WHERE n.recipientId = :recipientId AND n.isRead = false")
  void markAllAsRead(Integer recipientId);

  /**
   * Every notification a deleted post leaves dangling (B42): the ones pointing straight at the
   * post ({@code referenceType = 'POST'}) and the ones about a comment underneath it, which
   * carries the post only in {@link NotificationEntity#getPostId()} — {@code referenceId} there is
   * the comment id, which is about to stop existing too (comments cascade-delete with their post,
   * see {@code PostService#deletePost}).
   */
  @Modifying
  @Query(
      "DELETE FROM NotificationEntity n WHERE n.postId = :postId "
          + "OR (n.referenceType = 'POST' AND n.referenceId = :postId)")
  void deleteAllForPost(@Param("postId") Integer postId);

  /**
   * Every notification pointing at one comment (B42) — {@code COMMENT_LIKED} and {@code
   * USER_MENTIONED} both key {@code referenceType='COMMENT'}/{@code referenceId} to the comment id,
   * same as {@code POST_COMMENTED} keys to the post. Used when a single comment is deleted without
   * its post going with it; the post-wide cleanup above already covers a comment lost to cascade.
   */
  void deleteByReferenceTypeAndReferenceId(String referenceType, Integer referenceId);
}
