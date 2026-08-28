package com.socialapp.security.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Component (unit) tests for {@link UsernameSlugger}, per ISTQB CTFL v4.0.1 (Section 2.2.1
 * component testing, Section 4.2.1 equivalence partitioning over the name shapes, Section 4.2.2
 * boundary values around the 3/30 character limits).
 */
class UsernameSluggerTest {

  @Nested
  @DisplayName("slugify")
  class Slugify {

    @ParameterizedTest
    @CsvSource({
      "Ada Lovelace, ada-lovelace",
      "Trần Phú Thịnh, tran-phu-thinh",
      "Nguyễn Văn Việt, nguyen-van-viet",
      "  spaced   out  , spaced-out",
      "O'Brien-Smith, o-brien-smith",
      "ĐINH BÌNH, dinh-binh"
    })
    @DisplayName("strips diacritics, lowercases and joins with single hyphens")
    void slugifiesNames(String fullName, String expected) {
      // When / Then — "Đ" is the case Java's NFD normalisation does NOT decompose on its own,
      // so it is here deliberately rather than as one more happy-path row.
      assertThat(UsernameSlugger.slugify(fullName)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "!!!", "---", "@#$%"})
    @DisplayName("returns null when the name yields nothing usable, so the caller can fall back")
    void returnsNullForUnusableNames(String fullName) {
      // When / Then
      assertThat(UsernameSlugger.slugify(fullName)).isNull();
    }

    @Test
    @DisplayName("returns null for a null name")
    void returnsNullForNull() {
      // When / Then
      assertThat(UsernameSlugger.slugify(null)).isNull();
    }

    @Test
    @DisplayName("never produces a handle longer than the format allows")
    void truncatesAtTheFormatCeiling() {
      // Given: a name well past the 30-character ceiling
      String slug = UsernameSlugger.slugify("Bartholomew Maximilian Wolfeschlegelsteinhausen");

      // Then — a generated handle a user would be forbidden from typing themselves would be a
      // handle they could never change to, or re-register with
      assertThat(slug).hasSize(30);
      assertThat(Pattern.matches(UsernameSlugger.USERNAME_PATTERN, slug)).isTrue();
    }

    @Test
    @DisplayName("produces handles that satisfy the pattern users are held to")
    void outputMatchesTheAcceptedPattern() {
      // When / Then
      assertThat(
              Pattern.matches(UsernameSlugger.USERNAME_PATTERN, UsernameSlugger.slugify("Lê Hà")))
          .isTrue();
    }
  }

  @Nested
  @DisplayName("isLongEnough")
  class IsLongEnough {

    @Test
    @DisplayName("rejects a slug below the minimum, so the caller suffixes the user id")
    void rejectsShortSlug() {
      // Given: "Le" slugs to "le", two characters — a real name the format would reject
      assertThat(UsernameSlugger.isLongEnough("le")).isFalse();
    }

    @Test
    @DisplayName("accepts a slug at or above the minimum")
    void acceptsLongEnoughSlug() {
      // When / Then — boundary: exactly 3 characters is already valid
      assertThat(UsernameSlugger.isLongEnough("ada")).isTrue();
      assertThat(UsernameSlugger.isLongEnough("adam")).isTrue();
    }
  }
}
