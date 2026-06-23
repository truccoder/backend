package com.socialapp.notifications.controller;

import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

import com.socialapp.common.utils.Constants;
import com.socialapp.notifications.dto.NotificationResponseDto;
import com.socialapp.notifications.dto.UpdatePreferenceRequestDto;
import com.socialapp.notifications.entity.NotificationPreferenceEntity;
import com.socialapp.notifications.services.NotificationService;
import com.socialapp.security.util.SecurityUtils;

import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/v1/api/notifications")
@RequiredArgsConstructor
public class NotificationController {
  private final NotificationService notificationService;

  @GetMapping
  public Page<NotificationResponseDto> getNotifications(
      @RequestParam(defaultValue = Constants.DEFAULT_PAGINATION_PAGE) @Positive int page,
      @RequestParam(defaultValue = Constants.DEFAULT_PAGINATION_PAGE_SIZE) @Positive int size) {
    return notificationService.getNotifications(SecurityUtils.getCurrentUserId(), page, size);
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
  public NotificationPreferenceEntity getPreferences() {
    return notificationService.getPreference(SecurityUtils.getCurrentUserId());
  }

  @PutMapping("/preferences")
  public NotificationPreferenceEntity updatePreferences(
      @RequestBody UpdatePreferenceRequestDto request) {
    return notificationService.updatePreference(SecurityUtils.getCurrentUserId(), request);
  }

  record UnreadCountResponse(int count) {}
}
