package com.socialapp.blocks.entity;

import java.io.Serial;
import java.io.Serializable;

import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** The pair is the key — blocking somebody twice is the same block. */
@Embeddable
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserBlockId implements Serializable {
  @Serial private static final long serialVersionUID = 1L;

  private Integer blockerId;
  private Integer blockedId;
}
