package com.socialapp.notifications.controller;

import org.springframework.data.domain.Page;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.socialapp.common.utils.Constants;
import com.socialapp.notifications.dto.NotificationPreferenceResponseDto;
import com.socialapp.notifications.dto.NotificationResponseDto;
import com.socialapp.notifications.dto.UnreadCountResponse;
import com.socialapp.notifications.dto.UpdatePreferenceRequestDto;
import com.socialapp.notifications.services.NotificationService;
import com.socialapp.notifications.sse.NotificationStreamService;
import com.socialapp.security.util.SecurityUtils;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/v1/api/notifications")
@RequiredArgsConstructor
public class NotificationController {
  private final NotificationService notificationService;
  private final NotificationStreamService notificationStreamService;

  @GetMapping
  public Page<NotificationResponseDto> getNotifications(
      @RequestParam(defaultValue = Constants.DEFAULT_PAGINATION_PAGE) @Positive int page,
      @RequestParam(defaultValue = Constants.DEFAULT_PAGINATION_PAGE_SIZE) @Positive int size) {
    return notificationService.getNotifications(SecurityUtils.getCurrentUserId(), page, size);
  }

  /**
   * A stream of this user's notifications, held open until the client goes away.
   *
   * <p>The bell's polling loop is what this replaces — see {@link NotificationStreamService}. The
   * caller must present the usual {@code Authorization} header, which means the browser's native
   * {@code EventSource} cannot be used; that is deliberate, and the reasoning is in that class.
   *
   * <p>{@code produces} is stated explicitly rather than inferred: content negotiation on a
   * handler returning {@code SseEmitter} otherwise depends on what the client sent in {@code
   * Accept}, and a client that asks for JSON gets a 406 on an endpoint that plainly is not JSON.
   */
  @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter stream() {
    return notificationStreamService.subscribe(SecurityUtils.getCurrentUserId());
  }

  @GetMapping("/unread-count")
  public UnreadCountResponse getUnreadCount() {
    return new UnreadCountResponse(
        notificationService.getUnreadCount(SecurityUtils.getCurrentUserId()));
  }

  @PostMapping("/{id}/read")
  public void markAsRead(@PathVariable Integer id) {
    notificationService.markAsRead(SecurityUtils.getCurrentUserId(), id);
  }

  @PostMapping("/read-all")
  public void markAllAsRead() {
    notificationService.markAllAsRead(SecurityUtils.getCurrentUserId());
  }

  @GetMapping("/preferences")
  public NotificationPreferenceResponseDto getPreferences() {
    return notificationService.getPreference(SecurityUtils.getCurrentUserId());
  }

  @PutMapping("/preferences")
  public NotificationPreferenceResponseDto updatePreferences(
      @Valid @RequestBody UpdatePreferenceRequestDto request) {
    return notificationService.updatePreference(SecurityUtils.getCurrentUserId(), request);
  }
}
