package com.socialapp.posts.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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

/**
 * Component (unit) tests for {@link EventService}, per ISTQB CTFL v4.0.1 Section 2.2.1
 * (component testing) — every collaborator is mocked with Mockito so the service is tested in
 * isolation, without a Spring context. BDD Given/When/Then per Section 2.1.3.
 */
@ExtendWith(MockitoExtension.class)
class EventServiceTest {

  private static final Integer USER_ID = 1;
  private static final Integer HOST_ID = 7;
  private static final Integer POST_ID = 100;

  @Mock private PostRepository postRepository;
  @Mock private EventRsvpRepository rsvpRepository;
  @Mock private UserRepository userRepository;
  @Mock private NotificationService notificationService;
  @Mock private GoogleCalendarService googleCalendarService;
  @Mock private PostVisibilityService postVisibilityService;

  @InjectMocks private EventService eventService;

  @Captor private ArgumentCaptor<EventRsvpEntity> rsvpCaptor;

  private static PostEntity sampleEventPost(Integer maxAttendees) {
    EventDetails details = new EventDetails();
    details.setEventTitle("Tech Meetup");
    details.setEventDescription("A meetup");
    details.setStartTime(OffsetDateTime.parse("2026-08-01T10:00:00Z"));
    details.setEndTime(OffsetDateTime.parse("2026-08-01T12:00:00Z"));
    details.setLocation("Hanoi");
    details.setMaxAttendees(maxAttendees);

    PostEntity post = new PostEntity();
    post.setId(POST_ID);
    post.setAuthorId(HOST_ID);
    post.setPostType(PostType.EVENT);
    post.setEventDetails(details);
    return post;
  }

  private static UserEntity sampleUser() {
    UserEntity user = new UserEntity();
    user.setId(USER_ID);
    user.setFullName("Nguyen Truc");
    user.setProfilePictureUrl("https://cdn/avatar.png");
    return user;
  }

  private static PostEntity sampleRegularPost() {
    PostEntity post = new PostEntity();
    post.setId(POST_ID);
    post.setPostType(PostType.REGULAR);
    return post;
  }

  // =====================================================================
  // rsvp
  // =====================================================================

  @Nested
  @DisplayName("rsvp")
  class RsvpTests {

    @Test
    @DisplayName("should create a new RSVP when the user has not RSVP'd yet")
    void shouldCreateNewRsvp_whenUserHasNotRsvpdYet() {
      // Given
      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(sampleEventPost(null)));
      when(postVisibilityService.isVisibleTo(any(), any())).thenReturn(true);
      when(rsvpRepository.findByPostIdAndUserId(POST_ID, USER_ID)).thenReturn(Optional.empty());

      // When
      eventService.rsvp(USER_ID, POST_ID, RsvpStatus.GOING);

      // Then
      verify(rsvpRepository).save(rsvpCaptor.capture());
      assertThat(rsvpCaptor.getValue().getPostId()).isEqualTo(POST_ID);
      assertThat(rsvpCaptor.getValue().getUserId()).isEqualTo(USER_ID);
      assertThat(rsvpCaptor.getValue().getStatus()).isEqualTo(RsvpStatus.GOING);
    }

    @Test
    @DisplayName("should update the existing RSVP's status when the user already RSVP'd")
    void shouldUpdateExistingRsvp_whenUserAlreadyRsvpd() {
      // Given
      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(sampleEventPost(null)));
      when(postVisibilityService.isVisibleTo(any(), any())).thenReturn(true);
      EventRsvpEntity existing =
          EventRsvpEntity.builder()
              .postId(POST_ID)
              .userId(USER_ID)
              .status(RsvpStatus.INTERESTED)
              .build();
      when(rsvpRepository.findByPostIdAndUserId(POST_ID, USER_ID))
          .thenReturn(Optional.of(existing));

      // When
      eventService.rsvp(USER_ID, POST_ID, RsvpStatus.NOT_GOING);

      // Then
      verify(rsvpRepository).save(rsvpCaptor.capture());
      assertThat(rsvpCaptor.getValue().getStatus()).isEqualTo(RsvpStatus.NOT_GOING);
    }

    @Test
    @DisplayName("should notify the host when somebody says they are going")
    void shouldNotifyHost_whenRsvpIsGoing() {
      // Given — EVENT_RSVP was declared in NotificationType with no publisher anywhere (B16)
      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(sampleEventPost(null)));
      when(postVisibilityService.isVisibleTo(any(), any())).thenReturn(true);
      when(rsvpRepository.findByPostIdAndUserId(POST_ID, USER_ID)).thenReturn(Optional.empty());
      when(userRepository.findById(USER_ID)).thenReturn(Optional.of(sampleUser()));

      // When
      eventService.rsvp(USER_ID, POST_ID, RsvpStatus.GOING);

      // Then
      ArgumentCaptor<SendNotificationRequest> sent =
          ArgumentCaptor.forClass(SendNotificationRequest.class);
      verify(notificationService).send(sent.capture());
      assertThat(sent.getValue().getRecipientId()).isEqualTo(HOST_ID);
      assertThat(sent.getValue().getActorId()).isEqualTo(USER_ID);
      assertThat(sent.getValue().getType()).isEqualTo(NotificationType.EVENT_RSVP);
      assertThat(sent.getValue().getBody()).isEqualTo("Nguyen Truc is going to Tech Meetup");
      assertThat(sent.getValue().getReferenceId()).isEqualTo(POST_ID);
      assertThat(sent.getValue().getReferenceType()).isEqualTo("POST");
    }

    @Test
    @DisplayName("should not notify the host when the answer is NOT_GOING")
    void shouldNotNotifyHost_whenRsvpIsNotGoing() {
      // Given — a decline is not worth a ping, and it also stops GOING -> NOT_GOING from
      // notifying the host twice about one person changing their mind
      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(sampleEventPost(null)));
      when(postVisibilityService.isVisibleTo(any(), any())).thenReturn(true);
      when(rsvpRepository.findByPostIdAndUserId(POST_ID, USER_ID)).thenReturn(Optional.empty());

      // When
      eventService.rsvp(USER_ID, POST_ID, RsvpStatus.NOT_GOING);

      // Then
      verifyNoInteractions(notificationService);
    }

    @Test
    @DisplayName("should not notify when the RSVP repeats the answer already on file")
    void shouldNotNotifyHost_whenStatusIsUnchanged() {
      // Given
      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(sampleEventPost(null)));
      when(postVisibilityService.isVisibleTo(any(), any())).thenReturn(true);
      when(rsvpRepository.findByPostIdAndUserId(POST_ID, USER_ID))
          .thenReturn(
              Optional.of(
                  EventRsvpEntity.builder()
                      .postId(POST_ID)
                      .userId(USER_ID)
                      .status(RsvpStatus.GOING)
                      .build()));

      // When — the client re-sends GOING
      eventService.rsvp(USER_ID, POST_ID, RsvpStatus.GOING);

      // Then
      verifyNoInteractions(notificationService);
    }

    @Test
    @DisplayName("should not notify the host about their own RSVP")
    void shouldNotNotifyHost_whenHostRsvpsToOwnEvent() {
      // Given
      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(sampleEventPost(null)));
      when(postVisibilityService.isVisibleTo(any(), any())).thenReturn(true);
      when(rsvpRepository.findByPostIdAndUserId(POST_ID, HOST_ID)).thenReturn(Optional.empty());

      // When
      eventService.rsvp(HOST_ID, POST_ID, RsvpStatus.GOING);

      // Then
      verifyNoInteractions(notificationService);
    }

    @Test
    @DisplayName("should fall back to a generic actor name when the attendee cannot be loaded")
    void shouldUseFallbackName_whenAttendeeIsMissing() {
      // Given
      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(sampleEventPost(null)));
      when(postVisibilityService.isVisibleTo(any(), any())).thenReturn(true);
      when(rsvpRepository.findByPostIdAndUserId(POST_ID, USER_ID)).thenReturn(Optional.empty());
      when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

      // When
      eventService.rsvp(USER_ID, POST_ID, RsvpStatus.INTERESTED);

      // Then
      ArgumentCaptor<SendNotificationRequest> sent =
          ArgumentCaptor.forClass(SendNotificationRequest.class);
      verify(notificationService).send(sent.capture());
      assertThat(sent.getValue().getBody()).isEqualTo("Someone is interested in Tech Meetup");
    }

    @Test
    @DisplayName("should allow GOING when maxAttendees is set but the event is not yet full")
    void shouldAllowGoing_whenEventHasCapacityLeft() {
      // Given
      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(sampleEventPost(10)));
      when(postVisibilityService.isVisibleTo(any(), any())).thenReturn(true);
      when(rsvpRepository.countByPostIdAndStatus(POST_ID, RsvpStatus.GOING)).thenReturn(9);
      when(rsvpRepository.findByPostIdAndUserId(POST_ID, USER_ID)).thenReturn(Optional.empty());

      // When
      eventService.rsvp(USER_ID, POST_ID, RsvpStatus.GOING);

      // Then
      verify(rsvpRepository).save(any());
    }

    @Test
    @DisplayName("should throw ValidationException when GOING and the event is already full")
    void shouldThrowValidationException_whenEventIsFull() {
      // Given
      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(sampleEventPost(10)));
      when(postVisibilityService.isVisibleTo(any(), any())).thenReturn(true);
      when(rsvpRepository.countByPostIdAndStatus(POST_ID, RsvpStatus.GOING)).thenReturn(10);

      // When / Then
      assertThatThrownBy(() -> eventService.rsvp(USER_ID, POST_ID, RsvpStatus.GOING))
          .isInstanceOf(ValidationException.class)
          .hasMessageContaining("Event is full");
      verify(rsvpRepository, never()).save(any());
    }

    @Test
    @DisplayName("should allow NOT_GOING even when the event is already at max capacity")
    void shouldAllowNotGoing_whenEventIsFull() {
      // Given — the capacity block is skipped entirely for a non-GOING answer, so neither the
      // count nor the row lock is reached. A NOT_GOING never contends for a seat.
      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(sampleEventPost(10)));
      when(postVisibilityService.isVisibleTo(any(), any())).thenReturn(true);
      when(rsvpRepository.findByPostIdAndUserId(POST_ID, USER_ID)).thenReturn(Optional.empty());

      // When
      eventService.rsvp(USER_ID, POST_ID, RsvpStatus.NOT_GOING);

      // Then
      verify(rsvpRepository).save(any());
    }

    @Test
    @DisplayName("should throw NotFoundException when the post does not exist")
    void shouldThrowNotFoundException_whenPostDoesNotExist() {
      // Given
      when(postRepository.findById(POST_ID)).thenReturn(Optional.empty());

      // When / Then
      assertThatThrownBy(() -> eventService.rsvp(USER_ID, POST_ID, RsvpStatus.GOING))
          .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("should throw ValidationException when the post is not an event")
    void shouldThrowValidationException_whenPostIsNotAnEvent() {
      // Given
      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(sampleRegularPost()));
      when(postVisibilityService.isVisibleTo(any(), any())).thenReturn(true);

      // When / Then
      assertThatThrownBy(() -> eventService.rsvp(USER_ID, POST_ID, RsvpStatus.GOING))
          .isInstanceOf(ValidationException.class)
          .hasMessageContaining("not an event");
    }
  }

  // =====================================================================
  // getAttendees / getGoingCount
  // =====================================================================

  @Nested
  @DisplayName("getAttendees / getGoingCount")
  class QueryTests {

    @Test
    @DisplayName("should return every RSVP for the event, resolved to a named attendee")
    void shouldReturnAttendees_whenPostIsAnEvent() {
      // Given
      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(sampleEventPost(null)));
      when(postVisibilityService.isVisibleTo(any(), any())).thenReturn(true);
      EventRsvpEntity rsvp =
          EventRsvpEntity.builder()
              .postId(POST_ID)
              .userId(USER_ID)
              .status(RsvpStatus.GOING)
              .createdAt(OffsetDateTime.parse("2026-07-20T09:00:00Z"))
              .build();
      when(rsvpRepository.findByPostId(POST_ID)).thenReturn(List.of(rsvp));
      when(userRepository.findAllById(List.of(USER_ID))).thenReturn(List.of(sampleUser()));

      // When
      List<EventAttendeeDto> attendees = eventService.getAttendees(USER_ID, POST_ID, null);

      // Then
      assertThat(attendees)
          .singleElement()
          .satisfies(
              a -> {
                assertThat(a.getUserId()).isEqualTo(USER_ID);
                assertThat(a.getFullName()).isEqualTo("Nguyen Truc");
                assertThat(a.getProfilePictureUrl()).isEqualTo("https://cdn/avatar.png");
                assertThat(a.getStatus()).isEqualTo(RsvpStatus.GOING);
                assertThat(a.getRespondedAt())
                    .isEqualTo(OffsetDateTime.parse("2026-07-20T09:00:00Z"));
              });
    }

    @Test
    @DisplayName("should query only the requested status when a filter is supplied")
    void shouldFilterByStatus_whenStatusIsSupplied() {
      // Given
      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(sampleEventPost(null)));
      when(postVisibilityService.isVisibleTo(any(), any())).thenReturn(true);
      EventRsvpEntity going =
          EventRsvpEntity.builder()
              .postId(POST_ID)
              .userId(USER_ID)
              .status(RsvpStatus.GOING)
              .build();
      when(rsvpRepository.findByPostIdAndStatus(POST_ID, RsvpStatus.GOING))
          .thenReturn(List.of(going));
      when(userRepository.findAllById(List.of(USER_ID))).thenReturn(List.of(sampleUser()));

      // When
      List<EventAttendeeDto> attendees =
          eventService.getAttendees(USER_ID, POST_ID, RsvpStatus.GOING);

      // Then
      assertThat(attendees)
          .extracting(EventAttendeeDto::getStatus)
          .containsExactly(RsvpStatus.GOING);
      verify(rsvpRepository, never()).findByPostId(POST_ID);
    }

    @Test
    @DisplayName("should leave identity null when the RSVP points at a missing user")
    void shouldLeaveIdentityNull_whenUserIsMissing() {
      // Given
      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(sampleEventPost(null)));
      when(postVisibilityService.isVisibleTo(any(), any())).thenReturn(true);
      when(rsvpRepository.findByPostId(POST_ID))
          .thenReturn(
              List.of(
                  EventRsvpEntity.builder()
                      .postId(POST_ID)
                      .userId(USER_ID)
                      .status(RsvpStatus.INTERESTED)
                      .build()));
      when(userRepository.findAllById(List.of(USER_ID))).thenReturn(List.of());

      // When
      List<EventAttendeeDto> attendees = eventService.getAttendees(USER_ID, POST_ID, null);

      // Then
      assertThat(attendees)
          .singleElement()
          .satisfies(
              a -> {
                assertThat(a.getUserId()).isEqualTo(USER_ID);
                assertThat(a.getFullName()).isNull();
                assertThat(a.getProfilePictureUrl()).isNull();
              });
    }

    @Test
    @DisplayName("should throw NotFoundException when the post does not exist")
    void shouldThrowNotFoundException_whenPostDoesNotExist() {
      // Given
      when(postRepository.findById(POST_ID)).thenReturn(Optional.empty());

      // When / Then
      assertThatThrownBy(() -> eventService.getAttendees(USER_ID, POST_ID, null))
          .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("should return the count of GOING RSVPs")
    void shouldReturnGoingCount() {
      // Given: the count now goes through the same visibility gate as the attendee list — the
      // number of people going to a PRIVATE event is itself information about that event.
      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(sampleEventPost(null)));
      when(postVisibilityService.isVisibleTo(any(), any())).thenReturn(true);
      when(rsvpRepository.countByPostIdAndStatus(POST_ID, RsvpStatus.GOING)).thenReturn(5);

      // When
      int count = eventService.getGoingCount(USER_ID, POST_ID);

      // Then
      assertThat(count).isEqualTo(5);
    }
  }

  // =====================================================================
  // addToGoogleCalendar
  // =====================================================================

  @Nested
  @DisplayName("addToGoogleCalendar")
  class AddToGoogleCalendarTests {

    @Test
    @DisplayName("should delegate to GoogleCalendarService with the event's details")
    void shouldDelegateToGoogleCalendarService() {
      // Given
      PostEntity post = sampleEventPost(null);
      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(post));
      when(postVisibilityService.isVisibleTo(any(), any())).thenReturn(true);

      // When
      eventService.addToGoogleCalendar(USER_ID, POST_ID);

      // Then
      verify(googleCalendarService).addEventToCalendar(USER_ID, post.getEventDetails());
    }

    @Test
    @DisplayName("should throw NotFoundException when the post does not exist")
    void shouldThrowNotFoundException_whenPostDoesNotExist() {
      // Given
      when(postRepository.findById(POST_ID)).thenReturn(Optional.empty());

      // When / Then
      assertThatThrownBy(() -> eventService.addToGoogleCalendar(USER_ID, POST_ID))
          .isInstanceOf(NotFoundException.class);
    }
  }

  // =====================================================================
  // generateIcsFile
  // =====================================================================

  @Nested
  @DisplayName("generateIcsFile")
  class GenerateIcsFileTests {

    @Test
    @DisplayName("should generate a well-formed .ics file with the event's details")
    void shouldGenerateIcsFile_withEventDetails() {
      // Given
      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(sampleEventPost(null)));
      when(postVisibilityService.isVisibleTo(any(), any())).thenReturn(true);

      // When
      String ics = eventService.generateIcsFile(USER_ID, POST_ID);

      // Then
      assertThat(ics)
          .startsWith("BEGIN:VCALENDAR")
          .contains("DTSTART:20260801T100000Z")
          .contains("DTEND:20260801T120000Z")
          .contains("SUMMARY:Tech Meetup")
          .contains("LOCATION:Hanoi")
          .endsWith("END:VCALENDAR\r\n");
    }

    @Test
    @DisplayName("should carry the UID and DTSTAMP that RFC 5545 makes mandatory")
    void shouldIncludeUidAndDtstamp() {
      // Given
      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(sampleEventPost(null)));
      when(postVisibilityService.isVisibleTo(any(), any())).thenReturn(true);

      // When
      String ics = eventService.generateIcsFile(USER_ID, POST_ID);

      // Then — UID is derived from the post id so a re-import updates rather than duplicates,
      // and DTSTAMP is the generation time in the same UTC basic format as DTSTART.
      assertThat(ics).contains("UID:event-" + POST_ID + "@socialapp\r\n");
      assertThat(ics).containsPattern("DTSTAMP:\\d{8}T\\d{6}Z\r\n");
    }

    @Test
    @DisplayName("should escape commas, semicolons, backslashes, and newlines in text fields")
    void shouldEscapeSpecialCharacters_inTextFields() {
      // Given
      PostEntity post = sampleEventPost(null);
      post.getEventDetails().setEventTitle("A, B; C\\D\nE");
      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(post));
      when(postVisibilityService.isVisibleTo(any(), any())).thenReturn(true);

      // When
      String ics = eventService.generateIcsFile(USER_ID, POST_ID);

      // Then
      assertThat(ics).contains("SUMMARY:A\\, B\\; C\\\\D\\nE");
    }

    @Test
    @DisplayName("should render empty description/location when they are null")
    void shouldRenderEmptyFields_whenDescriptionAndLocationAreNull() {
      // Given
      PostEntity post = sampleEventPost(null);
      post.getEventDetails().setEventDescription(null);
      post.getEventDetails().setLocation(null);
      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(post));
      when(postVisibilityService.isVisibleTo(any(), any())).thenReturn(true);

      // When
      String ics = eventService.generateIcsFile(USER_ID, POST_ID);

      // Then
      assertThat(ics).contains("DESCRIPTION:\r\n").contains("LOCATION:\r\n");
    }

    @Test
    @DisplayName("should throw NotFoundException when the post does not exist")
    void shouldThrowNotFoundException_whenPostDoesNotExist() {
      // Given
      when(postRepository.findById(POST_ID)).thenReturn(Optional.empty());

      // When / Then
      assertThatThrownBy(() -> eventService.generateIcsFile(USER_ID, POST_ID))
          .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("should throw ValidationException when the post is not an event")
    void shouldThrowValidationException_whenPostIsNotAnEvent() {
      // Given
      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(sampleRegularPost()));
      when(postVisibilityService.isVisibleTo(any(), any())).thenReturn(true);

      // When / Then
      assertThatThrownBy(() -> eventService.generateIcsFile(USER_ID, POST_ID))
          .isInstanceOf(ValidationException.class);
    }
  }

  // =====================================================================
  // post visibility gate
  // =====================================================================

  @Nested
  @DisplayName("post visibility")
  class PostVisibilityTests {

    @Test
    @DisplayName("should not list attendees of an event the viewer may not read")
    void shouldRefuseGetAttendees_whenPostNotVisible() {
      // Given: the attendee list is every attendee's full name and avatar
      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(sampleEventPost(null)));
      when(postVisibilityService.isVisibleTo(any(), any())).thenReturn(false);

      // When / Then
      assertThatThrownBy(() -> eventService.getAttendees(USER_ID, POST_ID, null))
          .isInstanceOf(NotFoundException.class);
      verifyNoInteractions(rsvpRepository);
    }

    @Test
    @DisplayName("should not export the .ics of an event the viewer may not read")
    void shouldRefuseGenerateIcs_whenPostNotVisible() {
      // Given: the .ics carries title, description, location and time
      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(sampleEventPost(null)));
      when(postVisibilityService.isVisibleTo(any(), any())).thenReturn(false);

      // When / Then
      assertThatThrownBy(() -> eventService.generateIcsFile(USER_ID, POST_ID))
          .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("should not RSVP to an event the caller may not read")
    void shouldRefuseRsvp_whenPostNotVisible() {
      // Given
      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(sampleEventPost(null)));
      when(postVisibilityService.isVisibleTo(any(), any())).thenReturn(false);

      // When / Then
      assertThatThrownBy(() -> eventService.rsvp(USER_ID, POST_ID, RsvpStatus.GOING))
          .isInstanceOf(NotFoundException.class);
      verify(rsvpRepository, never()).save(any());
    }

    @Test
    @DisplayName("should report the post missing rather than not-an-event when it is hidden")
    void shouldPreferNotFoundOverNotAnEvent() {
      // Given: a regular (non-event) post the caller may not see. Answering "Post is not an
      // event" would confirm the post exists, so the visibility check runs first.
      when(postRepository.findById(POST_ID)).thenReturn(Optional.of(sampleRegularPost()));
      when(postVisibilityService.isVisibleTo(any(), any())).thenReturn(false);

      // When / Then
      assertThatThrownBy(() -> eventService.generateIcsFile(USER_ID, POST_ID))
          .isInstanceOf(NotFoundException.class);
    }
  }
}
