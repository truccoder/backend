package com.socialapp.posts.service;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialapp.common.exception.NotFoundException;
import com.socialapp.common.exception.ValidationException;
import com.socialapp.posts.entity.EventDetails;
import com.socialapp.posts.entity.EventRsvpEntity;
import com.socialapp.posts.entity.PostEntity;
import com.socialapp.posts.entity.enums.PostType;
import com.socialapp.posts.entity.enums.RsvpStatus;
import com.socialapp.posts.repository.EventRsvpRepository;
import com.socialapp.posts.repository.PostRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class EventService {
  private final PostRepository postRepository;
  private final EventRsvpRepository rsvpRepository;
  private final GoogleCalendarService googleCalendarService;

  private static final DateTimeFormatter ICS_FORMAT =
      DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");

  @Transactional
  public void rsvp(Integer userId, Integer postId, RsvpStatus status) {
    PostEntity post = findEventPostOrThrow(postId);
    EventDetails details = post.getEventDetails();

    if (Objects.nonNull(details.getMaxAttendees())) {
      int goingCount = rsvpRepository.countByPostIdAndStatus(postId, RsvpStatus.GOING);
      if (RsvpStatus.GOING.equals(status) && goingCount >= details.getMaxAttendees()) {
        throw new ValidationException("Event is full");
      }
    }

    EventRsvpEntity rsvp =
        rsvpRepository
            .findByPostIdAndUserId(postId, userId)
            .orElseGet(() -> EventRsvpEntity.builder().postId(postId).userId(userId).build());

    rsvp.setStatus(status);
    rsvpRepository.save(rsvp);
  }

  public List<EventRsvpEntity> getAttendees(Integer postId) {
    findEventPostOrThrow(postId);
    return rsvpRepository.findByPostId(postId);
  }

  public int getGoingCount(Integer postId) {
    return rsvpRepository.countByPostIdAndStatus(postId, RsvpStatus.GOING);
  }

  public void addToGoogleCalendar(Integer userId, Integer postId) {
    PostEntity post = findEventPostOrThrow(postId);
    googleCalendarService.addEventToCalendar(userId, post.getEventDetails());
  }

  public String generateIcsFile(Integer postId) {
    PostEntity post = findEventPostOrThrow(postId);
    EventDetails event = post.getEventDetails();

    String startUtc =
        event.getStartTime().toInstant().atOffset(java.time.ZoneOffset.UTC).format(ICS_FORMAT);
    String endUtc =
        event.getEndTime().toInstant().atOffset(java.time.ZoneOffset.UTC).format(ICS_FORMAT);

    return "BEGIN:VCALENDAR\r\n"
        + "VERSION:2.0\r\n"
        + "PRODID:-//SocialApp//Event//EN\r\n"
        + "BEGIN:VEVENT\r\n"
        + "DTSTART:"
        + startUtc
        + "\r\n"
        + "DTEND:"
        + endUtc
        + "\r\n"
        + "SUMMARY:"
        + escapeIcs(event.getEventTitle())
        + "\r\n"
        + "DESCRIPTION:"
        + escapeIcs(Objects.toString(event.getEventDescription(), ""))
        + "\r\n"
        + "LOCATION:"
        + escapeIcs(Objects.toString(event.getLocation(), ""))
        + "\r\n"
        + "END:VEVENT\r\n"
        + "END:VCALENDAR\r\n";
  }

  private PostEntity findEventPostOrThrow(Integer postId) {
    PostEntity post =
        postRepository
            .findById(postId)
            .orElseThrow(() -> new NotFoundException("Post not found: " + postId));

    if (!PostType.EVENT.equals(post.getPostType())) {
      throw new ValidationException("Post is not an event");
    }
    return post;
  }

  private String escapeIcs(String text) {
    if (text == null) return "";
    return text.replace("\\", "\\\\").replace(",", "\\,").replace(";", "\\;").replace("\n", "\\n");
  }
}
