package com.socialapp.notifications.entity;

import java.time.OffsetDateTime;
import java.util.List;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import com.socialapp.notifications.entity.enums.EmailFrequency;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "t_notification_preferences")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationPreferenceEntity {
  @Id private Integer userId;

  @Builder.Default private Boolean pushEnabled = true;

  @Builder.Default private Boolean emailEnabled = true;

  private String onesignalPlayerId;

  @Enumerated(EnumType.STRING)
  @Builder.Default
  private EmailFrequency emailFrequency = EmailFrequency.INSTANT;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  private List<String> mutedTypes;

  @UpdateTimestamp private OffsetDateTime updatedAt;
}
