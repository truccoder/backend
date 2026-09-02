package com.socialapp.notifications.services;

import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

import com.socialapp.notifications.config.MailProperties;
import com.socialapp.security.config.AuthProperties;

import jakarta.mail.internet.InternetAddress;
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

  /**
   * An address in a form that identifies a support case without publishing the address.
   *
   * <p>{@code someone@example.com} becomes {@code s*****@example.com}: enough to match against a
   * user's report, not enough to be a mailing list if the log store leaks.
   */
  private static String mask(String email) {
    if (email == null || email.isBlank()) {
      return "(none)";
    }
    int at = email.indexOf('@');
    if (at <= 0) {
      return "***";
    }
    return email.charAt(0) + "*****" + email.substring(at);
  }

  public void sendEmail(String toEmail, String toName, String subject, String htmlBody) {
    try {
      MimeMessage message = mailSender.createMimeMessage();
      MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

      helper.setFrom(properties.getFromEmail(), properties.getFromName());
      helper.setTo(new InternetAddress(toEmail, toName, "UTF-8"));
      helper.setSubject(subject);
      helper.setText(htmlBody, true);

      mailSender.send(message);

      // The address is masked and the line is DEBUG. Production logs com.socialapp at INFO and
      // ships them to Axiom, so logging the recipient verbatim sent every address the system ever
      // mails — registration, password reset, magic link, notifications — to a third party, where
      // it sits outside any deletion request. The same file that configures those logs already
      // pins three Hibernate loggers down to WARN for precisely this reason.
      log.debug("Email sent to {}: {}", mask(toEmail), subject);
    } catch (Exception e) {
      // `e` as the last argument, not e.getMessage(). SLF4J prints the stack trace only for the
      // former, and MailException's message rarely distinguishes a timeout from a rejected
      // credential from an SMTP refusal — which made mail failures in production undiagnosable.
      // Still swallowed rather than rethrown: the callers are @Async, so there is nobody upstream
      // to catch it. WARN with the address masked is the record that a send was lost.
      log.warn("Failed to send email to {} (subject: {})", mask(toEmail), subject, e);
    }
  }

  @Async
  public void sendNotificationEmail(String toEmail, String toName, String title, String body) {
    String html = buildNotificationHtml(HtmlUtils.htmlEscape(title), HtmlUtils.htmlEscape(body));
    sendEmail(toEmail, toName, title, html);
  }

  @Async
  public void sendPasswordResetEmail(String toEmail, String toName, String token) {
    String html = buildResetPasswordEmail(token);
    sendEmail(toEmail, toName, "Reset your SocialApp password", html);
  }

  @Async
  public void sendVerificationEmail(String toEmail, String toName, String token) {
    String html = buildVerificationEmail(token);
    sendEmail(toEmail, toName, "Verify your SocialApp email", html);
  }

  @Async
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
