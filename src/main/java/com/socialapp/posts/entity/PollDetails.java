package com.socialapp.posts.entity;

import java.time.OffsetDateTime;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PollDetails {
  private String question;
  private List<PollOption> options;
  private Boolean allowMultipleVotes;
  private OffsetDateTime endDate;
}
