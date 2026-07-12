package com.socialapp.posts.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
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

import com.socialapp.common.exception.ValidationException;
import com.socialapp.posts.entity.EventRsvpEntity;
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
 * <p>{@code state} is parsed with {@code Integer.parseInt(state)}; a non-numeric {@code state}
 * now maps to 400 via {@code GlobalExceptionHandler}'s {@code NumberFormatException} handler
 * (previously fell through to the generic {@code Exception} handler as 500).
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
      EventRsvpEntity rsvp = new EventRsvpEntity();
      rsvp.setPostId(1);
      rsvp.setUserId(2);
      rsvp.setStatus(RsvpStatus.GOING);
      when(eventService.getAttendees(1)).thenReturn(List.of(rsvp));

      // When / Then
      mockMvc
          .perform(authed(get(EVENTS_URL + "/1/attendees")))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$[0].userId").value(2))
          .andExpect(jsonPath("$[0].status").value("GOING"));
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
    @DisplayName("shouldReturn200_whenCodeAndStateAreValid_happyPath")
    void shouldReturn200_whenCodeAndStateAreValid_happyPath() throws Exception {
      // When / Then
      mockMvc
          .perform(
              authed(get(EVENTS_URL + "/google/callback"))
                  .param("code", "auth-code-abc")
                  .param("state", "1"))
          .andExpect(status().isOk());

      verify(googleCalendarService).handleOAuthCallback(1, "auth-code-abc");
    }

    @Test
    @DisplayName("shouldReturn400_whenStateIsNotNumeric")
    void shouldReturn400_whenStateIsNotNumeric() throws Exception {
      // Given — state is parsed with Integer.parseInt(state); NumberFormatException now has a
      // dedicated handler in GlobalExceptionHandler
      mockMvc
          .perform(
              get(EVENTS_URL + "/google/callback")
                  .param("code", "auth-code-abc")
                  .param("state", "not-a-number"))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.message").value("Invalid numeric value in request"));
    }

    @Test
    @DisplayName("shouldReturn200_whenCalledWithNoAuthorizationHeader_permitAll")
    void shouldReturn200_whenCalledWithNoAuthorizationHeader_permitAll() throws Exception {
      // Given — this is exactly how Google's browser redirect calls this endpoint: no
      // Authorization header at all, since Google doesn't have this app's bearer token.
      // SecurityConfig now permitAll()s this specific path for that reason.

      // When / Then
      mockMvc
          .perform(
              get(EVENTS_URL + "/google/callback")
                  .param("code", "auth-code-abc")
                  .param("state", "1"))
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
