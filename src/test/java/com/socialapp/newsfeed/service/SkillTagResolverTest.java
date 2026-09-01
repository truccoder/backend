package com.socialapp.newsfeed.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collection;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.socialapp.posts.entity.HashtagEntity;
import com.socialapp.posts.repository.HashtagRepository;
import com.socialapp.roadmap.enums.VerificationStatus;
import com.socialapp.roadmap.repository.UserRoadmapProgressRepository;

/**
 * Component (unit) tests for {@link SkillTagResolver}, per ISTQB CTFL v4.0.1 (Section 2.2.1
 * component testing, Section 4.2.1 equivalence partitioning over the shapes a node name can take,
 * Section 2.1.3 BDD Given/When/Then).
 *
 * <p>What is under test is a <b>join between two vocabularies that were never designed against each
 * other</b> — editorial roadmap node names on one side, author-typed hashtags on the other. The
 * candidate generation is the guess; the lookup against {@code t_hashtags} is what keeps the guess
 * from reaching a reader. Both halves are pinned here.
 */
@ExtendWith(MockitoExtension.class)
class SkillTagResolverTest {

  private static final Integer USER_ID = 9001;

  @Mock private UserRoadmapProgressRepository progressRepository;
  @Mock private HashtagRepository hashtagRepository;

  @InjectMocks private SkillTagResolver resolver;

  @Captor private ArgumentCaptor<Collection<String>> candidatesCaptor;

  private static HashtagEntity tag(String name) {
    HashtagEntity entity = new HashtagEntity();
    entity.setName(name);
    return entity;
  }

  private void givenVerifiedSkills(String... names) {
    when(progressRepository.findSkillNamesByUserIdAndStatus(USER_ID, VerificationStatus.VERIFIED))
        .thenReturn(List.of(names));
  }

  @Nested
  @DisplayName("resolveTagsFor — no skills")
  class NoSkills {

    @Test
    @DisplayName(
        "should return an empty set without touching the tag table when nothing is verified")
    void shouldReturnEmptyWhenNoVerifiedSkills() {
      // Given
      givenVerifiedSkills();

      // When
      Set<String> tags = resolver.resolveTagsFor(USER_ID);

      // Then — the caller reads an empty set as "nothing matches", so there is nothing to look up
      assertThat(tags).isEmpty();
      verify(hashtagRepository, never()).findByNameIn(anyCollection());
    }
  }

  @Nested
  @DisplayName("resolveTagsFor — candidate generation")
  class CandidateGeneration {

    @Test
    @DisplayName("should offer both the joined name and each word of a two-word skill")
    void shouldOfferWholeAndParts() {
      // Given — "Spring Boot" only reaches #springboot through the joined form, and "Java Core"
      // only reaches #java through a fragment. Both shapes exist in the seeded roadmap.
      givenVerifiedSkills("Spring Boot", "Java Core");
      when(hashtagRepository.findByNameIn(any())).thenReturn(List.of());

      // When
      resolver.resolveTagsFor(USER_ID);

      // Then
      verify(hashtagRepository).findByNameIn(candidatesCaptor.capture());
      assertThat(candidatesCaptor.getValue())
          .contains("springboot", "spring", "boot", "javacore", "java", "core");
    }

    @Test
    @DisplayName("should strip Vietnamese diacritics, including the đ that NFD leaves alone")
    void shouldFoldVietnameseDiacritics() {
      // Given — EP: a node name written in Vietnamese. đ has no combining form, so NFD alone
      // would leave "Đóng gói" as "ónggói" and the fold would silently produce nothing usable.
      givenVerifiedSkills("Đóng gói và triển khai");
      when(hashtagRepository.findByNameIn(any())).thenReturn(List.of());

      // When
      resolver.resolveTagsFor(USER_ID);

      // Then
      verify(hashtagRepository).findByNameIn(candidatesCaptor.capture());
      assertThat(candidatesCaptor.getValue()).contains("dong", "goi", "trien", "khai");
      assertThat(candidatesCaptor.getValue()).allMatch(c -> c.matches("[a-z0-9]+"));
    }

    @Test
    @DisplayName("should drop fragments shorter than three characters (boundary: 2 vs 3)")
    void shouldDropShortFragments() {
      // Given — BVA on MIN_FRAGMENT_LENGTH: "10" and "và" are noise, "top" is not
      givenVerifiedSkills("OWASP Top 10");
      when(hashtagRepository.findByNameIn(any())).thenReturn(List.of());

      // When
      resolver.resolveTagsFor(USER_ID);

      // Then
      verify(hashtagRepository).findByNameIn(candidatesCaptor.capture());
      assertThat(candidatesCaptor.getValue()).contains("owasp", "top", "owasptop10");
      assertThat(candidatesCaptor.getValue()).doesNotContain("10");
    }

    @Test
    @DisplayName("should drop punctuation rather than let it split a name into nothing")
    void shouldDropPunctuation() {
      // Given — EP: a name carrying a separator that is not whitespace
      givenVerifiedSkills("CI/CD");
      when(hashtagRepository.findByNameIn(any())).thenReturn(List.of());

      // When
      resolver.resolveTagsFor(USER_ID);

      // Then — "ci/cd" is one word to the splitter, so only the folded whole survives the length
      // filter; that is the form a hashtag would take anyway
      verify(hashtagRepository).findByNameIn(candidatesCaptor.capture());
      assertThat(candidatesCaptor.getValue()).contains("cicd");
    }
  }

  @Nested
  @DisplayName("resolveTagsFor — intersection with the tag table")
  class Intersection {

    @Test
    @DisplayName("should keep only candidates that exist as real hashtags")
    void shouldKeepOnlyExistingHashtags() {
      // Given — "core" and "boot" are generated but nobody has ever tagged a post with them
      givenVerifiedSkills("Spring Boot", "Java Core");
      when(hashtagRepository.findByNameIn(any()))
          .thenReturn(List.of(tag("springboot"), tag("java")));

      // When
      Set<String> tags = resolver.resolveTagsFor(USER_ID);

      // Then — this is what stops a fragment of a node title from filtering somebody's feed
      assertThat(tags).containsExactlyInAnyOrder("springboot", "java");
    }

    @Test
    @DisplayName("should return an empty set when no candidate matches any hashtag")
    void shouldReturnEmptyWhenNothingMatches() {
      // Given
      givenVerifiedSkills("Mô hình đe doạ");
      when(hashtagRepository.findByNameIn(any())).thenReturn(List.of());

      // When
      Set<String> tags = resolver.resolveTagsFor(USER_ID);

      // Then
      assertThat(tags).isEmpty();
    }
  }

  @Nested
  @DisplayName("resolveTagsFor — hand-curated Vietnamese-to-hashtag translations")
  class KnownTranslations {

    @Test
    @DisplayName("should offer the curated hashtag(s) for a node name folding cannot reach")
    void shouldOfferCuratedTranslation() {
      // Given — "Xác thực" folds to "xac" and "thuc", neither of which is "security"; the two are
      // translations of each other, not spelling variants, so no fold ever bridges them
      givenVerifiedSkills("Xác thực");
      when(hashtagRepository.findByNameIn(any())).thenReturn(List.of());

      // When
      resolver.resolveTagsFor(USER_ID);

      // Then
      verify(hashtagRepository).findByNameIn(candidatesCaptor.capture());
      assertThat(candidatesCaptor.getValue()).contains("security");
    }

    @Test
    @DisplayName("should let the curated hashtag reach the result once it exists in t_hashtags")
    void shouldResolveCuratedTranslationThroughIntersection() {
      // Given
      givenVerifiedSkills("Xác thực");
      when(hashtagRepository.findByNameIn(any())).thenReturn(List.of(tag("security")));

      // When
      Set<String> tags = resolver.resolveTagsFor(USER_ID);

      // Then
      assertThat(tags).containsExactly("security");
    }

    @Test
    @DisplayName("should not offer a curated hashtag for an unlisted node name")
    void shouldNotOfferCuratedTranslationForUnlistedName() {
      // Given — close to a curated key but not an exact match, so it must fall back to folding only
      givenVerifiedSkills("Mô hình đe doạ");
      when(hashtagRepository.findByNameIn(any())).thenReturn(List.of());

      // When
      resolver.resolveTagsFor(USER_ID);

      // Then
      verify(hashtagRepository).findByNameIn(candidatesCaptor.capture());
      assertThat(candidatesCaptor.getValue()).doesNotContain("security");
    }
  }
}
