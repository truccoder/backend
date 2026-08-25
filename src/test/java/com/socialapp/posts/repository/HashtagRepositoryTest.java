package com.socialapp.posts.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import com.socialapp.AbstractIntegrationTest;
import com.socialapp.posts.entity.HashtagEntity;

/**
 * Component integration tests for {@link HashtagRepository} against a real PostgreSQL instance
 * (Testcontainers), per ISTQB CTFL v4.0.1 Section 2.2.1.
 *
 * <p>{@code createMissing} and {@code incrementUsage} are native queries that bind a {@code
 * String[]} into {@code CAST(:names AS text[])} and lean on {@code ON CONFLICT (name)}. Neither the
 * array binding nor the upsert can be exercised by a mocked {@code PostServiceTest}, so they are
 * verified here — a binding failure would otherwise only show up as a 500 on the first post
 * containing a hashtag.
 */
@Transactional
class HashtagRepositoryTest extends AbstractIntegrationTest {

  @Autowired private HashtagRepository hashtagRepository;

  private static String[] names(String... names) {
    return names;
  }

  @Nested
  @DisplayName("createMissing")
  class CreateMissingTests {

    @Test
    @DisplayName("inserts the tags that do not exist yet")
    void insertsNewTags() {
      // GIVEN two tag names nothing has used before
      String[] fresh = names("brandnewtag1", "brandnewtag2");

      // WHEN the batch is created
      hashtagRepository.createMissing(fresh);

      // THEN both rows exist, with a zeroed counter
      List<HashtagEntity> saved = hashtagRepository.findByNameIn(Set.of(fresh));
      assertThat(saved)
          .extracting(HashtagEntity::getName)
          .containsExactlyInAnyOrder("brandnewtag1", "brandnewtag2");
      assertThat(saved).extracting(HashtagEntity::getUsageCount).containsOnly(0);
    }

    @Test
    @DisplayName("leaves an existing tag untouched instead of failing on the unique index")
    void ignoresExistingTags() {
      // GIVEN a tag that already exists with a non-zero counter
      hashtagRepository.createMissing(names("existingtag"));
      hashtagRepository.incrementUsage(names("existingtag"));

      // WHEN the same name is created again alongside a new one
      hashtagRepository.createMissing(names("existingtag", "companiontag"));

      // THEN the existing row keeps its counter and the new one is added
      List<HashtagEntity> saved =
          hashtagRepository.findByNameIn(Set.of("existingtag", "companiontag"));
      assertThat(saved).hasSize(2);
      assertThat(saved)
          .filteredOn(tag -> tag.getName().equals("existingtag"))
          .extracting(HashtagEntity::getUsageCount)
          .containsExactly(1);
    }
  }

  @Nested
  @DisplayName("incrementUsage")
  class IncrementUsageTests {

    @Test
    @DisplayName("bumps every named tag by one and ignores names with no row")
    void incrementsNamedTags() {
      // GIVEN two tags, one of which is bumped twice
      hashtagRepository.createMissing(names("counted1", "counted2"));

      // WHEN the counters are bumped in the database
      hashtagRepository.incrementUsage(names("counted1", "counted2", "nosuchtag"));
      hashtagRepository.incrementUsage(names("counted1"));

      // THEN each tag carries the number of bumps it received
      List<HashtagEntity> saved = hashtagRepository.findByNameIn(Set.of("counted1", "counted2"));
      assertThat(saved)
          .filteredOn(tag -> tag.getName().equals("counted1"))
          .extracting(HashtagEntity::getUsageCount)
          .containsExactly(2);
      assertThat(saved)
          .filteredOn(tag -> tag.getName().equals("counted2"))
          .extracting(HashtagEntity::getUsageCount)
          .containsExactly(1);
    }
  }

  @Nested
  @DisplayName("decrementUsage")
  class DecrementUsageTests {

    @Test
    @DisplayName("takes one off every named tag")
    void decrementsNamedTags() {
      // GIVEN a tag used twice and one used once
      hashtagRepository.createMissing(names("dropped1", "dropped2"));
      hashtagRepository.incrementUsage(names("dropped1", "dropped2"));
      hashtagRepository.incrementUsage(names("dropped1"));

      // WHEN a post that carried both is edited away from them
      hashtagRepository.decrementUsage(names("dropped1", "dropped2"));

      // THEN each counter is one lower
      List<HashtagEntity> saved = hashtagRepository.findByNameIn(Set.of("dropped1", "dropped2"));
      assertThat(saved)
          .filteredOn(tag -> tag.getName().equals("dropped1"))
          .extracting(HashtagEntity::getUsageCount)
          .containsExactly(1);
      assertThat(saved)
          .filteredOn(tag -> tag.getName().equals("dropped2"))
          .extracting(HashtagEntity::getUsageCount)
          .containsExactly(0);
    }

    @Test
    @DisplayName("floors a counter at zero instead of going negative")
    void neverGoesNegative() {
      // GIVEN a tag whose counter has already drifted to zero
      hashtagRepository.createMissing(names("floored"));

      // WHEN it is decremented twice more
      hashtagRepository.decrementUsage(names("floored"));
      hashtagRepository.decrementUsage(names("floored"));

      // THEN it stays at zero
      assertThat(hashtagRepository.findByName("floored"))
          .get()
          .extracting(HashtagEntity::getUsageCount)
          .isEqualTo(0);
    }
  }
}
