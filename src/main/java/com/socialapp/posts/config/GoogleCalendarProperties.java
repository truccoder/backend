package com.socialapp.posts.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

@Data
@Component
@ConfigurationProperties(prefix = "google.calendar")
public class GoogleCalendarProperties {
  private String clientId;
  private String clientSecret;
  private String redirectUri;
  private String tokenUrl = "https://oauth2.googleapis.com/token";
  private String authUrl = "https://accounts.google.com/o/oauth2/v2/auth";
  private String calendarApiUrl = "https://www.googleapis.com/calendar/v3";
  private String scope = "https://www.googleapis.com/auth/calendar.events";
}
