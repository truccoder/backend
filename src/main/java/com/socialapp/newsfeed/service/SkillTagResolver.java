package com.socialapp.newsfeed.service;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.socialapp.posts.entity.HashtagEntity;
import com.socialapp.posts.repository.HashtagRepository;
import com.socialapp.roadmap.enums.VerificationStatus;
import com.socialapp.roadmap.repository.UserRoadmapProgressRepository;

import lombok.RequiredArgsConstructor;

/**
 * Turns "the skills this user has had verified" into "the hashtags that count as those skills".
 *
 * <p>The two vocabularies were never designed against each other: roadmap nodes are editorial
 * names a moderator wrote ({@code Spring Boot}, {@code React Hooks}, {@code Tối ưu truy vấn SQL}),
 * hashtags are whatever an author typed ({@code springboot}, {@code react}, {@code postgresql}).
 * Nothing joins them in the schema, so the join has to be made here, and it has to be made
 * conservatively — a filter that guesses wrong shows a reader posts about something they did not
 * ask for, which is worse than showing fewer.
 *
 * <p>Three steps, in this order:
 *
 * <ol>
 *   <li><b>Fold.</b> Strip diacritics and case, drop everything that is not a letter or digit. This
 *       is what makes {@code Tối ưu} and {@code toi uu} the same string, and it is done in Java
 *       rather than by Postgres {@code unaccent} because the hashtag side is already stored folded.
 *   <li><b>Split, and keep the whole.</b> {@code "React Hooks"} yields {@code reacthooks} plus
 *       {@code react} and {@code hooks}. Without the parts, {@code Java Core} would never reach the
 *       {@code java} hashtag; without the whole, {@code Spring Boot} would never reach {@code
 *       springboot}. Fragments shorter than three characters are dropped — they are articles and
 *       particles, not skills.
 *   <li><b>Intersect with reality.</b> The candidates are looked up in {@code t_hashtags} and only
 *       the ones that exist survive. This is the step that keeps the guesswork honest: a junk
 *       fragment can only match a post if somebody actually created a hashtag with that name, and
 *       the result set is bounded by the tag table rather than by how many words a moderator used
 *       in a node title.
 * </ol>
 */
@Component
@RequiredArgsConstructor
public class SkillTagResolver {

  /** Below this, a fragment is a particle ({@code và}, {@code de}, {@code 10}), not a skill. */
  private static final int MIN_FRAGMENT_LENGTH = 3;

  private final UserRoadmapProgressRepository progressRepository;
  private final HashtagRepository hashtagRepository;

  /**
   * The hashtag names that stand for {@code userId}'s verified skills, or an empty set when they
   * have none — which the caller must treat as "nothing matches", not as "no filter".
   */
  @Transactional(readOnly = true)
  public Set<String> resolveTagsFor(Integer userId) {
    List<String> skillNames =
        progressRepository.findSkillNamesByUserIdAndStatus(userId, VerificationStatus.VERIFIED);

    if (skillNames.isEmpty()) {
      return Set.of();
    }

    Set<String> candidates = new LinkedHashSet<>();
    skillNames.forEach(name -> candidates.addAll(candidatesFor(name)));

    if (candidates.isEmpty()) {
      return Set.of();
    }

    return hashtagRepository.findByNameIn(candidates).stream()
        .map(HashtagEntity::getName)
        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
  }

  /** The folded whole plus each folded fragment of one node name — see the class javadoc. */
  private Set<String> candidatesFor(String skillName) {
    Set<String> candidates = new LinkedHashSet<>();
    if (skillName == null || skillName.isBlank()) {
      return candidates;
    }

    Arrays.stream(skillName.split("\\s+"))
        .map(SkillTagResolver::fold)
        .filter(fragment -> fragment.length() >= MIN_FRAGMENT_LENGTH)
        .forEach(candidates::add);

    String whole = fold(skillName);
    if (whole.length() >= MIN_FRAGMENT_LENGTH) {
      candidates.add(whole);
    }
    return candidates;
  }

  /**
   * Lowercase, unaccented, letters and digits only.
   *
   * <p>{@code Normalizer.Form.NFD} splits an accented character into base letter plus combining
   * mark; the regex then removes the marks, which is what turns {@code ế} into {@code e}. Vietnamese
   * {@code đ} has no combining form and survives NFD intact, so it is mapped by hand — without that
   * line {@code Đóng gói} would fold to {@code ónggói} and match nothing.
   */
  private static String fold(String value) {
    String deAccented =
        Normalizer.normalize(value.replace('đ', 'd').replace('Đ', 'D'), Normalizer.Form.NFD)
            .replaceAll("\\p{M}+", "");
    return deAccented.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", "");
  }
}
