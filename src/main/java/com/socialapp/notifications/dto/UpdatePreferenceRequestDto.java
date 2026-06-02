package com.socialapp.notifications.dto;

import java.util.List;

import com.socialapp.notifications.entity.enums.EmailFrequency;

import lombok.Data;

@Data
public class UpdatePreferenceRequestDto {
  private Boolean pushEnabled;
  private Boolean emailEnabled;
  private String onesignalPlayerId;
  private EmailFrequency emailFrequency;
  private List<String> mutedTypes;
}
