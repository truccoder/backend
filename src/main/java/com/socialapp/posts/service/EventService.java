package com.socialapp.posts.service;

import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialapp.common.exception.NotFoundException;
import com.socialapp.common.exception.ValidationException;
import com.socialapp.posts.dto.EventAttendeeDto;
import com.socialapp.posts.entity.EventDetails;
import com.socialapp.posts.entity.EventRsvpEntity;
import com.socialapp.posts.entity.PostEntity;
import com.socialapp.posts.entity.enums.PostType;
import com.socialapp.posts.entity.enums.RsvpStatus;
import com.socialapp.posts.repository.EventRsvpRepository;
import com.socialapp.posts.repository.PostRepository;
import com.socialapp.security.entity.UserEntity;
import com.socialapp.security.repository.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class EventService {
  private final PostRepository postRepository;
  private final EventRsvpRepository rsvpRepository;
  private final UserRepository userRepository;
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

  /**
   * Attendees of an event, optionally narrowed to one RSVP status.
   *
   * <p>Unfiltered means every RSVP row, {@code NOT_GOING} and {@code INTERESTED} included — that is
   * deliberate, callers that want the guest list pass {@code GOING}. The status is on every row so
   * a caller can group them without a second round trip.
   */
  public List<EventAttendeeDto> getAttendees(Integer postId, RsvpStatus status) {
    findEventPostOrThrow(postId);

    List<EventRsvpEntity> rsvps =
        status == null
            ? rsvpRepository.findByPostId(postId)
            : rsvpRepository.findByPostIdAndStatus(postId, status);

    // One batch lookup rather than a findById per row: an event can hold hundreds of RSVPs.
    Map<Integer, UserEntity> usersById = new HashMap<>();
    userRepository
        .findAllById(
            rsvps.stream().map(EventRsvpEntity::getUserId).filter(Objects::nonNull).toList())
        .forEach(u -> usersById.put(u.getId(), u));

    return rsvps.stream()
        .map(
            rsvp -> {
              UserEntity user = usersById.get(rsvp.getUserId());
              return EventAttendeeDto.builder()
                  .userId(rsvp.getUserId())
                  .fullName(user != null ? user.getFullName() : null)
                  .profilePictureUrl(user != null ? user.getProfilePictureUrl() : null)
                  .status(rsvp.getStatus())
                  .respondedAt(rsvp.getCreatedAt())
                  .build();
            })
        .toList();
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

    // UID and DTSTAMP are both MUST properties of VEVENT (RFC 5545 §3.6.1) and strict importers
    // reject a file without them. UID is derived from the post id so re-importing the same event
    // updates the existing calendar entry instead of creating a duplicate; DTSTAMP is the moment
    // this file was generated, which is what the spec asks for on a published (non-METHOD) event.
    String uid = "event-" + postId + "@socialapp";
    String dtStamp = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).format(ICS_FORMAT);

    return "BEGIN:VCALENDAR\r\n"
        + "VERSION:2.0\r\n"
        + "PRODID:-//SocialApp//Event//EN\r\n"
        + "BEGIN:VEVENT\r\n"
        + "UID:"
        + uid
        + "\r\n"
        + "DTSTAMP:"
        + dtStamp
        + "\r\n"
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
