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

import com.socialapp.posts.entity.CommentReactionEntity;
import com.socialapp.posts.entity.CommentReactionId;
import com.socialapp.posts.entity.enums.ReactionType;

public interface CommentReactionRepository
    extends JpaRepository<CommentReactionEntity, CommentReactionId> {

  /**
   * Reaction totals for a whole comment thread in one query.
   *
   * <p>The batch form is the only one the read path may use. A thread is unbounded — the seed
   * alone has posts with dozens of comments — so a per-comment count inside the mapping loop is
   * the N+1 that {@code PostReactionRepository#countByPostIds} exists to avoid one level up.
   */
  @Query(
      """
      SELECT r.id.commentId, COUNT(r) FROM CommentReactionEntity r
      WHERE r.id.commentId IN :commentIds
      GROUP BY r.id.commentId
      """)
  List<Object[]> countByCommentIdsRaw(@Param("commentIds") Collection<Integer> commentIds);

  default Map<Integer, Long> countByCommentIds(Collection<Integer> commentIds) {
    // Guarded rather than passed through: "IN ()" is a syntax error in Postgres, and an empty
    // thread is the ordinary case for a post nobody has answered yet.
    if (commentIds.isEmpty()) {
      return Map.of();
    }
    return countByCommentIdsRaw(commentIds).stream()
        .collect(Collectors.toMap(row -> (Integer) row[0], row -> (Long) row[1]));
  }

  /**
   * Per-type reaction totals for a whole comment thread, in one query.
   *
   * <p>Comments never had this at all: {@code CommentResponseDto} carried a single {@code
   * likeCount}, and the reaction row on a comment lost its text labels, so a bare "5" beside one
   * icon could not say which five. Posts could at least answer the question through {@code
   * /reactions/summary}; comments had no read endpoint of any kind.
   *
   * <p>Batched for the same reason {@link #countByCommentIdsRaw} is — a thread is unbounded.
   */
  @Query(
      """
      SELECT r.id.commentId, r.reactionType, COUNT(r) FROM CommentReactionEntity r
      WHERE r.id.commentId IN :commentIds
      GROUP BY r.id.commentId, r.reactionType
      """)
  List<Object[]> countByTypeForCommentIdsRaw(@Param("commentIds") Collection<Integer> commentIds);

  default Map<Integer, Map<ReactionType, Long>> countByTypeForCommentIds(
      Collection<Integer> commentIds) {
    if (commentIds.isEmpty()) {
      return Map.of();
    }
    Map<Integer, Map<ReactionType, Long>> byComment = new HashMap<>();
    for (Object[] row : countByTypeForCommentIdsRaw(commentIds)) {
      byComment
          .computeIfAbsent((Integer) row[0], id -> new EnumMap<>(ReactionType.class))
          .put((ReactionType) row[1], (Long) row[2]);
    }
    return byComment;
  }

  /**
   * The user ids that reacted to a comment, one page at a time, optionally narrowed to a single
   * reaction type ({@code null} means all types).
   *
   * <p>The mirror of {@code PostReactionRepository#findReactorIds}, cursored on user id for the
   * same reason: this table's key is (userId, commentId), and {@code createdAt} has no tiebreaker,
   * so a page boundary falling between two reactions written in the same instant would drop or
   * repeat one.
   */
  @Query(
      """
      SELECT r.id.userId FROM CommentReactionEntity r
      WHERE r.id.commentId = :commentId
        AND (:reactionType IS NULL OR r.reactionType = :reactionType)
        AND (:cursor IS NULL OR r.id.userId > :cursor)
      ORDER BY r.id.userId ASC
      """)
  List<Integer> findReactorIds(
      @Param("commentId") Integer commentId,
      @Param("reactionType") ReactionType reactionType,
      @Param("cursor") Integer cursor,
      Pageable pageable);

  long countByIdCommentId(Integer commentId);

  long countByIdCommentIdAndReactionType(Integer commentId, ReactionType reactionType);

  /** What {@code userId} chose on each of {@code commentIds}, for the "my reaction" highlight. */
  @Query(
      """
      SELECT r.id.commentId, r.reactionType FROM CommentReactionEntity r
      WHERE r.id.userId = :userId AND r.id.commentId IN :commentIds
      """)
  List<Object[]> findMyReactionsRaw(
      @Param("userId") Integer userId, @Param("commentIds") Collection<Integer> commentIds);

  default Map<Integer, ReactionType> findMyReactions(
      Integer userId, Collection<Integer> commentIds) {
    // A guest reading a public post has no id, and has reacted to nothing.
    if (userId == null || commentIds.isEmpty()) {
      return Map.of();
    }
    return findMyReactionsRaw(userId, commentIds).stream()
        .collect(Collectors.toMap(row -> (Integer) row[0], row -> (ReactionType) row[1]));
  }
}
