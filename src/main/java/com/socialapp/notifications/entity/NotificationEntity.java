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

  @Enumerated(EnumType.STRING)
  private NotificationChannel channel;

  @Builder.Default private Boolean isRead = false;

  private OffsetDateTime sentAt;

  @CreationTimestamp private OffsetDateTime createdAt;
}
