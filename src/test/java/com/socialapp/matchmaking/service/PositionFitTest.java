package com.socialapp.matchmaking.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.socialapp.knowledge.entity.UserProfessionalProfileEntity;
import com.socialapp.knowledge.entity.enums.SeniorityLevel;
import com.socialapp.matchmaking.entity.ProjectPositionEntity;

/**
 * Component (unit) tests for {@link PositionFit}, per ISTQB CTFL v4.0.1 (Section 2.2.1 component
 * testing; Section 4.2.1 equivalence partitioning over the null / below / at / above partitions of
 * each gate, and Section 4.3.2 branch coverage of {@code qualified()}).
 *
 * <p>The gates are the point of this class. Each one is a way the endpoints previously suggested
 * something they should not have, so each gets its own case rather than being folded into a happy
 * path — and each is tested from both sides of the asymmetry: absent on the <em>role</em> means
 * "does not ask", absent on the <em>person</em> means "cannot be shown to clear it".
 */
class PositionFitTest {

  private static ProjectPositionEntity role(String... requiredSkills) {
    ProjectPositionEntity position = new ProjectPositionEntity();
    position.setRequiredSkills(List.of(requiredSkills));
    return position;
  }

  private static UserProfessionalProfileEntity person(String... techStack) {
    UserProfessionalProfileEntity profile = new UserProfessionalProfileEntity();
    profile.setUserId(11);
    profile.setKnownTechStack(List.of(techStack));
    return profile;
  }

  @Nested
  @DisplayName("skill coverage")
  class SkillCoverage {

    @Test
    @DisplayName("should qualify on exactly half of the required skills")
    void shouldQualifyAtTheBoundary() {
      PositionFit fit = PositionFit.forCandidate(role("Java", "Spring"), person("Java", "Figma"));

      assertThat(fit.qualified()).isTrue();
      assertThat(fit.coveragePercent()).isEqualTo(50);
      assertThat(fit.fullCoverage()).isFalse();
    }

    @Test
    @DisplayName("should not qualify below half, rounding the bar up")
    void shouldRejectBelowTheBar() {
      // Three required skills means two are needed: ceil(3 * 0.5).
      PositionFit fit = PositionFit.forCandidate(role("Java", "Spring", "Redis"), person("Java"));

      assertThat(fit.qualified()).isFalse();
    }

    @Test
    @DisplayName("should still need one skill when the role asks for only one")
    void shouldNeverDropToZero() {
      assertThat(PositionFit.forCandidate(role("Java"), person("Go")).qualified()).isFalse();
      assertThat(PositionFit.forCandidate(role("Java"), person("Java")).qualified()).isTrue();
    }

    @Test
    @DisplayName("should never qualify for a role that lists no required skills")
    void shouldRejectSkilllessRoles() {
      // The positions that predate V105. With nothing to match on, "this person fits" is a claim
      // with no evidence behind it — and answering otherwise is what made the old endpoint's
      // empty results look like a bug rather than an honest nothing.
      ProjectPositionEntity legacy = new ProjectPositionEntity();

      PositionFit fit = PositionFit.forCandidate(legacy, person("Java"));

      assertThat(fit.qualified()).isFalse();
      assertThat(fit.coveragePercent()).isZero();
    }

    @Test
    @DisplayName("should compare skills case-insensitively but report each side's own spelling")
    void shouldEchoTheRightSpelling() {
      ProjectPositionEntity position = role("Java", "React");
      UserProfessionalProfileEntity profile = person("java", "REACT");

      // The owner reads the candidate list, so it speaks their vocabulary...
      assertThat(PositionFit.forCandidate(position, profile).matchedSkills())
          .containsExactly("Java", "React");
      // ...and the job seeker reads the project list, so that one speaks theirs.
      assertThat(PositionFit.forSeeker(position, profile).matchedSkills())
          .containsExactly("java", "REACT");
    }
  }

  @Nested
  @DisplayName("experience bar")
  class ExperienceBar {

    @Test
    @DisplayName("should let everyone through when the role states no bar")
    void shouldPassWhenUnset() {
      PositionFit fit = PositionFit.forCandidate(role("Java"), person("Java"));

      assertThat(fit.meetsExperienceBar()).isTrue();
      assertThat(fit.qualified()).isTrue();
    }

    @Test
    @DisplayName("should treat the bar as a floor, not an equality check")
    void shouldPassAtOrAboveTheBar() {
      ProjectPositionEntity position = role("Java");
      position.setMinYearsExperience(3);

      UserProfessionalProfileEntity exactly = person("Java");
      exactly.setYearsOfExperience(3);
      UserProfessionalProfileEntity below = person("Java");
      below.setYearsOfExperience(2);

      assertThat(PositionFit.forCandidate(position, exactly).qualified()).isTrue();
      assertThat(PositionFit.forCandidate(position, below).qualified()).isFalse();
    }

    @Test
    @DisplayName("should refuse someone who never stated their experience once a bar is set")
    void shouldRejectUnknownExperience() {
      ProjectPositionEntity position = role("Java");
      position.setMinYearsExperience(3);

      assertThat(PositionFit.forCandidate(position, person("Java")).qualified()).isFalse();
    }
  }

  @Nested
  @DisplayName("seniority bar")
  class SeniorityBar {

    private static UserProfessionalProfileEntity at(SeniorityLevel level) {
      UserProfessionalProfileEntity profile = person("Java");
      profile.setSeniorityLevel(level);
      return profile;
    }

    @Test
    @DisplayName("should accept the stated level and anything above it")
    void shouldRankByDeclarationOrder() {
      ProjectPositionEntity position = role("Java");
      position.setSeniorityLevel(SeniorityLevel.SENIOR);

      assertThat(PositionFit.forCandidate(position, at(SeniorityLevel.MID)).qualified()).isFalse();
      assertThat(PositionFit.forCandidate(position, at(SeniorityLevel.SENIOR)).qualified())
          .isTrue();
      assertThat(PositionFit.forCandidate(position, at(SeniorityLevel.LEAD)).qualified()).isTrue();
    }

    @Test
    @DisplayName("should refuse an unstated level once the role asks for one")
    void shouldRejectUnknownSeniority() {
      ProjectPositionEntity position = role("Java");
      position.setSeniorityLevel(SeniorityLevel.JUNIOR);

      assertThat(PositionFit.forCandidate(position, person("Java")).qualified()).isFalse();
    }

    @Test
    @DisplayName("should ignore the candidate's level when the role states none")
    void shouldPassWhenUnset() {
      assertThat(PositionFit.forCandidate(role("Java"), at(SeniorityLevel.JUNIOR)).qualified())
          .isTrue();
    }
  }
}
