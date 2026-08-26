package com.socialapp.notifications.entity;

import java.time.OffsetDateTime;

import org.hibernate.annotations.CreationTimestamp;

import com.socialapp.notifications.entity.enums.NotificationChannel;
import com.socialapp.notifications.entity.enums.NotificationType;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "t_notifications")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationEntity {
  @Id
  @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "notifications_seq_gen")
  @SequenceGenerator(
      name = "notifications_seq_gen",
      sequenceName = "q_notifications_id",
      allocationSize = 1)
  private Integer id;

  private Integer recipientId;

  private Integer actorId;

  @Enumerated(EnumType.STRING)
  private NotificationType type;

  private String title;

  @Column(columnDefinition = "TEXT")
  private String body;

  private Integer referenceId;

  private String referenceType;

  /**
   * The post a {@code COMMENT} reference lives under, so the client has somewhere to navigate.
   *
   * <p>Null for every other {@code referenceType}: only a comment needs a second coordinate,
   * because no route is keyed by comment id and a comment row does not announce its post to a
   * client that only holds the notification.
   *
   * <p>Written at send time rather than resolved at read time — both emitting sites already hold
   * the post, while the read path maps a whole page outside a transaction.
   */
  @Column(name = "post_id")
  private Integer postId;

  @Enumerated(EnumType.STRING)
  private NotificationChannel channel;

  @Builder.Default private Boolean isRead = false;

  private OffsetDateTime sentAt;

  @CreationTimestamp private OffsetDateTime createdAt;
}
