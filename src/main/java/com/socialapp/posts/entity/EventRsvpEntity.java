package com.socialapp.posts.entity;

import java.time.OffsetDateTime;

import org.hibernate.annotations.CreationTimestamp;

import com.socialapp.posts.entity.enums.RsvpStatus;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "t_event_rsvps")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EventRsvpEntity {
  @Id
  @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "event_rsvps_seq_gen")
  @SequenceGenerator(
      name = "event_rsvps_seq_gen",
      sequenceName = "q_event_rsvps_id",
      allocationSize = 1)
  private Integer id;

  private Integer postId;

  private Integer userId;

  @Enumerated(EnumType.STRING)
  private RsvpStatus status;

  @CreationTimestamp private OffsetDateTime createdAt;
}
