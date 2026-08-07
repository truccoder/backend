package com.socialapp.posts.service;

import static java.time.temporal.ChronoUnit.MINUTES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.socialapp.notifications.dto.SendNotificationRequest;
import com.socialapp.notifications.entity.enums.NotificationType;
import com.socialapp.notifications.repository.NotificationRepository;
import com.socialapp.notifications.services.NotificationService;
import com.socialapp.posts.entity.EventDetails;
import com.socialapp.posts.entity.EventRsvpEntity;
import com.socialapp.posts.entity.PostEntity;
import com.socialapp.posts.entity.enums.PostType;
import com.socialapp.posts.entity.enums.RsvpStatus;
import com.socialapp.posts.repository.EventRsvpRepository;
import com.socialapp.posts.repository.PostRepository;

/**
 * Component (unit) tests for {@link EventReminderScheduler}, per ISTQB CTFL v4.0.1 (Section 2.2.1
 * component testing, Section 2.1.3 BDD Given/When/Then, Section 4.3.2 branch testing — the RSVP
 * status filter, the already-sent guard and the per-event error boundary are each driven both
 * ways).
 */
@ExtendWith(MockitoExtension.class)
class EventReminderSchedulerTest {

  private static final Integer POST_ID = 700;
  private static final Integer ATTENDEE_ID = 42;

  @Mock private PostRepository postRepository;
  @Mock private EventRsvpRepository rsvpRepository;
  @Mock private NotificationService notificationService;
  @Mock private NotificationRepository notificationRepository;

  @InjectMocks private EventReminderScheduler scheduler;

  @Captor private ArgumentCaptor<SendNotificationRequest> requestCaptor;

  private static PostEntity event(String title) {
    PostEntity post = new PostEntity();
    post.setId(POST_ID);
    post.setAuthorId(1);
    post.setPostType(PostType.EVENT);
    EventDetails details = new EventDetails();
    details.setEventTitle(title);
    details.setStartTime(OffsetDateTime.now().plusHours(12));
    post.setEventDetails(details);
    return post;
  }

  private static EventRsvpEntity rsvp(Integer userId, RsvpStatus status) {
    return EventRsvpEntity.builder().postId(POST_ID).userId(userId).status(status).build();
  }

  @Nested
  @DisplayName("sendDueReminders")
  class SendDueRemindersTests {

    @Test
    @DisplayName("should do nothing when no event starts within the lead time")
    void shouldDoNothing_whenNoUpcomingEvents() {
      // Given
      when(postRepository.findEventsStartingBetween(any(), any())).thenReturn(List.of());

      // When
      scheduler.sendDueReminders();

      // Then
      verifyNoInteractions(rsvpRepository, notificationService);
    }

    @Test
    @DisplayName("should remind an attendee who is going, with the event title in the body")
    void shouldRemindGoingAttendee() {
      // Given
      when(postRepository.findEventsStartingBetween(any(), any()))
          .thenReturn(List.of(event("Java Meetup")));
      when(rsvpRepository.findByPostId(POST_ID))
          .thenReturn(List.of(rsvp(ATTENDEE_ID, RsvpStatus.GOING)));

      // When
      scheduler.sendDueReminders();

      // Then
      verify(notificationService).send(requestCaptor.capture());
      SendNotificationRequest sent = requestCaptor.getValue();
      assertThat(sent.getRecipientId()).isEqualTo(ATTENDEE_ID);
      assertThat(sent.getType()).isEqualTo(NotificationType.EVENT_REMINDER);
      assertThat(sent.getBody()).contains("Java Meetup");
      assertThat(sent.getReferenceId()).isEqualTo(POST_ID);
      assertThat(sent.getReferenceType()).isEqualTo("POST");
    }

    @Test
    @DisplayName("should remind an interested attendee too")
    void shouldRemindInterestedAttendee() {
      // Given
      when(postRepository.findEventsStartingBetween(any(), any()))
          .thenReturn(List.of(event("Java Meetup")));
      when(rsvpRepository.findByPostId(POST_ID))
          .thenReturn(List.of(rsvp(ATTENDEE_ID, RsvpStatus.INTERESTED)));

      // When
      scheduler.sendDueReminders();

      // Then
      verify(notificationService).send(any());
    }

    @Test
    @DisplayName("should not remind somebody who declined")
    void shouldNotRemindNotGoingAttendee() {
      // Given
      when(postRepository.findEventsStartingBetween(any(), any()))
          .thenReturn(List.of(event("Java Meetup")));
      when(rsvpRepository.findByPostId(POST_ID))
          .thenReturn(List.of(rsvp(ATTENDEE_ID, RsvpStatus.NOT_GOING)));

      // When
      scheduler.sendDueReminders();

      // Then
      verify(notificationService, never()).send(any());
      verify(notificationRepository, never())
          .existsByRecipientIdAndTypeAndReferenceId(anyInt(), any(), anyInt());
    }

    @Test
    @DisplayName("should not remind the same person twice for the same event")
    void shouldNotRemindTwice() {
      // Given — the previous sweep already wrote this reminder
      when(postRepository.findEventsStartingBetween(any(), any()))
          .thenReturn(List.of(event("Java Meetup")));
      when(rsvpRepository.findByPostId(POST_ID))
          .thenReturn(List.of(rsvp(ATTENDEE_ID, RsvpStatus.GOING)));
      when(notificationRepository.existsByRecipientIdAndTypeAndReferenceId(
              ATTENDEE_ID, NotificationType.EVENT_REMINDER, POST_ID))
          .thenReturn(true);

      // When — the sweep re-reads the whole window every run, so this happens every 15 minutes
      scheduler.sendDueReminders();

      // Then
      verify(notificationService, never()).send(any());
    }

    @Test
    @DisplayName("should skip an event whose details are missing instead of failing the sweep")
    void shouldSkipEventWithNullDetails() {
      // Given
      PostEntity broken = event("Java Meetup");
      broken.setEventDetails(null);
      when(postRepository.findEventsStartingBetween(any(), any())).thenReturn(List.of(broken));

      // When / Then
      assertThatCode(() -> scheduler.sendDueReminders()).doesNotThrowAnyException();
      verify(notificationService, never()).send(any());
    }

    @Test
    @DisplayName("should keep sweeping the remaining events when one of them blows up")
    void shouldContinueSweep_whenOneEventFails() {
      // Given — two events; looking up the first one's RSVPs fails
      PostEntity first = event("Broken Event");
      PostEntity second = event("Java Meetup");
      second.setId(POST_ID + 1);
      when(postRepository.findEventsStartingBetween(any(), any()))
          .thenReturn(List.of(first, second));
      when(rsvpRepository.findByPostId(POST_ID)).thenThrow(new RuntimeException("db down"));
      when(rsvpRepository.findByPostId(POST_ID + 1))
          .thenReturn(List.of(rsvp(ATTENDEE_ID, RsvpStatus.GOING)));

      // When / Then — the second event is still reminded.
      assertThatCode(() -> scheduler.sendDueReminders()).doesNotThrowAnyException();
      verify(notificationService, times(1)).send(any());
    }

    @Test
    @DisplayName("should ask for events between now and 24 hours ahead")
    void shouldQueryTheNext24Hours() {
      // Given
      ArgumentCaptor<OffsetDateTime> from = ArgumentCaptor.forClass(OffsetDateTime.class);
      ArgumentCaptor<OffsetDateTime> to = ArgumentCaptor.forClass(OffsetDateTime.class);
      when(postRepository.findEventsStartingBetween(any(), any())).thenReturn(List.of());

      // When
      scheduler.sendDueReminders();

      // Then — an event that already started is past reminding, so the lower bound is now.
      verify(postRepository).findEventsStartingBetween(from.capture(), to.capture());
      assertThat(java.time.Duration.between(from.getValue(), to.getValue()).toHours())
          .isEqualTo(24);
      assertThat(from.getValue()).isCloseTo(OffsetDateTime.now(), within(1, MINUTES));
    }

    @Test
    @DisplayName("should fall back to a generic title when the event has none")
    void shouldUseFallbackTitle_whenEventTitleIsNull() {
      // Given
      when(postRepository.findEventsStartingBetween(any(), any())).thenReturn(List.of(event(null)));
      when(rsvpRepository.findByPostId(POST_ID))
          .thenReturn(List.of(rsvp(ATTENDEE_ID, RsvpStatus.GOING)));

      // When
      scheduler.sendDueReminders();

      // Then
      verify(notificationService).send(requestCaptor.capture());
      assertThat(requestCaptor.getValue().getBody())
          .isEqualTo("An event you signed up for starts within 24 hours");
    }

    @Test
    @DisplayName("should remind every eligible attendee of the same event")
    void shouldRemindAllEligibleAttendees() {
      // Given
      when(postRepository.findEventsStartingBetween(any(), any()))
          .thenReturn(List.of(event("Java Meetup")));
      when(rsvpRepository.findByPostId(POST_ID))
          .thenReturn(
              List.of(
                  rsvp(ATTENDEE_ID, RsvpStatus.GOING),
                  rsvp(ATTENDEE_ID + 1, RsvpStatus.INTERESTED),
                  rsvp(ATTENDEE_ID + 2, RsvpStatus.NOT_GOING)));

      // When
      scheduler.sendDueReminders();

      // Then
      verify(notificationService, times(2)).send(any());
      verify(notificationService, never()).send(argThatRecipientIs(ATTENDEE_ID + 2));
    }

    private SendNotificationRequest argThatRecipientIs(Integer recipientId) {
      return org.mockito.ArgumentMatchers.argThat(
          r -> r != null && recipientId.equals(r.getRecipientId()));
    }
  }
}
