package com.socialapp.knowledge.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.socialapp.knowledge.entity.enums.PrimaryRole;

/**
 * Component (unit) tests for {@link ProfileMatchScorer}, per ISTQB CTFL v4.0.1 (Section 2.2.1
 * component testing; Section 4.2.1 equivalence partitioning over the null / empty / disjoint /
 * overlapping partitions of both input lists, and Section 4.2.2 boundary analysis on the
 * zero-overlap and total-overlap ends).
 *
 * <p>This class is small on purpose. It is the one place three modules now agree on what "similar
 * background" means, so each rule it encodes — case-insensitivity, null tolerance, de-duplication,
 * whose spelling is returned — gets an assertion of its own rather than being inferred from a
 * caller's happy path.
 */
class ProfileMatchScorerTest {

  @Nested
  @DisplayName("skillOverlap")
  class SkillOverlapTests {

    @Test
    @DisplayName("should count only the entries the two lists share")
    void shouldCountSharedEntries() {
      // Given / When / Then
      assertThat(
              ProfileMatchScorer.skillOverlap(
                  List.of("Java", "Spring", "Redis"), List.of("Java", "Redis", "Go")))
          .isEqualTo(2);
    }

    @Test
    @DisplayName("should ignore casing and surrounding whitespace on both sides")
    void shouldIgnoreCasingAndWhitespace() {
      // Given: free text typed by two different people. Treating "React" and "react" as different
      // skills was the single biggest reason the old matchmaking filter missed people.
      // When / Then
      assertThat(
              ProfileMatchScorer.skillOverlap(
                  List.of("React", "  Node.js "), List.of("react", "NODE.JS")))
          .isEqualTo(2);
    }

    @Test
    @DisplayName("should count a duplicated skill once")
    void shouldCountDuplicatesOnce() {
      // Given: a profile listing the same skill twice must not outrank one listing it once.
      // When / Then
      assertThat(ProfileMatchScorer.skillOverlap(List.of("Java", "java", "JAVA"), List.of("Java")))
          .isEqualTo(1);
    }

    @Test
    @DisplayName("should be zero for lists that share nothing")
    void shouldBeZeroForDisjointLists() {
      // Given / When / Then
      assertThat(ProfileMatchScorer.skillOverlap(List.of("Java"), List.of("Figma"))).isZero();
    }

    @Test
    @DisplayName("should be zero rather than throw when either side is null or empty")
    void shouldBeZeroForNullOrEmptyInput() {
      // Given: an unfilled tech stack and a position posted without required skills are both
      // ordinary states, not errors — callers rank on the result and 0 sorts them last.
      // When / Then
      assertThat(ProfileMatchScorer.skillOverlap(null, List.of("Java"))).isZero();
      assertThat(ProfileMatchScorer.skillOverlap(List.of("Java"), null)).isZero();
      assertThat(ProfileMatchScorer.skillOverlap(List.of(), List.of("Java"))).isZero();
      assertThat(ProfileMatchScorer.skillOverlap(List.of("Java"), List.of())).isZero();
    }

    @Test
    @DisplayName("should tolerate a null entry inside a list")
    void shouldTolerateNullEntries() {
      // Given: jsonb round-trips can produce a list holding a null element.
      List<String> withNull = Arrays.asList("Java", null);

      // When / Then
      assertThat(ProfileMatchScorer.skillOverlap(withNull, List.of("Java"))).isEqualTo(1);
    }
  }

  @Nested
  @DisplayName("matchedSkills")
  class MatchedSkillsTests {

    @Test
    @DisplayName("should return the shared entries spelled the way the first list spells them")
    void shouldReturnFirstListSpelling() {
      // Given: the first argument is always the caller's own side of the comparison, and echoing
      // a stranger's capitalisation of the caller's own words back at them reads like a bug.
      // When
      List<String> matched =
          ProfileMatchScorer.matchedSkills(List.of("Java", "React"), List.of("JAVA", "react"));

      // Then
      assertThat(matched).containsExactly("Java", "React");
    }

    @Test
    @DisplayName("should preserve the first list's order so the same match renders identically")
    void shouldPreserveOrder() {
      // Given / When
      List<String> matched =
          ProfileMatchScorer.matchedSkills(
              List.of("Redis", "Java", "Spring"), List.of("Spring", "Java", "Redis"));

      // Then
      assertThat(matched).containsExactly("Redis", "Java", "Spring");
    }

    @Test
    @DisplayName("should return an empty list when either side is null or empty")
    void shouldReturnEmptyListForNullOrEmptyInput() {
      // Given / When / Then
      assertThat(ProfileMatchScorer.matchedSkills(null, List.of("Java"))).isEmpty();
      assertThat(ProfileMatchScorer.matchedSkills(List.of("Java"), null)).isEmpty();
      assertThat(ProfileMatchScorer.matchedSkills(List.of(), List.of())).isEmpty();
    }

    @Test
    @DisplayName("should return an immutable list")
    void shouldReturnImmutableList() {
      // Given: the result is handed straight into a response DTO, so a caller must not be able to
      // mutate it out from under the scorer's contract.
      List<String> matched = ProfileMatchScorer.matchedSkills(List.of("Java"), List.of("java"));

      // When / Then
      assertThat(matched).isUnmodifiable();
    }
  }

  @Nested
  @DisplayName("sameRole")
  class SameRoleTests {

    @Test
    @DisplayName("should be true only for two identical, stated roles")
    void shouldBeTrueForIdenticalRoles() {
      // Given / When / Then
      assertThat(ProfileMatchScorer.sameRole(PrimaryRole.BACKEND, PrimaryRole.BACKEND)).isTrue();
      assertThat(ProfileMatchScorer.sameRole(PrimaryRole.BACKEND, PrimaryRole.FRONTEND)).isFalse();
    }

    @Test
    @DisplayName("should be false when either role is unstated, including both")
    void shouldBeFalseWhenEitherRoleIsNull() {
      // Given: "we both haven't said what we do" is not a match — letting two nulls compare equal
      // would float every profile-less user to the top of every ranking.
      // When / Then
      assertThat(ProfileMatchScorer.sameRole(null, PrimaryRole.BACKEND)).isFalse();
      assertThat(ProfileMatchScorer.sameRole(PrimaryRole.BACKEND, null)).isFalse();
      assertThat(ProfileMatchScorer.sameRole(null, null)).isFalse();
    }
  }
}
