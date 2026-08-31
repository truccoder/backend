package com.socialapp.hashtags;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Component (unit) tests for {@link HashtagNormalizer}, per ISTQB CTFL v4.0.1 (Section 4.2
 * equivalence partitioning / boundary value analysis).
 *
 * <p>This is the one place the query side and the composer side agree on what string a hashtag is,
 * so every folding rule that {@code PostService.processHashtags} applies on write has a row here.
 */
class HashtagNormalizerTest {

  @ParameterizedTest
  @CsvSource({
    "'#ReactHooks', reacthooks",
    "'reacthooks', reacthooks",
    "'  ReactHooks  ', reacthooks",
    "'#java', java",
    "'##java', java",
    "'# java', java",
    "'JAVA', java",
    "'spring_boot', spring_boot"
  })
  @DisplayName("folds # prefix, surrounding space and case to the stored form")
  void folds(String raw, String expected) {
    assertThat(HashtagNormalizer.normalize(raw)).isEqualTo(expected);
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"   ", "#", "##", "  #  ", "#\t"})
  @DisplayName("returns null when nothing survives folding, i.e. no filter")
  void nothingLeft(String raw) {
    assertThat(HashtagNormalizer.normalize(raw)).isNull();
  }

  @Test
  @DisplayName("does not strip characters that simply will not match a row")
  void keepsNonWordCharacters() {
    // c++ folds to c++, matches no t_hashtags row, returns an empty result — the right answer.
    // Stripping would turn it into "c", a different populated tag the user never asked for.
    assertThat(HashtagNormalizer.normalize("C++")).isEqualTo("c++");
  }
}
