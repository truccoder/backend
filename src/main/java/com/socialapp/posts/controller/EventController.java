package com.socialapp.posts.controller;

import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.socialapp.posts.entity.EventRsvpEntity;
import com.socialapp.posts.entity.enums.RsvpStatus;
import com.socialapp.posts.service.EventService;
import com.socialapp.posts.service.GoogleCalendarService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/v1/api/events")
@RequiredArgsConstructor
public class EventController {
  private final EventService eventService;
  private final GoogleCalendarService googleCalendarService;

  @PostMapping("/{postId}/rsvp")
  public void rsvp(
      @RequestHeader("X-User-Id") Integer userId,
      @PathVariable Integer postId,
      @RequestParam RsvpStatus status) {
    eventService.rsvp(userId, postId, status);
  }

  @GetMapping("/{postId}/attendees")
  public List<EventRsvpEntity> getAttendees(@PathVariable Integer postId) {
    return eventService.getAttendees(postId);
  }

  @GetMapping("/{postId}/attendees/count")
  public AttendeeCountResponse getAttendeeCount(@PathVariable Integer postId) {
    return new AttendeeCountResponse(eventService.getGoingCount(postId));
  }

  @PostMapping("/{postId}/add-to-calendar")
  public void addToGoogleCalendar(
      @RequestHeader("X-User-Id") Integer userId, @PathVariable Integer postId) {
    eventService.addToGoogleCalendar(userId, postId);
  }

  @GetMapping("/{postId}/export.ics")
  public ResponseEntity<byte[]> exportIcs(@PathVariable Integer postId) {
    String ics = eventService.generateIcsFile(postId);
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=event.ics")
        .contentType(MediaType.parseMediaType("text/calendar"))
        .body(ics.getBytes());
  }

  @GetMapping("/google/auth-url")
  public AuthUrlResponse getGoogleAuthUrl(@RequestHeader("X-User-Id") Integer userId) {
    return new AuthUrlResponse(googleCalendarService.getAuthorizationUrl(userId));
  }

  @GetMapping("/google/callback")
  public void handleGoogleCallback(@RequestParam String code, @RequestParam String state) {
    Integer userId = Integer.parseInt(state);
    googleCalendarService.handleOAuthCallback(userId, code);
  }

  @GetMapping("/google/status")
  public CalendarStatusResponse getCalendarStatus(@RequestHeader("X-User-Id") Integer userId) {
    return new CalendarStatusResponse(googleCalendarService.isConnected(userId));
  }

  record AttendeeCountResponse(int count) {}

  record AuthUrlResponse(String authUrl) {}

  record CalendarStatusResponse(boolean connected) {}
}
