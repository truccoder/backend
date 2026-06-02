package com.socialapp.notifications.services;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Objects;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialapp.notifications.dto.NotificationResponseDto;
import com.socialapp.notifications.dto.SendNotificationRequest;
import com.socialapp.notifications.dto.UpdatePreferenceRequestDto;
import com.socialapp.notifications.entity.NotificationEntity;
import com.socialapp.notifications.entity.NotificationPreferenceEntity;
import com.socialapp.notifications.entity.enums.NotificationChannel;
import com.socialapp.notifications.repository.NotificationPreferenceRepository;
import com.socialapp.notifications.repository.NotificationRepository;
import com.socialapp.security.entity.UserEntity;
import com.socialapp.security.repository.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {
  private final NotificationRepository notificationRepository;
  private final NotificationPreferenceRepository preferenceRepository;
  private final PushNotificationService pushService;
  private final MailService mailService;
  private final UserRepository userRepository;

  @Async
  public void send(SendNotificationRequest request) {
    NotificationPreferenceEntity prefs = getOrCreatePreference(request.getRecipientId());

    if (isTypeMuted(prefs, request.getType().name())) {
      log.debug(
          "Notification type {} muted for user {}", request.getType(), request.getRecipientId());
      return;
    }

    NotificationEntity entity = saveNotification(request);

    NotificationChannel channel = request.getChannel();

    if (shouldSendPush(channel, prefs)) {
      pushService.sendToPlayer(
          prefs.getOnesignalPlayerId(),
          request.getTitle(),
          request.getBody(),
          Objects.requireNonNullElse(request.getPushData(), Map.of()));
      entity.setSentAt(OffsetDateTime.now());
    }

    if (shouldSendEmail(channel, prefs)) {
      UserEntity recipient = userRepository.findById(request.getRecipientId()).orElse(null);
      if (Objects.nonNull(recipient) && Objects.nonNull(recipient.getEmail())) {
        mailService.sendNotificationEmail(
            recipient.getEmail(),
            Objects.toString(recipient.getFullName(), "User"),
            request.getTitle(),
            request.getBody());
        entity.setSentAt(OffsetDateTime.now());
      }
    }

    notificationRepository.save(entity);
  }

  public Page<NotificationResponseDto> getNotifications(Integer userId, int page, int size) {
    return notificationRepository
        .findByRecipientIdOrderByCreatedAtDesc(userId, PageRequest.of(page - 1, size))
        .map(this::toDto);
  }

  public int getUnreadCount(Integer userId) {
    return notificationRepository.countByRecipientIdAndIsReadFalse(userId);
  }

  @Transactional
  public void markAsRead(Integer userId, Integer notificationId) {
    notificationRepository
        .findById(notificationId)
        .ifPresent(
            n -> {
              if (n.getRecipientId().equals(userId)) {
                n.setIsRead(true);
                notificationRepository.save(n);
              }
            });
  }

  @Transactional
  public void markAllAsRead(Integer userId) {
    notificationRepository.markAllAsRead(userId);
  }

  public NotificationPreferenceEntity updatePreference(
      Integer userId, UpdatePreferenceRequestDto request) {
    NotificationPreferenceEntity pref = getOrCreatePreference(userId);

    if (Objects.nonNull(request.getPushEnabled())) pref.setPushEnabled(request.getPushEnabled());
    if (Objects.nonNull(request.getEmailEnabled())) pref.setEmailEnabled(request.getEmailEnabled());
    if (Objects.nonNull(request.getOnesignalPlayerId()))
      pref.setOnesignalPlayerId(request.getOnesignalPlayerId());
    if (Objects.nonNull(request.getEmailFrequency()))
      pref.setEmailFrequency(request.getEmailFrequency());
    if (Objects.nonNull(request.getMutedTypes())) pref.setMutedTypes(request.getMutedTypes());

    return preferenceRepository.save(pref);
  }

  public NotificationPreferenceEntity getPreference(Integer userId) {
    return getOrCreatePreference(userId);
  }

  private NotificationEntity saveNotification(SendNotificationRequest request) {
    NotificationEntity entity =
        NotificationEntity.builder()
            .recipientId(request.getRecipientId())
            .actorId(request.getActorId())
            .type(request.getType())
            .title(request.getTitle())
            .body(request.getBody())
            .referenceId(request.getReferenceId())
            .referenceType(request.getReferenceType())
            .channel(request.getChannel())
            .build();
    return notificationRepository.save(entity);
  }

  private NotificationPreferenceEntity getOrCreatePreference(Integer userId) {
    return preferenceRepository
        .findByUserId(userId)
        .orElseGet(
            () -> {
              NotificationPreferenceEntity newPref =
                  NotificationPreferenceEntity.builder().userId(userId).build();
              return preferenceRepository.save(newPref);
            });
  }

  private boolean shouldSendPush(NotificationChannel channel, NotificationPreferenceEntity prefs) {
    return (NotificationChannel.PUSH.equals(channel) || NotificationChannel.BOTH.equals(channel))
        && Boolean.TRUE.equals(prefs.getPushEnabled())
        && Objects.nonNull(prefs.getOnesignalPlayerId());
  }

  private boolean shouldSendEmail(NotificationChannel channel, NotificationPreferenceEntity prefs) {
    return (NotificationChannel.EMAIL.equals(channel) || NotificationChannel.BOTH.equals(channel))
        && Boolean.TRUE.equals(prefs.getEmailEnabled());
  }

  private boolean isTypeMuted(NotificationPreferenceEntity prefs, String type) {
    return Objects.nonNull(prefs.getMutedTypes()) && prefs.getMutedTypes().contains(type);
  }

  private NotificationResponseDto toDto(NotificationEntity entity) {
    return NotificationResponseDto.builder()
        .id(entity.getId())
        .actorId(entity.getActorId())
        .type(entity.getType())
        .title(entity.getTitle())
        .body(entity.getBody())
        .referenceId(entity.getReferenceId())
        .referenceType(entity.getReferenceType())
        .channel(entity.getChannel())
        .isRead(entity.getIsRead())
        .createdAt(entity.getCreatedAt())
        .build();
  }
}
