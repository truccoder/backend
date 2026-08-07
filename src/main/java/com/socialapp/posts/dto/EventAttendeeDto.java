package com.socialapp.posts.dto;

import java.time.OffsetDateTime;

import com.socialapp.posts.entity.enums.RsvpStatus;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One attendee of an event, with enough identity to render a row.
 *
 * <p>{@code /attendees} used to return {@code EventRsvpEntity} directly, which carries nothing but
 * {@code userId} — and there is no endpoint for looking a user up by id, so the attendee list was
 * literally unrenderable. Joining the name and avatar in here is the only way the caller can get
 * them.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EventAttendeeDto {
  private Integer userId;
  private String fullName;
  private String profilePictureUrl;
  private RsvpStatus status;

  /** When the RSVP was first recorded. */
  private OffsetDateTime respondedAt;
}
