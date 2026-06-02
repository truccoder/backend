package com.socialapp.notifications.controller;

import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.socialapp.common.utils.Constants;
import com.socialapp.notifications.dto.NotificationResponseDto;
import com.socialapp.notifications.dto.UpdatePreferenceRequestDto;
import com.socialapp.notifications.entity.NotificationPreferenceEntity;
import com.socialapp.notifications.services.NotificationService;

import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/v1/api/notifications")
@RequiredArgsConstructor
public class NotificationController {
  private final NotificationService notificationService;

  @GetMapping
  public Page<NotificationResponseDto> getNotifications(
      @RequestHeader("X-User-Id") Integer userId,
      @RequestParam(defaultValue = Constants.DEFAULT_PAGINATION_PAGE) @Positive int page,
      @RequestParam(defaultValue = Constants.DEFAULT_PAGINATION_PAGE_SIZE) @Positive int size) {
    return notificationService.getNotifications(userId, page, size);
  }

  @GetMapping("/unread-count")
  public UnreadCountResponse getUnreadCount(@RequestHeader("X-User-Id") Integer userId) {
    return new UnreadCountResponse(notificationService.getUnreadCount(userId));
  }

  @PostMapping("/{id}/read")
  public void markAsRead(@RequestHeader("X-User-Id") Integer userId, @PathVariable Integer id) {
    notificationService.markAsRead(userId, id);
  }

  @PostMapping("/read-all")
  public void markAllAsRead(@RequestHeader("X-User-Id") Integer userId) {
    notificationService.markAllAsRead(userId);
  }

  @GetMapping("/preferences")
  public NotificationPreferenceEntity getPreferences(@RequestHeader("X-User-Id") Integer userId) {
    return notificationService.getPreference(userId);
  }

  @PutMapping("/preferences")
  public NotificationPreferenceEntity updatePreferences(
      @RequestHeader("X-User-Id") Integer userId, @RequestBody UpdatePreferenceRequestDto request) {
    return notificationService.updatePreference(userId, request);
  }

  record UnreadCountResponse(int count) {}
}
