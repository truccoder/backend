package com.socialapp.knowledge.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.socialapp.knowledge.entity.enums.PrimaryRole;

/**
 * The one place that answers "how close are these two professional backgrounds?".
 *
 * <p>This logic was written twice before it was written once: {@code FriendshipService} ranked
 * friend suggestions by role and tech-stack overlap, while {@code MatchmakingService} — the module
 * whose entire purpose is matching people to work — did a case-sensitive "shares at least one
 * skill" filter with no ranking at all. Two answers to the same question, and the worse one lived
 * where it mattered most.
 *
 * <p>Lives in {@code knowledge} because that is the module owning {@link
 * com.socialapp.knowledge.entity.UserProfessionalProfileEntity}, and because both {@code
 * friendships} and {@code matchmaking} already depend on it — so sharing this creates no new edge
 * in the module graph.
 *
 * <p>Static and stateless on purpose: it holds no repository and touches no database, so it is
 * callable from inside a comparator without dragging a Spring bean through every call site, and
 * testable without a context.
 *
 * <p><b>Every comparison is case-insensitive.</b> These lists are free text typed by users and by
 * project owners — {@code "React"}, {@code "react"} and {@code "REACT"} are the same skill, and
 * treating them as three was the single biggest reason the old matchmaking filter missed people.
 */
public final class ProfileMatchScorer {

  private ProfileMatchScorer() {
    // Static-only helper; never instantiated.
  }

  /**
   * How many entries the two lists share, compared case-insensitively.
   *
   * <p>Null or empty on either side is 0 rather than an exception: a user who has not filled in a
   * tech stack, and a position posted without required skills, are both ordinary states here, not
   * errors. Callers rank on the result, and 0 sorts them last on its own.
   *
   * <p>Duplicates within a list are counted once — the inputs are conceptually sets, and a profile
   * listing {@code ["Java","java"]} must not outrank one listing {@code ["Java"]}.
   */
  public static int skillOverlap(List<String> a, List<String> b) {
    return matchedSkills(a, b).size();
  }

  /**
   * The shared entries themselves, so a caller can show <em>why</em> something was suggested
   * instead of only how strongly.
   *
   * <p>Values are returned in {@code a}'s casing and {@code a}'s order: {@code a} is the caller's
   * own profile at every call site, and echoing a stranger's capitalisation of the caller's own
   * skills back at them reads like a bug. Insertion order is preserved so the same match never
   * renders in a different sequence between requests.
   */
  public static List<String> matchedSkills(List<String> a, List<String> b) {
    if (a == null || b == null || a.isEmpty() || b.isEmpty()) {
      return List.of();
    }

    Set<String> other = b.stream().map(ProfileMatchScorer::normalize).collect(Collectors.toSet());

    // LinkedHashMap keyed by the normalized form: first-writer-wins de-duplicates entries that
    // differ only in case or padding, while the map's insertion order preserves a's ordering.
    Map<String, String> matched = new LinkedHashMap<>();
    for (String value : a) {
      String key = normalize(value);
      if (other.contains(key)) {
        matched.putIfAbsent(key, value);
      }
    }
    return List.copyOf(matched.values());
  }

  /**
   * Whether two people specialise in the same area.
   *
   * <p>Null-safe on both sides and false when either is unset: "we both haven't said what we do"
   * is not a match, and letting two nulls compare equal would have floated every profile-less user
   * to the top of every ranking.
   */
  public static boolean sameRole(PrimaryRole a, PrimaryRole b) {
    return a != null && a == b;
  }

  private static String normalize(String value) {
    return value == null ? "" : value.trim().toLowerCase();
  }
}
