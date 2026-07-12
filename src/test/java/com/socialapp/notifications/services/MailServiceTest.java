package com.socialapp.notifications.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
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
import org.springframework.mail.javamail.JavaMailSender;

import com.socialapp.notifications.config.MailProperties;
import com.socialapp.security.config.AuthProperties;

import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;

/**
 * Component (unit) tests for {@link MailService}, per ISTQB CTFL v4.0.1 (Section 2.2.1 component
 * testing, Section 5.1.6 test pyramid, Section 2.1.3 BDD Given/When/Then) — see {@code
 * PostServiceTest} for the full rationale.
 *
 * <p>Unlike most services in this project, every public method here is straight-line code (a
 * single try/catch with no if/else, ternary, or loop) — {@code try/catch} is exception-handling
 * metadata, not a conditional jump, so JaCoCo counts zero branches for this class (compare {@code
 * TokenService}, which has the same property). These tests exist for behavioral correctness and
 * line/method coverage, not to satisfy a branch-coverage requirement that doesn't apply here.
 *
 * <p>{@link JavaMailSender} is mocked, but {@code createMimeMessage()} returns a real {@link
 * MimeMessage} backed by a real, in-memory {@link Session} (not a Mockito mock) — {@code
 * MimeMessageHelper} calls real JavaMail APIs internally that a mock can't satisfy without
 * throwing, and a real Session-backed message lets the tests inspect the actual resulting
 * subject/recipients/body afterward.
 */
@ExtendWith(MockitoExtension.class)
class MailServiceTest {

  @Mock private JavaMailSender mailSender;

  private MailProperties mailProperties;
  private AuthProperties authProperties;
  private MailService mailService;

  @BeforeEach
  void setUp() {
    mailProperties = new MailProperties();
    mailProperties.setFromEmail("noreply@socialapp.test");
    mailProperties.setFromName("SocialApp");

    authProperties = new AuthProperties();
    authProperties.setResetPasswordUrl("https://app.test/reset-password?token=");
    authProperties.setVerifyEmailUrl("https://app.test/verify-email?token=");
    authProperties.setMagicLinkUrl("https://app.test/magic-login?token=");

    mailService = new MailService(mailSender, mailProperties, authProperties);
  }

  private MimeMessage realMimeMessage() {
    return new MimeMessage(Session.getInstance(new Properties()));
  }

  /**
   * {@code MimeMessageHelper} is constructed with {@code multipart=true}, so the message's
   * content is a (possibly multiply-nested) {@link MimeMultipart} rather than a plain {@code
   * String} — recurse through it the same way a real mail client would to find the HTML body.
   */
  private static String htmlBodyOf(MimeMessage message) throws Exception {
    return extractText(message.getContent());
  }

  private static String extractText(Object content) throws Exception {
    if (content instanceof String text) {
      return text;
    }
    if (content instanceof MimeMultipart multipart) {
      for (int i = 0; i < multipart.getCount(); i++) {
        String text = extractText(multipart.getBodyPart(i).getContent());
        if (text != null) {
          return text;
        }
      }
    }
    return null;
  }

  // =====================================================================
  // sendNotificationEmail
  // =====================================================================

  @Nested
  @DisplayName("sendNotificationEmail")
  class SendNotificationEmailTests {

    @Test
    @DisplayName("should build and send an email with the given title and body")
    void shouldSendEmail() throws Exception {
      // Given
      MimeMessage message = realMimeMessage();
      when(mailSender.createMimeMessage()).thenReturn(message);

      // When
      mailService.sendNotificationEmail(
          "user@example.com", "Alice", "New like", "Someone liked your post");

      // Then
      verify(mailSender).send(message);
      assertThat(message.getSubject()).isEqualTo("New like");
      assertThat(((InternetAddress) message.getAllRecipients()[0]).getAddress())
          .isEqualTo("user@example.com");
      assertThat(htmlBodyOf(message)).contains("Someone liked your post");
    }

    @Test
    @DisplayName("should swallow the error and not propagate when sending fails")
    void shouldSwallowException_whenSendFails() {
      // Given
      when(mailSender.createMimeMessage()).thenReturn(realMimeMessage());
      doThrow(new RuntimeException("smtp down"))
          .when(mailSender)
          .send((MimeMessage) org.mockito.ArgumentMatchers.any());

      // When / Then
      assertThatCode(
              () -> mailService.sendNotificationEmail("user@example.com", "Alice", "Title", "Body"))
          .doesNotThrowAnyException();
    }
  }

  // =====================================================================
  // sendPasswordResetEmail
  // =====================================================================

  @Nested
  @DisplayName("sendPasswordResetEmail")
  class SendPasswordResetEmailTests {

    @Test
    @DisplayName("should embed the reset link built from the token")
    void shouldBuildAndSendResetEmail_containingToken() throws Exception {
      // Given
      MimeMessage message = realMimeMessage();
      when(mailSender.createMimeMessage()).thenReturn(message);

      // When
      mailService.sendPasswordResetEmail("user@example.com", "Alice", "reset-token-123");

      // Then
      assertThat(message.getSubject()).isEqualTo("Reset your SocialApp password");
      assertThat(htmlBodyOf(message))
          .contains("https://app.test/reset-password?token=reset-token-123");
    }
  }

  // =====================================================================
  // sendVerificationEmail
  // =====================================================================

  @Nested
  @DisplayName("sendVerificationEmail")
  class SendVerificationEmailTests {

    @Test
    @DisplayName("should embed the verification link built from the token")
    void shouldBuildAndSendVerificationEmail_containingToken() throws Exception {
      // Given
      MimeMessage message = realMimeMessage();
      when(mailSender.createMimeMessage()).thenReturn(message);

      // When
      mailService.sendVerificationEmail("user@example.com", "Alice", "verify-token-123");

      // Then
      assertThat(message.getSubject()).isEqualTo("Verify your SocialApp email");
      assertThat(htmlBodyOf(message))
          .contains("https://app.test/verify-email?token=verify-token-123");
    }
  }

  // =====================================================================
  // sendMagicLinkEmail
  // =====================================================================

  @Nested
  @DisplayName("sendMagicLinkEmail")
  class SendMagicLinkEmailTests {

    @Test
    @DisplayName("should embed the magic-login link built from the token")
    void shouldBuildAndSendMagicLinkEmail_containingToken() throws Exception {
      // Given
      MimeMessage message = realMimeMessage();
      when(mailSender.createMimeMessage()).thenReturn(message);

      // When
      mailService.sendMagicLinkEmail("user@example.com", "Alice", "magic-token-123");

      // Then
      assertThat(message.getSubject()).isEqualTo("Your SocialApp login link");
      assertThat(htmlBodyOf(message))
          .contains("https://app.test/magic-login?token=magic-token-123");
    }
  }
}
