package com.socialapp.notifications.services;

import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import com.socialapp.notifications.config.MailProperties;
import com.socialapp.security.config.AuthProperties;

import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class MailService {
  private final JavaMailSender mailSender;
  private final MailProperties properties;
  private final AuthProperties authProperties;

  public void sendEmail(String toEmail, String toName, String subject, String htmlBody) {
    try {
      MimeMessage message = mailSender.createMimeMessage();
      MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

      helper.setFrom(properties.getFromEmail(), properties.getFromName());
      helper.setTo(toEmail);
      helper.setSubject(subject);
      helper.setText(htmlBody, true);

      mailSender.send(message);

      log.info("Email sent to {}: {}", toEmail, subject);
    } catch (Exception e) {
      log.error("Failed to send email to {}: {}", toEmail, e.getMessage());
    }
  }

  public void sendNotificationEmail(String toEmail, String toName, String title, String body) {
    String html = buildNotificationHtml(title, body);
    sendEmail(toEmail, toName, title, html);
  }

  public void sendPasswordResetEmail(String toEmail, String toName, String token) {
    String html = buildResetPasswordEmail(token);
    sendEmail(toEmail, toName, "Reset your SocialApp password", html);
  }

  public void sendVerificationEmail(String toEmail, String toName, String token) {
    String html = buildVerificationEmail(token);
    sendEmail(toEmail, toName, "Verify your SocialApp email", html);
  }

  public void sendMagicLinkEmail(String toEmail, String toName, String token) {
    String html = buildMagicLinkEmail(token);
    sendEmail(toEmail, toName, "Your SocialApp login link", html);
  }

  private String buildVerificationEmail(String token) {
    String verifyLink = authProperties.getVerifyEmailUrl() + token;
    return """
            <!DOCTYPE html>
            <html>
            <head><meta charset="UTF-8"></head>
            <body style="font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; max-width: 600px; margin: 0 auto; padding: 20px;">
              <div style="background: #f8f9fa; border-radius: 8px; padding: 24px;">
                <h2 style="color: #1a1a1a; margin-top: 0;">Verify your email</h2>
                <p style="color: #4a4a4a; line-height: 1.6;">Welcome to SocialApp! Confirm your email address to unlock account recovery.</p>
                <p><a href="%s" style="color: #2563eb;">Verify email</a></p>
                <p style="color: #4a4a4a; line-height: 1.6;">This link expires in 24 hours.</p>
                <hr style="border: none; border-top: 1px solid #e0e0e0; margin: 20px 0;">
                <p style="color: #888; font-size: 12px;">SocialApp - Tech Community for Engineers</p>
              </div>
            </body>
            </html>
            """
        .formatted(verifyLink);
  }

  private String buildMagicLinkEmail(String token) {
    String loginLink = authProperties.getMagicLinkUrl() + token;
    return """
            <!DOCTYPE html>
            <html>
            <head><meta charset="UTF-8"></head>
            <body style="font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; max-width: 600px; margin: 0 auto; padding: 20px;">
              <div style="background: #f8f9fa; border-radius: 8px; padding: 24px;">
                <h2 style="color: #1a1a1a; margin-top: 0;">Your login link</h2>
                <p style="color: #4a4a4a; line-height: 1.6;">Click below to sign in to SocialApp. No password needed.</p>
                <p><a href="%s" style="color: #2563eb;">Log in</a></p>
                <p style="color: #4a4a4a; line-height: 1.6;">This link expires in 15 minutes and can only be used once. If you didn't request this, you can ignore this email.</p>
                <hr style="border: none; border-top: 1px solid #e0e0e0; margin: 20px 0;">
                <p style="color: #888; font-size: 12px;">SocialApp - Tech Community for Engineers</p>
              </div>
            </body>
            </html>
            """
        .formatted(loginLink);
  }

  private String buildResetPasswordEmail(String token) {
    String resetLink = authProperties.getResetPasswordUrl() + token;
    return """
            <!DOCTYPE html>
            <html>
            <head><meta charset="UTF-8"></head>
            <body style="font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; max-width: 600px; margin: 0 auto; padding: 20px;">
              <div style="background: #f8f9fa; border-radius: 8px; padding: 24px;">
                <h2 style="color: #1a1a1a; margin-top: 0;">Reset your password</h2>
                <p style="color: #4a4a4a; line-height: 1.6;">Use the link below to reset your password.</p>
                <p><a href="%s" style="color: #2563eb;">Reset password</a></p>
                <p style="color: #4a4a4a; line-height: 1.6;">This link expires in 1 hour.</p>
                <hr style="border: none; border-top: 1px solid #e0e0e0; margin: 20px 0;">
                <p style="color: #888; font-size: 12px;">SocialApp - Tech Community for Engineers</p>
              </div>
            </body>
            </html>
            """
        .formatted(resetLink);
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
