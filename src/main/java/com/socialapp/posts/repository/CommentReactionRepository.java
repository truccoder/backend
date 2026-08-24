package com.socialapp.posts.repository;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
