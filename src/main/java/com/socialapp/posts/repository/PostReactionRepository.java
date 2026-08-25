package com.socialapp.posts.repository;

import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.socialapp.posts.entity.PostReactionEntity;
import com.socialapp.posts.entity.PostReactionId;
import com.socialapp.posts.entity.enums.ReactionType;

public interface PostReactionRepository extends JpaRepository<PostReactionEntity, PostReactionId> {
  long countByIdPostId(Integer postId);

  @Query(
      """
      SELECT r.reactionType, COUNT(r) FROM PostReactionEntity r
      WHERE r.id.postId = :postId
      GROUP BY r.reactionType
      """)
  java.util.List<Object[]> countByTypeRaw(@Param("postId") Integer postId);

  default Map<ReactionType, Long> countByType(Integer postId) {
    return countByTypeRaw(postId).stream()
        .collect(Collectors.toMap(row -> (ReactionType) row[0], row -> (Long) row[1]));
  }

  long countByIdPostIdAndReactionType(Integer postId, ReactionType reactionType);

  /**
   * Per-type reaction totals for a whole page of posts, in one query.
   *
   * <p>The batch sibling of {@link #countByTypeRaw}, which answers for a single post and backs
   * {@code GET /posts/{id}/reactions/summary}. That endpoint is fine for one open post and wrong
   * for a feed: ten cards meant ten extra requests to draw three small icons each, over a
   * group-by the feed's own {@code likeCount} had already run and thrown the detail away.
   *
   * <p>Grouped by post AND type, so one row per (post, type) pair — the caller pivots it into a
   * map per post.
   */
  @Query(
      """
      SELECT r.id.postId, r.reactionType, COUNT(r) FROM PostReactionEntity r
      WHERE r.id.postId IN :postIds
      GROUP BY r.id.postId, r.reactionType
      """)
  List<Object[]> countByTypeForPostIdsRaw(@Param("postIds") Collection<Integer> postIds);

  default Map<Integer, Map<ReactionType, Long>> countByTypeForPostIds(Collection<Integer> postIds) {
    // Guarded rather than passed through: "IN ()" is a syntax error in Postgres.
    if (postIds.isEmpty()) {
      return Map.of();
    }
    Map<Integer, Map<ReactionType, Long>> byPost = new HashMap<>();
    for (Object[] row : countByTypeForPostIdsRaw(postIds)) {
      byPost
          .computeIfAbsent((Integer) row[0], id -> new EnumMap<>(ReactionType.class))
          .put((ReactionType) row[1], (Long) row[2]);
    }
    return byPost;
  }

  /** Reaction totals for a whole page of posts in one query — see {@code countByPostIdsRaw}. */
  @Query(
      """
      SELECT r.id.postId, COUNT(r) FROM PostReactionEntity r
      WHERE r.id.postId IN :postIds
      GROUP BY r.id.postId
      """)
  List<Object[]> countByPostIdsRaw(@Param("postIds") Collection<Integer> postIds);

  default Map<Integer, Long> countByPostIds(Collection<Integer> postIds) {
    if (postIds.isEmpty()) {
      return Map.of();
    }
    return countByPostIdsRaw(postIds).stream()
        .collect(Collectors.toMap(row -> (Integer) row[0], row -> (Long) row[1]));
  }

  /**
   * The user ids that reacted to a post, one page at a time, optionally narrowed to a single
   * reaction type ({@code null} means all types).
   *
   * <p>Ordered by user id rather than by {@code createdAt}: the cursor has to be a single unique
   * column, and this table's key is (userId, postId) — {@code createdAt} has no tiebreaker, so a
   * page boundary between two reactions recorded in the same instant would drop or repeat one.
   * Returning ids only, with the profiles fetched in one batch afterwards, keeps this to two
   * queries per page instead of one per reactor.
   */
  @Query(
      """
      SELECT r.id.userId FROM PostReactionEntity r
      WHERE r.id.postId = :postId
        AND (:reactionType IS NULL OR r.reactionType = :reactionType)
        AND (:cursor IS NULL OR r.id.userId > :cursor)
      ORDER BY r.id.userId ASC
      """)
  List<Integer> findReactorIds(
      @Param("postId") Integer postId,
      @Param("reactionType") ReactionType reactionType,
      @Param("cursor") Integer cursor,
      Pageable pageable);
}
