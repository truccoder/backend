package com.socialapp.posts.entity;

import java.time.OffsetDateTime;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class EventDetails {
  private String eventTitle;
  private String eventDescription;
  private OffsetDateTime startTime;
  private OffsetDateTime endTime;
  private String timezone;
  private String location;
  private String onlineUrl;
  private Integer maxAttendees;
}
