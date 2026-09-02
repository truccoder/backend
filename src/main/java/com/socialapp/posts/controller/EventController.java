package com.socialapp.posts.controller;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.socialapp.posts.dto.EventAttendeeDto;
import com.socialapp.posts.entity.enums.RsvpStatus;
import com.socialapp.posts.service.EventService;
import com.socialapp.posts.service.GoogleCalendarService;
import com.socialapp.security.util.SecurityUtils;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/v1/api/events")
@RequiredArgsConstructor
public class EventController {
  private final EventService eventService;
  private final GoogleCalendarService googleCalendarService;

  @PostMapping("/{postId}/rsvp")
  public void rsvp(@PathVariable Integer postId, @RequestParam RsvpStatus status) {
    eventService.rsvp(SecurityUtils.getCurrentUserId(), postId, status);
  }

  /**
   * @param status optional filter; omit it to get every RSVP regardless of answer.
   */
  @GetMapping("/{postId}/attendees")
  public List<EventAttendeeDto> getAttendees(
      @PathVariable Integer postId, @RequestParam(required = false) RsvpStatus status) {
    return eventService.getAttendees(SecurityUtils.getCurrentUserId(), postId, status);
  }

  @GetMapping("/{postId}/attendees/count")
  public AttendeeCountResponse getAttendeeCount(@PathVariable Integer postId) {
    return new AttendeeCountResponse(
        eventService.getGoingCount(SecurityUtils.getCurrentUserId(), postId));
  }

  @PostMapping("/{postId}/add-to-calendar")
  public void addToGoogleCalendar(@PathVariable Integer postId) {
    eventService.addToGoogleCalendar(SecurityUtils.getCurrentUserId(), postId);
  }

  @GetMapping("/{postId}/export.ics")
  public ResponseEntity<byte[]> exportIcs(@PathVariable Integer postId) {
    String ics = eventService.generateIcsFile(SecurityUtils.getCurrentUserId(), postId);
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=event.ics")
        .contentType(MediaType.parseMediaType("text/calendar;charset=UTF-8"))
        .body(ics.getBytes(StandardCharsets.UTF_8));
  }

  @GetMapping("/google/auth-url")
  public AuthUrlResponse getGoogleAuthUrl() {
    return new AuthUrlResponse(
        googleCalendarService.getAuthorizationUrl(SecurityUtils.getCurrentUserId()));
  }

  // This endpoint is permitAll (see SecurityConfig): Google redirects the browser here and no
  // bearer token of ours can ride along. The account it acts on therefore comes from redeeming
  // the server-issued nonce, never from parsing "state" as a user id — doing the latter let any
  // caller graft their own Google account onto someone else's profile.
  @GetMapping("/google/callback")
  public void handleGoogleCallback(@RequestParam String code, @RequestParam String state) {
    Integer userId = googleCalendarService.consumeOAuthState(state);
    googleCalendarService.handleOAuthCallback(userId, code);
  }

  @GetMapping("/google/status")
  public CalendarStatusResponse getCalendarStatus() {
    return new CalendarStatusResponse(
        googleCalendarService.isConnected(SecurityUtils.getCurrentUserId()));
  }

  record AttendeeCountResponse(int count) {}

  record AuthUrlResponse(String authUrl) {}

  record CalendarStatusResponse(boolean connected) {}
}
