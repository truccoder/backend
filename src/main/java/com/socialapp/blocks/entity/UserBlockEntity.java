package com.socialapp.blocks.entity;

import java.time.OffsetDateTime;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** One user blocking another. See {@code V46__create_t_user_blocks.sql}. */
@Entity
@Table(name = "t_user_blocks")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserBlockEntity {
  @EmbeddedId private UserBlockId id;

  @CreationTimestamp private OffsetDateTime createdAt;
}
