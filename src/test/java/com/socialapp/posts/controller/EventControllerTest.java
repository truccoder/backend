package com.socialapp.posts.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.socialapp.common.exception.ForbiddenException;
import com.socialapp.common.exception.ValidationException;
import com.socialapp.posts.dto.EventAttendeeDto;
import com.socialapp.posts.entity.enums.RsvpStatus;
import com.socialapp.posts.service.EventService;
import com.socialapp.posts.service.GoogleCalendarService;
import com.socialapp.security.config.CustomAccessDeniedHandler;
import com.socialapp.security.config.CustomAuthenticationEntryPoint;
import com.socialapp.security.config.JwtAuthenticationFilter;
import com.socialapp.security.config.JwtProvider;
import com.socialapp.security.config.SecurityConfig;
import com.socialapp.security.entity.UserEntity;
import com.socialapp.security.entity.UserRole;
import com.socialapp.security.repository.UserRepository;

/**
 * System/API integration tests for {@link EventController}, per ISTQB CTFL v4.0.1 Section
 * 2.2.2, using {@code @WebMvcTest} + {@code MockMvc}. {@link EventService} and {@link
 * GoogleCalendarService} are mocked.
 *
 * <p>{@code GET /v1/api/events/google/callback} is the redirect target Google's OAuth consent
 * screen sends the browser back to — that request comes from the browser navigating directly,
 * not from this app's own client with a bearer token, so {@link SecurityConfig} carves it out as
 * {@code permitAll()} (fixed after this gap was found via testing; previously fell under {@code
 * anyRequest().authenticated()} like every other endpoint here and would have rejected the real
 * OAuth redirect with 401).
 *
 * <p>Because that path is open, {@code state} carries the authorisation: it is a single-use
 * server-issued nonce redeemed by {@code consumeOAuthState}, not a user id. Any state the server
 * did not issue — a guessed id, a spent nonce, junk — is a 403. It used to be
 * {@code Integer.parseInt(state)}, which meant anyone could name the account to bind (B1).
 */
@WebMvcTest(EventController.class)
@Import({
  SecurityConfig.class,
  CustomAuthenticationEntryPoint.class,
  CustomAccessDeniedHandler.class,
  JwtAuthenticationFilter.class
})
class EventControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockBean private EventService eventService;
  @MockBean private GoogleCalendarService googleCalendarService;
  @MockBean private JwtProvider jwtProvider;
  @MockBean private UserRepository userRepository;

  private static final String EVENTS_URL = "/v1/api/events";
  private static final String VALID_TOKEN = "a-valid-jwt-token";

  private UserEntity currentUser;

  @BeforeEach
  void setUpDefaultUser() {
    currentUser = new UserEntity();
    currentUser.setId(1);
    currentUser.setEmail("attendee@example.com");
    currentUser.setUsername("attendee");
    currentUser.setFullName("Attendee One");
    currentUser.setRole(UserRole.USER);
    currentUser.setEmailVerified(true);

    when(jwtProvider.isTokenValid(VALID_TOKEN)).thenReturn(true);
    when(jwtProvider.extractEmail(VALID_TOKEN)).thenReturn(currentUser.getEmail());
    when(userRepository.findByEmailIgnoreCase(currentUser.getEmail()))
        .thenReturn(Optional.of(currentUser));
  }

  private static MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder) {
    return builder.header("Authorization", "Bearer " + VALID_TOKEN);
  }

  // =====================================================================
  // POST /v1/api/events/{postId}/rsvp
  // =====================================================================

  @Nested
  @DisplayName("POST /v1/api/events/{postId}/rsvp")
  class RsvpTests {

    @Test
    @DisplayName("shouldReturn200_whenStatusIsValid_happyPath")
    void shouldReturn200_whenStatusIsValid_happyPath() throws Exception {
      // When / Then
      mockMvc
          .perform(authed(post(EVENTS_URL + "/1/rsvp")).param("status", "GOING"))
          .andExpect(status().isOk());

      verify(eventService).rsvp(currentUser.getId(), 1, RsvpStatus.GOING);
    }

    @Test
    @DisplayName("shouldReturn400_whenStatusParamIsMissingEntirely")
    void shouldReturn400_whenStatusParamIsMissingEntirely() throws Exception {
      // When / Then — required @RequestParam with no default; handled by
      // MissingServletRequestParameterException's handler (see GlobalExceptionHandler)
      mockMvc.perform(authed(post(EVENTS_URL + "/1/rsvp"))).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("shouldReturn400_whenStatusIsNotAValidEnumValue")
    void shouldReturn400_whenStatusIsNotAValidEnumValue() throws Exception {
      // When / Then — EP: status must be one of RsvpStatus's constants
      mockMvc
          .perform(authed(post(EVENTS_URL + "/1/rsvp")).param("status", "NOT_A_REAL_STATUS"))
          .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("shouldReturn400_whenEventIsFull")
    void shouldReturn400_whenEventIsFull() throws Exception {
      // Given
      doThrow(new ValidationException("Event is full"))
          .when(eventService)
          .rsvp(anyInt(), anyInt(), any());

      // When / Then
      mockMvc
          .perform(authed(post(EVENTS_URL + "/1/rsvp")).param("status", "GOING"))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.message").value("Event is full"));
    }

    @Test
    @DisplayName("shouldReturn400_whenPostIdPathVariableIsNotANumber")
    void shouldReturn400_whenPostIdPathVariableIsNotANumber() throws Exception {
      // When / Then — EP: postId must be an Integer
      mockMvc
          .perform(authed(post(EVENTS_URL + "/not-a-number/rsvp")).param("status", "GOING"))
          .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("shouldReturn401_whenCalledWithNoAuthorizationHeader")
    void shouldReturn401_whenCalledWithNoAuthorizationHeader() throws Exception {
      // When / Then
      mockMvc
          .perform(post(EVENTS_URL + "/1/rsvp").param("status", "GOING"))
          .andExpect(status().isUnauthorized());
    }
  }

  // =====================================================================
  // GET /v1/api/events/{postId}/attendees
  // =====================================================================

  @Nested
  @DisplayName("GET /v1/api/events/{postId}/attendees")
  class GetAttendeesTests {

    @Test
    @DisplayName("shouldReturn200AndAttendeeList_happyPath")
    void shouldReturn200AndAttendeeList_happyPath() throws Exception {
      // Given
      EventAttendeeDto attendee =
          EventAttendeeDto.builder()
              .userId(2)
              .fullName("Nguyen Truc")
              .profilePictureUrl("https://cdn/avatar.png")
              .status(RsvpStatus.GOING)
              .build();
      when(eventService.getAttendees(1, null)).thenReturn(List.of(attendee));

      // When / Then — the identity fields are the point of the DTO: with the bare JPA entity the
      // caller got a userId it had no endpoint to resolve.
      mockMvc
          .perform(authed(get(EVENTS_URL + "/1/attendees")))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$[0].userId").value(2))
          .andExpect(jsonPath("$[0].fullName").value("Nguyen Truc"))
          .andExpect(jsonPath("$[0].profilePictureUrl").value("https://cdn/avatar.png"))
          .andExpect(jsonPath("$[0].status").value("GOING"));
    }

    @Test
    @DisplayName("shouldPassStatusFilterToService_whenStatusParamIsSupplied")
    void shouldPassStatusFilterToService_whenStatusParamIsSupplied() throws Exception {
      // Given
      when(eventService.getAttendees(1, RsvpStatus.NOT_GOING)).thenReturn(List.of());

      // When / Then
      mockMvc
          .perform(authed(get(EVENTS_URL + "/1/attendees").param("status", "NOT_GOING")))
          .andExpect(status().isOk());
      verify(eventService).getAttendees(1, RsvpStatus.NOT_GOING);
    }

    @Test
    @DisplayName("shouldReturn400_whenStatusParamIsNotAValidRsvpStatus")
    void shouldReturn400_whenStatusParamIsNotAValidRsvpStatus() throws Exception {
      // When / Then
      mockMvc
          .perform(authed(get(EVENTS_URL + "/1/attendees").param("status", "MAYBE")))
          .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("shouldReturn401_whenCalledWithNoAuthorizationHeader")
    void shouldReturn401_whenCalledWithNoAuthorizationHeader() throws Exception {
      // When / Then
      mockMvc.perform(get(EVENTS_URL + "/1/attendees")).andExpect(status().isUnauthorized());
    }
  }

  // =====================================================================
  // GET /v1/api/events/{postId}/attendees/count
  // =====================================================================

  @Nested
  @DisplayName("GET /v1/api/events/{postId}/attendees/count")
  class GetAttendeeCountTests {

    @Test
    @DisplayName("shouldReturn200AndCount_happyPath")
    void shouldReturn200AndCount_happyPath() throws Exception {
      // Given
      when(eventService.getGoingCount(1)).thenReturn(7);

      // When / Then
      mockMvc
          .perform(authed(get(EVENTS_URL + "/1/attendees/count")))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.count").value(7));
    }

    @Test
    @DisplayName("shouldReturn401_whenCalledWithNoAuthorizationHeader")
    void shouldReturn401_whenCalledWithNoAuthorizationHeader() throws Exception {
      // When / Then
      mockMvc.perform(get(EVENTS_URL + "/1/attendees/count")).andExpect(status().isUnauthorized());
    }
  }

  // =====================================================================
  // POST /v1/api/events/{postId}/add-to-calendar
  // =====================================================================

  @Nested
  @DisplayName("POST /v1/api/events/{postId}/add-to-calendar")
  class AddToGoogleCalendarTests {

    @Test
    @DisplayName("shouldReturn200_happyPath")
    void shouldReturn200_happyPath() throws Exception {
      // When / Then
      mockMvc.perform(authed(post(EVENTS_URL + "/1/add-to-calendar"))).andExpect(status().isOk());

      verify(eventService).addToGoogleCalendar(currentUser.getId(), 1);
    }

    @Test
    @DisplayName("shouldReturn401_whenCalledWithNoAuthorizationHeader")
    void shouldReturn401_whenCalledWithNoAuthorizationHeader() throws Exception {
      // When / Then
      mockMvc.perform(post(EVENTS_URL + "/1/add-to-calendar")).andExpect(status().isUnauthorized());
    }
  }

  // =====================================================================
  // GET /v1/api/events/{postId}/export.ics
  // =====================================================================

  @Nested
  @DisplayName("GET /v1/api/events/{postId}/export.ics")
  class ExportIcsTests {

    @Test
    @DisplayName("shouldReturn200AndCalendarFile_happyPath")
    void shouldReturn200AndCalendarFile_happyPath() throws Exception {
      // Given
      when(eventService.generateIcsFile(1)).thenReturn("BEGIN:VCALENDAR\nEND:VCALENDAR");

      // When / Then
      mockMvc
          .perform(authed(get(EVENTS_URL + "/1/export.ics")))
          .andExpect(status().isOk())
          .andExpect(header().string("Content-Disposition", "attachment; filename=event.ics"))
          .andExpect(header().stringValues("Content-Type", "text/calendar"));
    }

    @Test
    @DisplayName("shouldReturn400_whenPostIsNotAnEvent")
    void shouldReturn400_whenPostIsNotAnEvent() throws Exception {
      // Given
      when(eventService.generateIcsFile(1))
          .thenThrow(new ValidationException("Post is not an event"));

      // When / Then
      mockMvc
          .perform(authed(get(EVENTS_URL + "/1/export.ics")))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.message").value("Post is not an event"));
    }

    @Test
    @DisplayName("shouldReturn401_whenCalledWithNoAuthorizationHeader")
    void shouldReturn401_whenCalledWithNoAuthorizationHeader() throws Exception {
      // When / Then
      mockMvc.perform(get(EVENTS_URL + "/1/export.ics")).andExpect(status().isUnauthorized());
    }
  }

  // =====================================================================
  // GET /v1/api/events/google/auth-url
  // =====================================================================

  @Nested
  @DisplayName("GET /v1/api/events/google/auth-url")
  class GetGoogleAuthUrlTests {

    @Test
    @DisplayName("shouldReturn200AndUrl_happyPath")
    void shouldReturn200AndUrl_happyPath() throws Exception {
      // Given
      when(googleCalendarService.getAuthorizationUrl(currentUser.getId()))
          .thenReturn("https://accounts.google.com/o/oauth2/auth?client_id=abc");

      // When / Then
      mockMvc
          .perform(authed(get(EVENTS_URL + "/google/auth-url")))
          .andExpect(status().isOk())
          .andExpect(
              jsonPath("$.authUrl")
                  .value("https://accounts.google.com/o/oauth2/auth?client_id=abc"));
    }

    @Test
    @DisplayName("shouldReturn401_whenCalledWithNoAuthorizationHeader")
    void shouldReturn401_whenCalledWithNoAuthorizationHeader() throws Exception {
      // When / Then
      mockMvc.perform(get(EVENTS_URL + "/google/auth-url")).andExpect(status().isUnauthorized());
    }
  }

  // =====================================================================
  // GET /v1/api/events/google/callback
  // =====================================================================

  @Nested
  @DisplayName("GET /v1/api/events/google/callback")
  class GoogleCallbackTests {

    @Test
    @DisplayName("shouldReturn200_whenStateIsAValidIssuedNonce_happyPath")
    void shouldReturn200_whenStateIsAValidIssuedNonce_happyPath() throws Exception {
      // Given — the nonce redeems to the user who started the authorize flow
      when(googleCalendarService.consumeOAuthState("issued-nonce")).thenReturn(1);

      // When / Then
      mockMvc
          .perform(
              authed(get(EVENTS_URL + "/google/callback"))
                  .param("code", "auth-code-abc")
                  .param("state", "issued-nonce"))
          .andExpect(status().isOk());

      verify(googleCalendarService).handleOAuthCallback(1, "auth-code-abc");
    }

    @Test
    @DisplayName("shouldReturn403_whenStateIsAGuessedUserId")
    void shouldReturn403_whenStateIsAGuessedUserId() throws Exception {
      // Given — B1: before the fix, state WAS the user id, so this call bound the caller's
      // Google account to user 9001. The nonce was never issued, so redemption must fail.
      when(googleCalendarService.consumeOAuthState("9001"))
          .thenThrow(new ForbiddenException("Invalid or expired OAuth state"));

      // When / Then
      mockMvc
          .perform(
              get(EVENTS_URL + "/google/callback")
                  .param("code", "attacker-auth-code")
                  .param("state", "9001"))
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.message").value("Invalid or expired OAuth state"));

      // Then — no token was written for anyone
      verify(googleCalendarService, never()).handleOAuthCallback(any(), anyString());
    }

    @Test
    @DisplayName("shouldReturn403_whenStateIsNotNumeric")
    void shouldReturn403_whenStateIsNotNumeric() throws Exception {
      // Given — state is now an opaque string, so a non-numeric value is no longer a parse
      // error (400) but simply an unknown nonce (403), same as any other forged state
      when(googleCalendarService.consumeOAuthState("not-a-number"))
          .thenThrow(new ForbiddenException("Invalid or expired OAuth state"));

      // When / Then
      mockMvc
          .perform(
              get(EVENTS_URL + "/google/callback")
                  .param("code", "auth-code-abc")
                  .param("state", "not-a-number"))
          .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("shouldReturn200_whenCalledWithNoAuthorizationHeader_permitAll")
    void shouldReturn200_whenCalledWithNoAuthorizationHeader_permitAll() throws Exception {
      // Given — this is exactly how Google's browser redirect calls this endpoint: no
      // Authorization header at all, since Google doesn't have this app's bearer token.
      // SecurityConfig permitAll()s this specific path for that reason; authorisation is
      // enforced by consumeOAuthState instead.
      when(googleCalendarService.consumeOAuthState("issued-nonce")).thenReturn(1);

      // When / Then
      mockMvc
          .perform(
              get(EVENTS_URL + "/google/callback")
                  .param("code", "auth-code-abc")
                  .param("state", "issued-nonce"))
          .andExpect(status().isOk());

      verify(googleCalendarService).handleOAuthCallback(1, "auth-code-abc");
    }
  }

  // =====================================================================
  // GET /v1/api/events/google/status
  // =====================================================================

  @Nested
  @DisplayName("GET /v1/api/events/google/status")
  class GetCalendarStatusTests {

    @Test
    @DisplayName("shouldReturn200AndConnectedTrue_happyPath")
    void shouldReturn200AndConnectedTrue_happyPath() throws Exception {
      // Given
      when(googleCalendarService.isConnected(currentUser.getId())).thenReturn(true);

      // When / Then
      mockMvc
          .perform(authed(get(EVENTS_URL + "/google/status")))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.connected").value(true));
    }

    @Test
    @DisplayName("shouldReturn401_whenCalledWithNoAuthorizationHeader")
    void shouldReturn401_whenCalledWithNoAuthorizationHeader() throws Exception {
      // When / Then
      mockMvc.perform(get(EVENTS_URL + "/google/status")).andExpect(status().isUnauthorized());
    }
  }
}
