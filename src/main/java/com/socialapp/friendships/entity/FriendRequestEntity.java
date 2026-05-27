package com.socialapp.friendships.entity;

import java.time.OffsetDateTime;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import com.socialapp.friendships.entity.enums.FriendRequestStatus;

import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Table(name = "t_friend_requests")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FriendRequestEntity {
  @Id private Integer id;
  private Integer requesterId;
  private Integer addresseeId;
  private FriendRequestStatus status;

  @CreationTimestamp private OffsetDateTime createdAt;

  @UpdateTimestamp private OffsetDateTime updatedAt;
}
