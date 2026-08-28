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
import com.socialapp.notifications.dto.SendNotificationRequest;
import com.socialapp.notifications.entity.enums.NotificationType;
import com.socialapp.notifications.services.NotificationService;
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
  private final NotificationService notificationService;
  private final GoogleCalendarService googleCalendarService;
  private final PostVisibilityService postVisibilityService;

  private static final DateTimeFormatter ICS_FORMAT =
      DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");

  @Transactional
  public void rsvp(Integer userId, Integer postId, RsvpStatus status) {
    PostEntity post = findEventPostOrThrow(userId, postId);
    EventDetails details = post.getEventDetails();

    if (Objects.nonNull(details.getMaxAttendees()) && RsvpStatus.GOING.equals(status)) {
      // Lock the event row first: this reads the GOING count and then writes an RSVP, so without
      // it each concurrent "going" counts against a snapshot missing the others and the cap is
      // exceeded. Only taken when a cap exists and the answer is GOING — a NOT_GOING never
      // contends. Same approach as ProjectService.acceptApplication.
      postRepository.findByIdForUpdate(postId);
      int goingCount = rsvpRepository.countByPostIdAndStatus(postId, RsvpStatus.GOING);
      boolean alreadyGoing =
          rsvpRepository
              .findByPostIdAndUserId(postId, userId)
              .map(existing -> RsvpStatus.GOING.equals(existing.getStatus()))
              .orElse(false);
      // Someone already counted in goingCount is not taking a second seat by re-confirming.
      if (!alreadyGoing && goingCount >= details.getMaxAttendees()) {
        throw new ValidationException("Event is full");
      }
    }

    EventRsvpEntity rsvp =
        rsvpRepository
            .findByPostIdAndUserId(postId, userId)
            .orElseGet(() -> EventRsvpEntity.builder().postId(postId).userId(userId).build());

    RsvpStatus previousStatus = rsvp.getStatus();
    rsvp.setStatus(status);
    rsvpRepository.save(rsvp);

    if (!status.equals(previousStatus)) {
      notifyHost(post, userId, status);
    }
  }

  /**
   * Tells the event's host that somebody answered. EVENT_RSVP was one of four notification types
   * the enum declared with no publisher anywhere: the RSVP itself worked, only the ping was
   * missing.
   *
   * <p>Only fired on an actual change of answer (see the caller) and never for {@code NOT_GOING} —
   * a decline is not worth a notification, and without that guard flipping GOING → NOT_GOING would
   * ping the host twice for one person's change of mind.
   */
  private void notifyHost(PostEntity post, Integer attendeeId, RsvpStatus status) {
    Integer hostId = post.getAuthorId();
    if (RsvpStatus.NOT_GOING.equals(status)
        || Objects.isNull(hostId)
        || hostId.equals(attendeeId)) {
      return;
    }

    String attendeeName =
        userRepository
            .findById(attendeeId)
            .map(UserEntity::getFullName)
            .filter(name -> Objects.nonNull(name) && !name.isBlank())
            .orElse("Someone");
    String eventTitle = Objects.toString(post.getEventDetails().getEventTitle(), "your event");

    notificationService.send(
        SendNotificationRequest.builder()
            .recipientId(hostId)
            .actorId(attendeeId)
            .type(NotificationType.EVENT_RSVP)
            .title("New RSVP for your event")
            .body(
                attendeeName
                    + (RsvpStatus.GOING.equals(status) ? " is going to " : " is interested in ")
                    + eventTitle)
            .referenceId(post.getId())
            .referenceType("POST")
            .build());
  }

  /**
   * Attendees of an event, optionally narrowed to one RSVP status.
   *
   * <p>Unfiltered means every RSVP row, {@code NOT_GOING} and {@code INTERESTED} included — that is
   * deliberate, callers that want the guest list pass {@code GOING}. The status is on every row so
   * a caller can group them without a second round trip.
   */
  public List<EventAttendeeDto> getAttendees(Integer viewerId, Integer postId, RsvpStatus status) {
    findEventPostOrThrow(viewerId, postId);

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

  public int getGoingCount(Integer viewerId, Integer postId) {
    findEventPostOrThrow(viewerId, postId);
    return rsvpRepository.countByPostIdAndStatus(postId, RsvpStatus.GOING);
  }

  public void addToGoogleCalendar(Integer userId, Integer postId) {
    PostEntity post = findEventPostOrThrow(userId, postId);
    googleCalendarService.addEventToCalendar(userId, post.getEventDetails());
  }

  public String generateIcsFile(Integer viewerId, Integer postId) {
    PostEntity post = findEventPostOrThrow(viewerId, postId);
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

  /**
   * The event post, but only if {@code viewerId} is allowed to read it.
   *
   * <p>This used to check existence and {@link PostType#EVENT} and nothing else, so the attendee
   * list of a PRIVATE event — every attendee's full name and avatar — and its {@code .ics} export
   * — title, description, location and time — were readable by anyone who guessed the post id.
   *
   * <p>404, not 403, matching {@code PostReactionService#requireVisiblePost}. The post-type check
   * runs after the visibility check so that "not an event" never leaks the existence of a post the
   * caller may not see.
   */
  private PostEntity findEventPostOrThrow(Integer viewerId, Integer postId) {
    PostEntity post =
        postRepository
            .findById(postId)
            .orElseThrow(() -> new NotFoundException("Post not found: " + postId));

    if (!postVisibilityService.isVisibleTo(post, viewerId)) {
      throw new NotFoundException("Post not found: " + postId);
    }

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
