package com.socialapp.posts.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class QnaDetails {
  private Boolean isResolved;
  private Integer bountyPoints;
  private Integer acceptedAnswerId;
}
