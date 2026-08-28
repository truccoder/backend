package com.socialapp.common.utils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import lombok.experimental.UtilityClass;

/**
 * Hashes a bearer secret for storage.
 *
 * <p>Refresh tokens, magic links, password-reset and email-verification tokens are all secrets that
 * arrive from the client and are looked up by exact value. Storing them as they arrive means any
 * read of the database — a backup, a pooler credential, a future SQL injection — hands over
 * credentials that work immediately: a refresh token good for seven days, or a magic link that is
 * exchanged for a session with no password at all. Stored as a hash, the same dump is inert.
 *
 * <p><b>SHA-256, not bcrypt, and that is the right choice here.</b> Password hashing is deliberately
 * slow because passwords are low-entropy and guessable. These tokens are 122+ bits from
 * {@code SecureRandom}, so there is nothing to guess and no dictionary to run — the only thing
 * needed is that the stored form cannot be reversed. A slow hash on a lookup that happens on every
 * token refresh would cost real latency for no security gain. This is the same reasoning, and the
 * same algorithm, that {@code PersonalAccessTokenService} already applies to API tokens.
 */
@UtilityClass
public class TokenHasher {

  /** The hex-encoded SHA-256 of {@code token}. Stable, so it can be used as a primary key. */
  public static String hash(String token) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      // SHA-256 is guaranteed on every conformant JVM, so this is unreachable at runtime.
      throw new IllegalStateException("SHA-256 is unavailable", e);
    }
  }
}
