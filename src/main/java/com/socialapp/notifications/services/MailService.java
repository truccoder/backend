package com.socialapp.notifications.services;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import com.socialapp.notifications.config.MailTrapProperties;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class MailService {
  private final WebClient webClient;
  private final MailTrapProperties properties;

  public MailService(MailTrapProperties properties) {
    this.properties = properties;
    this.webClient =
        WebClient.builder()
            .defaultHeader("Authorization", "Bearer " + properties.getApiToken())
            .defaultHeader("Content-Type", "application/json")
            .build();
  }

  public void sendEmail(String toEmail, String toName, String subject, String htmlBody) {
    try {
      Map<String, Object> payload =
          Map.of(
              "from",
              Map.of("email", properties.getFromEmail(), "name", properties.getFromName()),
              "to",
              List.of(Map.of("email", toEmail, "name", toName)),
              "subject",
              subject,
              "html",
              htmlBody);

      webClient
          .post()
          .uri(properties.getBaseUrl())
          .bodyValue(payload)
          .retrieve()
          .bodyToMono(Map.class)
          .block();

      log.info("Email sent to {}: {}", toEmail, subject);
    } catch (Exception e) {
      log.error("Failed to send email to {}: {}", toEmail, e.getMessage());
    }
  }

  public void sendNotificationEmail(String toEmail, String toName, String title, String body) {
    String html = buildNotificationHtml(title, body);
    sendEmail(toEmail, toName, title, html);
  }

  private String buildNotificationHtml(String title, String body) {
    return """
        <!DOCTYPE html>
        <html>
        <head><meta charset="UTF-8"></head>
        <body style="font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; max-width: 600px; margin: 0 auto; padding: 20px;">
          <div style="background: #f8f9fa; border-radius: 8px; padding: 24px;">
            <h2 style="color: #1a1a1a; margin-top: 0;">%s</h2>
            <p style="color: #4a4a4a; line-height: 1.6;">%s</p>
            <hr style="border: none; border-top: 1px solid #e0e0e0; margin: 20px 0;">
            <p style="color: #888; font-size: 12px;">SocialApp - Tech Community for Engineers</p>
          </div>
        </body>
        </html>
        """
        .formatted(title, body);
  }
}
