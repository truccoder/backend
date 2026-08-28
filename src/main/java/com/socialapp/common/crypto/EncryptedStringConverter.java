package com.socialapp.common.crypto;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.socialapp.common.exception.MissingConfigurationException;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;

/**
 * Encrypts a column on the way into the database and decrypts it on the way out.
 *
 * <p>For third-party OAuth credentials — the GitHub access token and the Google Calendar
 * access/refresh pair. These cannot be hashed the way this application's own tokens are (see {@code
 * TokenHasher}), because the application has to send them back to GitHub and Google; they have to
 * be recoverable, so the protection has to be encryption.
 *
 * <p><b>What is at stake is not this application's accounts.</b> A dump of {@code t_github_stats}
 * and {@code t_google_calendar_tokens} in plaintext hands over other services' credentials: the
 * GitHub token carries {@code user:email}, which reads addresses a user has hidden from their
 * public profile, and the Google token carries {@code calendar.events}, read and write. Worst of
 * all is the Google <em>refresh</em> token, which does not expire until the user revokes it — so it
 * is permanent read/write access to somebody's calendar, granted to an application, sitting in a
 * database that shares a pooler.
 *
 * <p>AES-256-GCM: authenticated, so a tampered ciphertext fails to decrypt rather than silently
 * producing wrong plaintext. A fresh 12-byte IV per value, prepended to the ciphertext — GCM is
 * catastrophically broken by IV reuse, and per-value randomness is what avoids it.
 *
 * <p><b>Reads tolerate plaintext.</b> Rows written before this existed are returned as they are
 * rather than throwing, so deploying does not break the accounts already linked; they become
 * encrypted the next time they are written. New writes are always encrypted.
 */
@Slf4j
@Converter
@Component
public class EncryptedStringConverter implements AttributeConverter<String, String> {

  private static final String TRANSFORMATION = "AES/GCM/NoPadding";
  private static final int IV_LENGTH = 12;
  private static final int TAG_LENGTH_BITS = 128;

  /** Marks a value this converter wrote, so a legacy plaintext row is recognisable on read. */
  private static final String PREFIX = "enc:v1:";

  private static final SecureRandom SECURE_RANDOM = new SecureRandom();

  private final SecretKeySpec key;

  public EncryptedStringConverter(@Value("${security.column-encryption-key:}") String configured) {
    this.key = buildKey(configured);
  }

  /**
   * Derives the AES key, or {@code null} when none is configured.
   *
   * <p>A missing key is not fatal at startup: it would stop a developer without the variable from
   * running the application at all, for a feature (linking a GitHub or Google account) they may
   * never touch. It fails at the moment it matters instead — see {@link #convertToDatabaseColumn} —
   * with the same {@code MissingConfigurationException} the rest of the codebase uses for an unset
   * secret.
   */
  private static SecretKeySpec buildKey(String configured) {
    if (configured == null || configured.isBlank()) {
      log.warn(
          "security.column-encryption-key is not set; linking a GitHub or Google account will be"
              + " refused rather than storing that provider's token in plaintext");
      return null;
    }

    byte[] raw = Base64.getDecoder().decode(configured);
    if (raw.length != 32) {
      throw new IllegalStateException(
          "security.column-encryption-key must be 32 bytes (256-bit), base64-encoded; got "
              + raw.length);
    }
    return new SecretKeySpec(raw, "AES");
  }

  @Override
  public String convertToDatabaseColumn(String attribute) {
    if (attribute == null) {
      return null;
    }
    if (key == null) {
      // Refusing is the point. Falling back to plaintext here would mean a missing environment
      // variable silently downgrades the protection, which is the failure mode this whole class
      // exists to remove.
      throw new MissingConfigurationException(
          "Cannot store a third-party token: security.column-encryption-key is not configured");
    }

    try {
      byte[] iv = new byte[IV_LENGTH];
      SECURE_RANDOM.nextBytes(iv);

      Cipher cipher = Cipher.getInstance(TRANSFORMATION);
      cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
      byte[] ciphertext = cipher.doFinal(attribute.getBytes(StandardCharsets.UTF_8));

      byte[] combined = new byte[iv.length + ciphertext.length];
      System.arraycopy(iv, 0, combined, 0, iv.length);
      System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);

      return PREFIX + Base64.getEncoder().encodeToString(combined);
    } catch (Exception e) {
      throw new IllegalStateException("Could not encrypt a token for storage", e);
    }
  }

  @Override
  public String convertToEntityAttribute(String dbData) {
    if (dbData == null) {
      return null;
    }
    if (!dbData.startsWith(PREFIX)) {
      // Written before this converter existed. Returned as-is so linked accounts keep working; the
      // next write encrypts it.
      return dbData;
    }
    if (key == null) {
      throw new MissingConfigurationException(
          "Cannot read a stored third-party token: security.column-encryption-key is not"
              + " configured");
    }

    try {
      byte[] combined = Base64.getDecoder().decode(dbData.substring(PREFIX.length()));
      byte[] iv = new byte[IV_LENGTH];
      System.arraycopy(combined, 0, iv, 0, IV_LENGTH);

      Cipher cipher = Cipher.getInstance(TRANSFORMATION);
      cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));

      byte[] plaintext = cipher.doFinal(combined, IV_LENGTH, combined.length - IV_LENGTH);
      return new String(plaintext, StandardCharsets.UTF_8);
    } catch (Exception e) {
      throw new IllegalStateException("Could not decrypt a stored token", e);
    }
  }
}
