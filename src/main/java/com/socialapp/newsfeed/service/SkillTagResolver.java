package com.socialapp.newsfeed.service;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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

  /**
   * Node names the fold-and-split strategy above can never reach, mapped by hand to the hashtag(s)
   * that mean the same thing.
   *
   * <p>The gap this closes: roadmap node names are Vietnamese phrases a moderator wrote ({@code
   * "Kiểm thử"}, {@code "Xác thực"}), hashtags are English tokens an author typed ({@code testing},
   * {@code security}). Folding {@code "Kiểm thử"} produces {@code kiem} and {@code thu} — neither is
   * {@code testing}, and no amount of stripping diacritics fixes that, because the two words are not
   * spelling variants of each other. They are translations.
   *
   * <p><b>Why a fixed table instead of a translator.</b> A machine-translated or fuzzy-matched guess
   * can drift into the wrong topic silently — the one failure mode the fold logic above was written
   * to avoid (see the class javadoc). A hand-checked table can only be wrong where a human already
   * looked and got it wrong, which is a bug someone can point at and fix. So every entry here is a
   * direct, unambiguous rendering of one specific node name into hashtag(s) that already exist in
   * {@code t_hashtags} as of 2026-09 — never a fragment, a synonym family, or a "close enough" guess.
   *
   * <p><b>Why this list is short, not exhaustive.</b> Most node names have no honest one-word English
   * counterpart in the seeded hashtag vocabulary ({@code "Cấu trúc dữ liệu"}, {@code "Một framework"},
   * {@code "Xử lý sự cố"} in the general sense) — inventing one would be exactly the guess this table
   * exists to avoid. Leaving those out means their skill tab may still come up empty, which is the
   * correct, honest answer: "no hashtag says this" rather than "here is a hashtag that might."
   *
   * <p>Keyed on the exact node name as stored in {@code t_roadmap_nodes.name} — not folded, not
   * lower-cased — so a rename silently drops the entry instead of drifting onto a different node.
   * Values are looked up alongside the fold-based candidates in {@link #candidatesFor}, and pass
   * through the same {@code t_hashtags} intersection in {@link #resolveTagsFor}, so a stale entry
   * whose target hashtag no longer exists costs nothing beyond a wasted map lookup.
   */
  private static final Map<String, Set<String>> KNOWN_TRANSLATIONS =
      Map.ofEntries(
          Map.entry("Cơ sở dữ liệu quan hệ", Set.of("database")),
          Map.entry("Thiết kế API", Set.of("api")),
          Map.entry("Ghi log và đo đạc", Set.of("logging", "observability")),
          Map.entry("Kiểm thử", Set.of("testing")),
          Map.entry("Khả năng truy cập", Set.of("accessibility")),
          Map.entry("Hiệu năng web", Set.of("webperf")),
          Map.entry("Hiệu năng", Set.of("performance")),
          Map.entry("Container", Set.of("docker")),
          Map.entry("Điều phối container", Set.of("kubernetes")),
          Map.entry("Giám sát", Set.of("monitoring", "observability")),
          Map.entry("Cảnh báo", Set.of("monitoring")),
          Map.entry("Hạ tầng dưới dạng mã", Set.of("terraform")),
          Map.entry("Tích hợp liên tục", Set.of("cicd")),
          Map.entry("Triển khai liên tục", Set.of("cicd")),
          Map.entry("Học có giám sát", Set.of("machinelearning")),
          Map.entry("Đưa mô hình lên sản xuất", Set.of("mlops")),
          Map.entry("Mô hình hoá mối đe doạ", Set.of("security")),
          Map.entry("Xác thực", Set.of("security")),
          Map.entry("Phân quyền", Set.of("security")),
          Map.entry("Mười rủi ro phổ biến", Set.of("security")),
          Map.entry("Chèn mã", Set.of("security")),
          Map.entry("Bí mật và khoá", Set.of("security")),
          Map.entry("Phụ thuộc bên thứ ba", Set.of("security")),
          Map.entry("Ghi nhật ký an toàn", Set.of("security")),
          Map.entry("Test đơn vị", Set.of("testing", "junit")),
          Map.entry("Test tích hợp", Set.of("testing")),
          Map.entry("Test đầu cuối", Set.of("testing", "cypress", "selenium", "playwright")),
          Map.entry("Kiểm thử hiệu năng", Set.of("testing", "performance")),
          Map.entry("Kim tự tháp kiểm thử", Set.of("testing")),
          Map.entry("Độ phủ", Set.of("testing")),
          Map.entry("Test giòn", Set.of("testing")),
          Map.entry("Dữ liệu kiểm thử", Set.of("testing")),
          Map.entry("Kiến trúc phân tầng", Set.of("architecture")),
          Map.entry("Thiết kế theo miền", Set.of("ddd")),
          Map.entry("Ghép lỏng và gắn kết", Set.of("architecture")),
          Map.entry("Tiến hoá hệ thống", Set.of("architecture")),
          Map.entry("Phỏng vấn", Set.of("interview")),
          Map.entry("Dẫn dắt kỹ thuật", Set.of("mentoring")),
          Map.entry("Làm việc nhóm", Set.of("teamwork")),
          Map.entry("Dòng lệnh Linux", Set.of("linux")),
          Map.entry("Quản lý phiên bản", Set.of("git")),
          Map.entry("Đường ống dữ liệu", Set.of("airflow", "dbt")),
          Map.entry("Offline-first", Set.of("pwa")),
          Map.entry("Nghiên cứu người dùng", Set.of("ux")),
          Map.entry("Viết rõ ràng", Set.of("documentation")),
          Map.entry("Ghi lại quyết định", Set.of("documentation")));

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

  /**
   * The folded whole, each folded fragment, and any hand-curated translation of one node name —
   * see the class javadoc and {@link #KNOWN_TRANSLATIONS}.
   */
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

    candidates.addAll(KNOWN_TRANSLATIONS.getOrDefault(skillName.trim(), Set.of()));
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
