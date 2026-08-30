package com.socialapp.posts.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import com.socialapp.AbstractIntegrationTest;
import com.socialapp.hashtags.dto.HashtagDto;
import com.socialapp.moderation.enums.ModerationStatus;
import com.socialapp.posts.entity.HashtagEntity;
import com.socialapp.posts.entity.PostEntity;
import com.socialapp.posts.entity.enums.PostVisibility;
import com.socialapp.security.entity.UserEntity;
import com.socialapp.security.repository.UserRepository;

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
  @Autowired private PostRepository postRepository;
  @Autowired private UserRepository userRepository;

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

  @Nested
  @DisplayName("findByNameStartingWithAndUsageCountGreaterThanOrderByUsageCountDescNameAsc")
  class PrefixSuggest {

    // Names are prefixed so they cannot collide with the six tags V41 seeds into t_hashtags.

    @Test
    @DisplayName("returns only the prefix matches, most-used first, name breaking ties")
    void ordersByUsageThenName() {
      // GIVEN four tags, three sharing the "b31java" prefix
      hashtagRepository.saveAll(
          List.of(
              tag("b31javascript", 5),
              tag("b31java", 40),
              tag("b31javafx", 40),
              tag("b31python", 99)));
      hashtagRepository.flush();

      // WHEN the "b31java" prefix is completed
      List<HashtagEntity> result =
          hashtagRepository
              .findByNameStartingWithAndUsageCountGreaterThanOrderByUsageCountDescNameAsc(
                  "b31java", 0, PageRequest.of(0, 10));

      // THEN b31python is absent, and the tie between b31java/b31javafx breaks on the name
      assertThat(result)
          .extracting(HashtagEntity::getName)
          .containsExactly("b31java", "b31javafx", "b31javascript");
    }

    @Test
    @DisplayName("leaves out tags no post carries (usage_count 0)")
    void excludesDeadTags() {
      hashtagRepository.saveAll(List.of(tag("b31live", 3), tag("b31dead", 0)));
      hashtagRepository.flush();

      List<HashtagEntity> result =
          hashtagRepository
              .findByNameStartingWithAndUsageCountGreaterThanOrderByUsageCountDescNameAsc(
                  "b31", 0, PageRequest.of(0, 10));

      assertThat(result).extracting(HashtagEntity::getName).containsExactly("b31live");
    }

    @Test
    @DisplayName("treats an underscore in the prefix as a literal, not a wildcard")
    void underscoreIsLiteral() {
      // GIVEN a tag with an underscore and a look-alike without one
      hashtagRepository.saveAll(List.of(tag("b31spring_boot", 3), tag("b31springboot", 3)));
      hashtagRepository.flush();

      // WHEN a prefix containing the underscore is completed
      List<HashtagEntity> result =
          hashtagRepository
              .findByNameStartingWithAndUsageCountGreaterThanOrderByUsageCountDescNameAsc(
                  "b31spring_", 0, PageRequest.of(0, 10));

      // THEN the "_" did not act as "match any character"
      assertThat(result).extracting(HashtagEntity::getName).containsExactly("b31spring_boot");
    }

    @Test
    @DisplayName("honours the page size")
    void honoursLimit() {
      hashtagRepository.saveAll(List.of(tag("b31goa", 3), tag("b31gob", 2), tag("b31goc", 1)));
      hashtagRepository.flush();

      assertThat(
              hashtagRepository
                  .findByNameStartingWithAndUsageCountGreaterThanOrderByUsageCountDescNameAsc(
                      "b31go", 0, PageRequest.of(0, 2)))
          .extracting(HashtagEntity::getName)
          .containsExactly("b31goa", "b31gob");
    }
  }

  @Nested
  @DisplayName("findTrendingSince")
  class TrendingSince {

    @Test
    @DisplayName("counts a tag once per matching post and orders by that count")
    void countsAndOrders() {
      Integer authorId =
          userRepository.saveAndFlush(user("trend-author@example.com", "trendauthor")).getId();

      HashtagEntity java = hashtagRepository.saveAndFlush(tag("b31java", 0));
      HashtagEntity kotlin = hashtagRepository.saveAndFlush(tag("b31kotlin", 0));

      // two public+approved posts on #b31java, one also on #b31kotlin
      savePost(authorId, PostVisibility.PUBLIC, ModerationStatus.APPROVED, java);
      savePost(authorId, PostVisibility.PUBLIC, ModerationStatus.APPROVED, java, kotlin);

      List<HashtagDto> result =
          hashtagRepository.findTrendingSince(
              OffsetDateTime.now().minusDays(1), PageRequest.of(0, 10));

      assertThat(result)
          .containsExactly(new HashtagDto("b31java", 2), new HashtagDto("b31kotlin", 1));
    }

    @Test
    @DisplayName("ignores friends-only and not-yet-approved posts")
    void ignoresNonPublicOrUnapproved() {
      Integer authorId =
          userRepository.saveAndFlush(user("trend-author2@example.com", "trendauthor2")).getId();
      HashtagEntity tag = hashtagRepository.saveAndFlush(tag("b31rust", 0));

      savePost(authorId, PostVisibility.FRIENDS, ModerationStatus.APPROVED, tag);
      savePost(authorId, PostVisibility.PUBLIC, ModerationStatus.PENDING_REVIEW, tag);

      assertThat(
              hashtagRepository.findTrendingSince(
                  OffsetDateTime.now().minusDays(1), PageRequest.of(0, 10)))
          .noneMatch(row -> row.tag().equals("b31rust"));
    }

    @Test
    @DisplayName("excludes posts created before the window opens")
    void excludesOlderThanSince() {
      Integer authorId =
          userRepository.saveAndFlush(user("trend-author3@example.com", "trendauthor3")).getId();
      HashtagEntity tag = hashtagRepository.saveAndFlush(tag("b31elixir", 0));
      savePost(authorId, PostVisibility.PUBLIC, ModerationStatus.APPROVED, tag);

      // window opens in the future, so nothing created "now" is inside it
      assertThat(
              hashtagRepository.findTrendingSince(
                  OffsetDateTime.now().plusDays(1), PageRequest.of(0, 10)))
          .isEmpty();
    }

    private void savePost(
        Integer authorId,
        PostVisibility visibility,
        ModerationStatus status,
        HashtagEntity... tags) {
      PostEntity post = new PostEntity();
      post.setAuthorId(authorId);
      post.setContent("trending post");
      post.setVisibility(visibility);
      post.setModerationStatus(status);
      for (HashtagEntity tag : tags) {
        post.getHashtags().add(tag);
      }
      postRepository.saveAndFlush(post);
    }
  }

  private static HashtagEntity tag(String name, Integer usageCount) {
    HashtagEntity entity = new HashtagEntity();
    entity.setName(name);
    entity.setUsageCount(usageCount);
    return entity;
  }

  private static UserEntity user(String email, String username) {
    UserEntity user = new UserEntity();
    user.setEmail(email);
    user.setPassword("hashed-password");
    user.setUsername(username);
    user.setFullName("Trend Author");
    return user;
  }
}
