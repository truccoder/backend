package com.socialapp.posts.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.socialapp.posts.entity.HashtagEntity;

@Repository
public interface HashtagRepository extends JpaRepository<HashtagEntity, Integer> {
  Optional<HashtagEntity> findByName(String name);

  List<HashtagEntity> findByNameIn(Collection<String> names);

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
