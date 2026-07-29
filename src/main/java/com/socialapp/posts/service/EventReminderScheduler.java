package com.socialapp.posts.service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.socialapp.notifications.dto.SendNotificationRequest;
import com.socialapp.notifications.entity.enums.NotificationType;
import com.socialapp.notifications.repository.NotificationRepository;
import com.socialapp.notifications.services.NotificationService;
import com.socialapp.posts.entity.EventDetails;
import com.socialapp.posts.entity.EventRsvpEntity;
import com.socialapp.posts.entity.PostEntity;
import com.socialapp.posts.entity.enums.RsvpStatus;
import com.socialapp.posts.repository.EventRsvpRepository;
import com.socialapp.posts.repository.PostRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Reminds people about an event they said they would attend, a day before it starts.
 *
 * <p>{@code EVENT_REMINDER} was declared in {@link NotificationType} with nothing anywhere that
 * could produce one. Unlike the other unused types it needed a clock rather than a user action,
 * which is why it outlived them.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EventReminderScheduler {
  private final PostRepository postRepository;
  private final EventRsvpRepository rsvpRepository;
  private final NotificationService notificationService;
  private final NotificationRepository notificationRepository;

  private static final Duration LEAD_TIME = Duration.ofHours(24);

  /** NOT_GOING is excluded, matching {@code EventService.notifyHost}. */
  private static final Set<RsvpStatus> REMINDED_STATUSES =
      Set.of(RsvpStatus.GOING, RsvpStatus.INTERESTED);

  /**
   * Sweeps every event starting within the lead time rather than only those crossing the 24-hour
   * mark since the last run. A narrow window would silently drop reminders whenever the app was
   * down or a run was missed, and would never reach an event created less than a day before it
   * starts. Sweeping the whole window is only safe because sending is idempotent (below), and it
   * costs one indexed-ish scan of upcoming events per run.
   */
  @Scheduled(fixedDelayString = "${events.reminder-interval-ms:900000}")
  public void sendDueReminders() {
    OffsetDateTime now = OffsetDateTime.now();
    List<PostEntity> upcoming = postRepository.findEventsStartingBetween(now, now.plus(LEAD_TIME));

    if (upcoming.isEmpty()) {
      log.debug("Event reminder sweep: no events start within the next {}", LEAD_TIME);
      return;
    }

    int sent = 0;
    for (PostEntity event : upcoming) {
      try {
        sent += remindAttendees(event);
      } catch (Exception e) {
        // One broken event must not stop the sweep for the others — same shape as the crawl loop.
        log.error("Event reminder failed for post {}: {}", event.getId(), e.getMessage());
      }
    }

    log.info("Event reminder sweep: {} events in window, {} reminders sent", upcoming.size(), sent);
  }

  private int remindAttendees(PostEntity event) {
    EventDetails details = event.getEventDetails();
    if (Objects.isNull(details)) {
      return 0;
    }

    String title = Objects.toString(details.getEventTitle(), "An event you signed up for");
    int sent = 0;

    for (EventRsvpEntity rsvp : rsvpRepository.findByPostId(event.getId())) {
      if (!REMINDED_STATUSES.contains(rsvp.getStatus()) || alreadyReminded(rsvp, event.getId())) {
        continue;
      }

      notificationService.send(
          SendNotificationRequest.builder()
              .recipientId(rsvp.getUserId())
              // No actor: nobody did anything, the clock did.
              .type(NotificationType.EVENT_REMINDER)
              .title("Event starting soon")
              .body(title + " starts within 24 hours")
              .referenceId(event.getId())
              .referenceType("POST")
              .build());
      sent++;
    }

    return sent;
  }

  /**
   * The written notification is the only "already sent" record this job keeps, so it needs no
   * table and no migration. The cost is that a user who deletes the reminder could be reminded
   * again on the next sweep; the alternative was a whole table to store one boolean per RSVP.
   */
  private boolean alreadyReminded(EventRsvpEntity rsvp, Integer postId) {
    return notificationRepository.existsByRecipientIdAndTypeAndReferenceId(
        rsvp.getUserId(), NotificationType.EVENT_REMINDER, postId);
  }
}
