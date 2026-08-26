package com.socialapp.posts.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Component tests for {@link MentionScanner}, per ISTQB CTFL v4.0.1 Section 2.2.1.
 *
 * <p>The false positives are the point of this class. Whatever this returns turns into a
 * notification sent to a real person, so a pattern that is slightly too generous does not produce a
 * cosmetic bug — it rings a stranger's bell because somebody quoted an email address.
 */
class MentionScannerTest {

  @Nested
  @DisplayName("scan — text that does mention somebody")
  class FoundTests {

    @Test
    @DisplayName("should find a handle at the very start of the text")
    void shouldFindAtStart() {
      // Given / When / Then — the ordinary case: the clients prefill the reply box with the handle
      assertThat(MentionScanner.scan("@ada đúng rồi đó")).containsExactly("ada");
    }

    @Test
    @DisplayName("should find a handle after whitespace, including after a newline")
    void shouldFindAfterWhitespace() {
      // Given / When / Then
      assertThat(MentionScanner.scan("cảm ơn @ada nhé")).containsExactly("ada");
      assertThat(MentionScanner.scan("dòng một\n@ada dòng hai")).containsExactly("ada");
    }

    @Test
    @DisplayName("should keep the sentence's full stop out of the handle")
    void shouldNotSwallowATrailingPeriod() {
      // Given — a period is not in the handle character set, so "@ada." ends the handle at "ada"
      // and the sentence keeps its punctuation. Without this every mention at the end of a
      // sentence would resolve to a handle nobody holds.
      assertThat(MentionScanner.scan("hỏi @ada.")).containsExactly("ada");
      assertThat(MentionScanner.scan("hỏi @ada, @bob và @cleo!"))
          .containsExactly("ada", "bob", "cleo");
    }

    @Test
    @DisplayName("should accept the digits and hyphens a real handle can contain")
    void shouldAcceptTheFullHandleCharset() {
      // Given — V47 slugs names into lowercase letters, digits and hyphens, so "tran-phu-thinh"
      // is what a Vietnamese full name actually becomes
      assertThat(MentionScanner.scan("@tran-phu-thinh xem giúp")).containsExactly("tran-phu-thinh");
      assertThat(MentionScanner.scan("@user-9026 ơi")).containsExactly("user-9026");
    }

    @Test
    @DisplayName("should accept the underscore every seeded handle actually contains")
    void shouldAcceptUnderscores() {
      // Given — the charset here is deliberately one character wider than
      // UsernameSlugger.USERNAME_PATTERN, which governs what may be REGISTERED. What the username
      // column holds is a different set: every account in the seed carries an underscore, and
      // /u/{username} resolves them because routing is a lookup with no format rule attached.
      // Held to the stricter pattern this would silently find none of them.
      assertThat(MentionScanner.scan("@backend_truc_anh xem giúp mình"))
          .containsExactly("backend_truc_anh");
    }

    @Test
    @DisplayName("should lower-case what it finds, since people type names capitalised")
    void shouldLowerCaseTheHandle() {
      // Given — a stored handle is always lower-case, but somebody writing about Ada types "@Ada"
      assertThat(MentionScanner.scan("@Ada @BOB")).containsExactly("ada", "bob");
    }

    @Test
    @DisplayName("should report each handle once, in the order it first appears")
    void shouldDeduplicateInReadingOrder() {
      // Given — order is what makes the cap predictable, and one person named three times is one
      // notification, not three
      assertThat(MentionScanner.scan("@bob @ada @bob lại @bob")).containsExactly("bob", "ada");
    }
  }

  @Nested
  @DisplayName("scan — text that does not")
  class NotFoundTests {

    @Test
    @DisplayName("should ignore the @ inside an email address")
    void shouldIgnoreEmailAddresses() {
      // Given — the reason the lookbehind exists. "ai@example.com" contains "@example", and
      // without the rule every comment quoting an address would notify whoever holds that handle.
      assertThat(MentionScanner.scan("gửi tới ai@example.com nhé")).isEmpty();
      assertThat(MentionScanner.scan("support@socialapp.com")).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
        strings = {
          "@ab", // two characters: below the 3-character floor
          "@-ada", // a handle may not start with a hyphen
          "@", // nothing at all
          "@ ada", // a space after the @
          "@Ánh" // outside the ASCII handle charset, which V47 guarantees
        })
    @DisplayName("should ignore text that cannot be a handle")
    void shouldIgnoreNonHandles(String text) {
      // Given / When / Then — EP: too short, starting with a separator, or outside the ASCII
      // charset the username column is guaranteed to hold. None of these could be a handle, so
      // none of them is a mention.
      assertThat(MentionScanner.scan(text)).isEmpty();
    }

    @Test
    @DisplayName("should stop a 31-character run at the 30-character ceiling, not reject it")
    void shouldTruncateAtTheCeiling_boundary() {
      // Given — BVA on the 30-character maximum. The extra character is simply not part of the
      // match, and the resulting handle then fails to resolve against the user table, which is
      // the right outcome: silence, not a notification for a near-miss.
      String thirtyOne = "a".repeat(31);

      // When / Then
      assertThat(MentionScanner.scan("@" + thirtyOne)).containsExactly("a".repeat(30));
    }

    @Test
    @DisplayName("should return an empty set for null, blank or plain text")
    void shouldHandleEmptyInput() {
      // When / Then
      assertThat(MentionScanner.scan(null)).isEmpty();
      assertThat(MentionScanner.scan("   ")).isEmpty();
      assertThat(MentionScanner.scan("không có tag nào ở đây")).isEmpty();
    }
  }

  @Nested
  @DisplayName("scan — the blast-radius cap")
  class CapTests {

    @Test
    @DisplayName("should return exactly ten handles when eleven are named_boundary")
    void shouldCapAtTen() {
      // Given — BVA on MAX_MENTIONS. Nothing limits how many handles fit in a comment body, so
      // without the cap one comment is a broadcast channel built out of a reply box.
      StringBuilder text = new StringBuilder();
      for (int i = 0; i < 11; i++) {
        text.append("@user-").append(i).append(' ');
      }

      // When
      var handles = MentionScanner.scan(text.toString());

      // Then — the first ten in reading order, so the people named at the top of a long comment
      // are the ones who hear about it
      assertThat(handles).hasSize(MentionScanner.MAX_MENTIONS);
      assertThat(handles)
          .containsExactly(
              "user-0", "user-1", "user-2", "user-3", "user-4", "user-5", "user-6", "user-7",
              "user-8", "user-9");
    }

    @Test
    @DisplayName("should count distinct handles, not occurrences, against the cap")
    void shouldCountDistinctHandles() {
      // Given — one name repeated twenty times is one person
      String text = "@ada ".repeat(20) + "@bob";

      // When / Then
      assertThat(MentionScanner.scan(text)).containsExactly("ada", "bob");
    }
  }

  @Nested
  @DisplayName("isMentionable — what the @-dropdown may offer")
  class IsMentionableTests {

    @Test
    @DisplayName("should accept every shape of handle scan() finds")
    void shouldAcceptRealHandles() {
      // Given / When / Then — the dropdown and the scanner have to agree, or a suggested name
      // produces a tag that notifies nobody
      assertThat(MentionScanner.isMentionable("ada")).isTrue();
      assertThat(MentionScanner.isMentionable("backend_truc_anh")).isTrue();
      assertThat(MentionScanner.isMentionable("tran-phu-thinh")).isTrue();
      assertThat(MentionScanner.isMentionable("user2")).isTrue();
    }

    @Test
    @DisplayName("should reject a handle one character below the floor_boundary")
    void shouldRejectTooShort() {
      // BVA on the 3-character minimum: "Ly" slugs to a handle scan() would never find
      assertThat(MentionScanner.isMentionable("ly")).isFalse();
      assertThat(MentionScanner.isMentionable("lyn")).isTrue();
    }

    @Test
    @DisplayName("should reject a handle one character above the ceiling_boundary")
    void shouldRejectTooLong() {
      // BVA on the 30-character maximum
      assertThat(MentionScanner.isMentionable("a".repeat(30))).isTrue();
      assertThat(MentionScanner.isMentionable("a".repeat(31))).isFalse();
    }

    @Test
    @DisplayName("should reject characters scan() would cut the handle short at")
    void shouldRejectForeignCharacters() {
      // A stored handle with a period is real enough, but "@minh.tran" scans as "minh" — so
      // offering it would tag the wrong person, or nobody
      assertThat(MentionScanner.isMentionable("minh.tran")).isFalse();
      assertThat(MentionScanner.isMentionable("-leading")).isFalse();
      assertThat(MentionScanner.isMentionable("has space")).isFalse();
    }

    @Test
    @DisplayName("should answer false for null rather than throwing")
    void shouldAnswerFalseForNull() {
      // The column is NOT NULL since V47, but a caller filtering a list should not have to know
      assertThat(MentionScanner.isMentionable(null)).isFalse();
      assertThat(MentionScanner.isMentionable("")).isFalse();
    }
  }
}
