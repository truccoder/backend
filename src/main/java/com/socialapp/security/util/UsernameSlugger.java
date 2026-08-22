package com.socialapp.security.util;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

import lombok.experimental.UtilityClass;

/**
 * Turns a display name into a handle.
 *
 * <p>Mirrors the SQL in {@code V47__make_username_a_real_identifier.sql}, which backfilled the
 * accounts that predate handles. The two have to agree: a user backfilled by the migration and a
 * user created by {@code AuthService} should end up with the same handle for the same name, or the
 * same person looks like two different conventions depending on when they signed up.
 *
 * <p>Diacritics are stripped rather than transliterated phonetically — "Trần Phú Thịnh" becomes
 * "tran-phu-thinh". That is what Postgres {@code unaccent()} does in the migration and in this
 * codebase's search queries, so it is the rule already in force everywhere else.
 */
@UtilityClass
public class UsernameSlugger {

  /** Matches the handle format the API accepts from a user who chooses their own. */
  public static final String USERNAME_PATTERN = "^[a-z0-9][a-z0-9-]{2,29}$";

  private static final Pattern NON_HANDLE_CHARS = Pattern.compile("[^a-z0-9]+");
  private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");
  private static final int MIN_LENGTH = 3;
  private static final int MAX_LENGTH = 30;

  /**
   * The handle a name slugs to, or {@code null} when the name yields nothing usable (blank, or
   * punctuation only). Callers must have a fallback for {@code null} — see {@code
   * AuthService#assignUsername}, which falls back to the user id, exactly as the migration does.
   */
  public static String slugify(String fullName) {
    if (fullName == null || fullName.isBlank()) {
      return null;
    }

    // "Đ"/"đ" first, because Unicode does not consider it an accented D: it is LATIN LETTER D WITH
    // STROKE, a letter in its own right, so NFD leaves it whole and the next step would delete it
    // outright — "Đinh Bình" would slug to "inh-binh". Postgres unaccent() maps it to "d", and this
    // method has to agree with the migration that used unaccent() to backfill existing accounts,
    // or the same name produces two different handles depending on which code path created it.
    String withoutStroke = fullName.replace('Đ', 'D').replace('đ', 'd');

    // NFD splits "ầ" into "a" + combining marks, which the next step then drops. Doing it in this
    // order is what lets one rule handle every accented Latin script rather than a lookup table.
    String decomposed = Normalizer.normalize(withoutStroke, Normalizer.Form.NFD);
    String ascii = COMBINING_MARKS.matcher(decomposed).replaceAll("");
    String slug =
        NON_HANDLE_CHARS
            .matcher(ascii.toLowerCase(Locale.ROOT))
            .replaceAll("-")
            .replaceAll("(^-+)|(-+$)", "");

    if (slug.isEmpty()) {
      return null;
    }
    // Truncated to the same ceiling the format allows, so a generated handle is never one a user
    // would be forbidden from typing themselves.
    return slug.length() > MAX_LENGTH ? slug.substring(0, MAX_LENGTH) : slug;
  }

  /**
   * Whether a slug is long enough to stand as a handle on its own. A two-letter name is a real
   * name; refusing to register it, or storing a handle the format rejects, are both worse than
   * suffixing it with something that makes it long enough — which is what the caller does with the
   * user id when this returns {@code false}.
   */
  public static boolean isLongEnough(String slug) {
    return slug.length() >= MIN_LENGTH;
  }
}
