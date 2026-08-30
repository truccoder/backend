package com.socialapp.posts.repository;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.socialapp.hashtags.dto.HashtagDto;
import com.socialapp.posts.entity.HashtagEntity;

@Repository
public interface HashtagRepository extends JpaRepository<HashtagEntity, Integer> {
  Optional<HashtagEntity> findByName(String name);

  List<HashtagEntity> findByNameIn(Collection<String> names);

  /**
   * Type-ahead for the composer and the search box: the tags whose name starts with {@code prefix}
   * and that at least one post carries, most-used first.
   *
   * <p>Derived rather than {@code @Query} so Spring Data escapes {@code %} and {@code _} in the
   * bound prefix — a user typing {@code java_} must not have the {@code _} act as a wildcard.
   * {@code idx_hashtags_name_prefix} ({@code text_pattern_ops}, added in {@code V99}) answers the
   * left-anchored {@code LIKE}.
   *
   * <p>{@code UsageCountGreaterThan(0)} drops the dead tags: {@code V41} seeds a handful at zero and
   * a tag can drift back to zero as posts that used it are edited or deleted. Completing to one of
   * those lands the reader on an empty feed, so a suggestion that leads nowhere is worse than a
   * shorter list. The caller passes {@code 0}.
   *
   * <p>{@code usageCount} is then the sort key because it is the number the product already
   * maintains per tag; a tie breaks on the name so the list is stable between keystrokes.
   */
  List<HashtagEntity> findByNameStartingWithAndUsageCountGreaterThanOrderByUsageCountDescNameAsc(
      String prefix, int minUsageCount, Pageable pageable);

  /**
   * The tags carried by the most PUBLIC, APPROVED posts created since {@code since}, most first.
   *
   * <p><b>Counted from {@code t_post_hashtags} inside the window, not read off {@code
   * usage_count}.</b> That counter is a lifetime running total — it is what {@code
   * /hashtags/suggest} sorts on — and says nothing about what is being talked about this week. This
   * query joins the link table to the posts so the number reflects the window the caller asked for.
   *
   * <p>Only PUBLIC + APPROVED posts count: a trending list is a public artefact, and a tag must not
   * ride onto it on the back of friends-only or not-yet-moderated posts nobody else can open.
   */
  @Query(
      """
      SELECT new com.socialapp.hashtags.dto.HashtagDto(h.name, COUNT(p.id))
      FROM PostEntity p
      JOIN p.hashtags h
      WHERE p.createdAt >= :since
        AND p.visibility = com.socialapp.posts.entity.enums.PostVisibility.PUBLIC
        AND p.moderationStatus = com.socialapp.moderation.enums.ModerationStatus.APPROVED
      GROUP BY h.name
      ORDER BY COUNT(p.id) DESC, h.name ASC
      """)
  List<HashtagDto> findTrendingSince(@Param("since") OffsetDateTime since, Pageable pageable);

  /**
   * Creates the tags that do not exist yet, leaving the ones that do untouched.
   *
   * <p>{@code ON CONFLICT DO NOTHING} rather than a select-then-insert: {@code t_hashtags.name} is
   * UNIQUE, so two people posting {@code #java} at the same moment both missed in the select and
   * both inserted, and one of them got a 409 on a perfectly good post. Let Postgres settle it.
   *
   * <p>Flushes but deliberately does <b>not</b> clear — see {@link #incrementUsage}.
   */
  @Modifying(flushAutomatically = true)
  @Query(
      value =
          "INSERT INTO socialapp.t_hashtags (name, usage_count) "
              + "SELECT unnest(CAST(:names AS text[])), 0 "
              + "ON CONFLICT (name) DO NOTHING",
      nativeQuery = true)
  void createMissing(@Param("names") String[] names);

  /**
   * Bumps each tag's counter in the database rather than in memory.
   *
   * <p>The counter used to be read, incremented in Java and written back, so two concurrent posts
   * sharing a tag both read the same value and one increment was lost — permanently, since nothing
   * ever recomputes this from the join table. Every other counter in this codebase was made
   * absolute or DB-atomic for the same reason.
   *
   * <p><b>{@code flushAutomatically} without {@code clearAutomatically}, on all three of these.</b>
   * Clearing would be the reflex — the raw UPDATE leaves any {@code HashtagEntity} already in the
   * persistence context holding a stale {@code usageCount} — but {@code em.clear()} detaches
   * <em>everything</em>, and the only caller is {@link
   * com.socialapp.posts.service.PostService#processHashtags}, which is handed the post mid-save by
   * {@code createPost} and {@code updatePost}. Detaching it there turns every following
   * {@code save()} into a merge whose result nobody keeps, so any write made after this point
   * survives only by luck, and any LAZY association not yet initialised becomes a
   * {@code LazyInitializationException} now that {@code open-in-view} is off.
   *
   * <p>The stale counter costs nothing by comparison: nothing reads {@code usageCount} off those
   * instances, and Hibernate compares against the snapshot it loaded, so a stale value is not
   * dirty and is never written back over the SQL. The one rule that follows from this is that
   * {@code usageCount} must be changed <em>only</em> through these queries, never by a setter.
   */
  @Modifying(flushAutomatically = true)
  @Query(
      value =
          "UPDATE socialapp.t_hashtags SET usage_count = COALESCE(usage_count, 0) + 1 "
              + "WHERE name = ANY(CAST(:names AS text[]))",
      nativeQuery = true)
  void incrementUsage(@Param("names") String[] names);

  /**
   * The counterpart to {@link #incrementUsage}, for the tags an edit drops from a post.
   *
   * <p>The decrement was the half of this pair left behind in Java — read, minus one, {@code
   * saveAll} — which loses exactly the same concurrent update the increment was moved into the
   * database to stop. {@code GREATEST(..., 0)} keeps the old in-Java guard: a counter that has
   * already drifted to zero must not go negative.
   */
  @Modifying(flushAutomatically = true)
  @Query(
      value =
          "UPDATE socialapp.t_hashtags "
              + "SET usage_count = GREATEST(COALESCE(usage_count, 0) - 1, 0) "
              + "WHERE name = ANY(CAST(:names AS text[]))",
      nativeQuery = true)
  void decrementUsage(@Param("names") String[] names);
}
