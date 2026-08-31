package com.socialapp.posts.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import com.socialapp.common.utils.RedirectUri;

import lombok.Data;

@Data
@Component
@ConfigurationProperties(prefix = "google.calendar")
public class GoogleCalendarProperties {
  private String clientId;
  private String clientSecret;
  private String redirectUri;

  /**
   * Collapses a doubled slash from {@code ${PUBLIC_URL}/...} so Google's byte-for-byte
   * {@code redirect_uri} check does not fail with {@code redirect_uri_mismatch}.
   */
  public void setRedirectUri(String redirectUri) {
    this.redirectUri = RedirectUri.normalize(redirectUri);
  }

  private String tokenUrl = "https://oauth2.googleapis.com/token";
  private String authUrl = "https://accounts.google.com/o/oauth2/v2/auth";
  private String calendarApiUrl = "https://www.googleapis.com/calendar/v3";
  private String scope = "https://www.googleapis.com/auth/calendar.events";
}
