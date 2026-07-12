package com.socialapp.notifications.services;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Properties;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;

import com.socialapp.notifications.config.MailProperties;
import com.socialapp.security.config.AuthProperties;

import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;

/**
 * Stage 5 (Experience-based Testing) — Error Guessing technique per ISTQB CTFL v4.0.1 Section
 * 4.5.3, applied to {@link MailService}.
 *
 * <p>Confirmed with the user: {@link MailService#sendEmail}'s catch-all-and-log (no rethrow) is
 * <b>intentional</b> resilience design, not a bug — a transient SMTP outage sending a
 * notification/verification email must never fail the caller's primary transaction (e.g. user
 * registration in {@code AuthService}). These tests exist to lock in that contract against the
 * concrete failure modes a real mail server can produce (timeout-flavored send failures, auth
 * rejection, malformed-address preparation failure, checked {@link MessagingException} causes,
 * and a null-message worst case), not just the generic {@code RuntimeException} already covered
 * in {@code MailServiceTest}.
 *
 * <p>{@link JavaMailSender} is mocked — no real SMTP connection is ever attempted.
 */
@ExtendWith(MockitoExtension.class)
class MailServiceErrorGuessingTest {

  @Mock private JavaMailSender mailSender;

  private MailService mailService;

  @BeforeEach
  void setUp() {
    MailProperties mailProperties = new MailProperties();
    mailProperties.setFromEmail("noreply@socialapp.test");
    mailProperties.setFromName("SocialApp");

    AuthProperties authProperties = new AuthProperties();
    authProperties.setVerifyEmailUrl("https://app.test/verify-email?token=");
    authProperties.setResetPasswordUrl("https://app.test/reset-password?token=");
    authProperties.setMagicLinkUrl("https://app.test/magic-login?token=");

    mailService = new MailService(mailSender, mailProperties, authProperties);
  }

  private MimeMessage realMimeMessage() {
    return new MimeMessage(Session.getInstance(new Properties()));
  }

  @Nested
  @DisplayName("SMTP server outages")
  class SmtpOutageTests {

    @Test
    @DisplayName("shouldNotThrow_whenSendTimesOut")
    void shouldNotThrow_whenSendTimesOut() {
      // Given — SMTP server accepted the connection but never responded (Spring wraps the
      // underlying socket timeout in MailSendException)
      when(mailSender.createMimeMessage()).thenReturn(realMimeMessage());
      doThrow(
              new MailSendException(
                  "Mail server connection failed",
                  new java.net.SocketTimeoutException("Read timed out")))
          .when(mailSender)
          .send(any(MimeMessage.class));

      // When / Then — a single failed email must never fail the caller's flow (e.g. registration)
      assertThatCode(
              () -> mailService.sendVerificationEmail("user@example.com", "Alice", "token-123"))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("shouldNotThrow_whenConnectionIsRefused")
    void shouldNotThrow_whenConnectionIsRefused() {
      // Given — SMTP host unreachable (container down, firewall, wrong port)
      when(mailSender.createMimeMessage()).thenReturn(realMimeMessage());
      doThrow(
              new MailSendException(
                  "Mail server connection failed",
                  new java.net.ConnectException("Connection refused")))
          .when(mailSender)
          .send(any(MimeMessage.class));

      // When / Then
      assertThatCode(
              () -> mailService.sendPasswordResetEmail("user@example.com", "Alice", "token-123"))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("shouldNotThrow_whenAuthenticationIsRejected")
    void shouldNotThrow_whenAuthenticationIsRejected() {
      // Given — wrong SMTP credentials or a revoked app password, a realistic prod misconfig
      when(mailSender.createMimeMessage()).thenReturn(realMimeMessage());
      doThrow(new MailAuthenticationException("Authentication failed"))
          .when(mailSender)
          .send(any(MimeMessage.class));

      // When / Then
      assertThatCode(() -> mailService.sendMagicLinkEmail("user@example.com", "Alice", "token-123"))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("shouldNotThrow_whenRecipientAddressIsRejectedDuringPreparation")
    void shouldNotThrow_whenRecipientAddressIsRejectedDuringPreparation() {
      // Given — MimeMessageHelper.setTo() itself can throw for a malformed address, before
      // send() is ever reached; still inside the same try/catch
      when(mailSender.createMimeMessage())
          .thenThrow(new MailPreparationException("Failed to prepare message"));

      // When / Then
      assertThatCode(
              () -> mailService.sendNotificationEmail("not-an-email", "Alice", "Title", "Body"))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("shouldNotThrow_whenSendFailureCauseIsACheckedMessagingException")
    void shouldNotThrow_whenSendFailureCauseIsACheckedMessagingException() {
      // Given — jakarta.mail's checked exception hierarchy, wrapped as Spring's runtime
      // MailSendException the same way the real JavaMailSenderImpl does
      when(mailSender.createMimeMessage()).thenReturn(realMimeMessage());
      doThrow(
              new MailSendException(
                  "Mail server connection failed",
                  new MessagingException("535 Authentication failed")))
          .when(mailSender)
          .send(any(MimeMessage.class));

      // When / Then
      assertThatCode(
              () -> mailService.sendVerificationEmail("user@example.com", "Alice", "token-123"))
          .doesNotThrowAnyException();
      verify(mailSender).send(any(MimeMessage.class));
    }

    @Test
    @DisplayName("shouldNotThrowNullPointerException_whenSendExceptionHasNullMessage")
    void shouldNotThrowNullPointerException_whenSendExceptionHasNullMessage() {
      // Given — worst-case third-party exception carrying no message at all
      when(mailSender.createMimeMessage()).thenReturn(realMimeMessage());
      doThrow(new MailSendException((String) null, null))
          .when(mailSender)
          .send(any(MimeMessage.class));

      // When / Then — log.error(..., e.getMessage()) must not itself NPE
      assertThatCode(
              () -> mailService.sendVerificationEmail("user@example.com", "Alice", "token-123"))
          .doesNotThrowAnyException();
    }
  }
}
