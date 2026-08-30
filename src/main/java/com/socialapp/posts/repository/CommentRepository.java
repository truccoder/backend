package com.socialapp.posts.repository;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.socialapp.posts.entity.CommentEntity;

@Repository
public interface CommentRepository extends JpaRepository<CommentEntity, Integer> {
  List<CommentEntity> findByPostIdOrderByCreatedAtAsc(Integer postId);

  /**
   * One page of a post's top-level comments, oldest first.
   *
   * <p>Paged by comment id, not by {@code createdAt}: two comments can share a timestamp and there
   * is no second column to break the tie, so a timestamp cursor either repeats or skips a row at
   * a page boundary. Ids come from a sequence, so ascending id is ascending insertion order with a
   * unique key — the same reasoning as {@code PostRepository.findByAuthorForViewer}, mirrored
   * because a comment thread reads oldest-first.
   *
   * <p>Only roots are paged. Replies are fetched for the roots on the page (see
   * {@code findByParentIdInOrderByCreatedAtAsc}), so a reply never arrives without its parent —
   * paging the flat list would have done exactly that.
   */
  @Query(
      """
      SELECT c FROM CommentEntity c
      WHERE c.postId = :postId
        AND c.parentId IS NULL
        AND (:cursor IS NULL OR c.id > :cursor)
      ORDER BY c.id ASC
      """)
  List<CommentEntity> findRootCommentsForPage(
      @Param("postId") Integer postId,
      @Param("cursor") Integer cursor,
      org.springframework.data.domain.Pageable pageable);

  /** Every reply belonging to the given root comments, in one query rather than one per root. */
  List<CommentEntity> findByParentIdInOrderByCreatedAtAsc(Collection<Integer> parentIds);

  List<CommentEntity> findByPostIdAndParentIdIsNullOrderByCreatedAtAsc(Integer postId);

  List<CommentEntity> findByParentIdOrderByCreatedAtAsc(Integer parentId);

  boolean existsByIdAndParentIdIsNull(Integer id);

  // Counts replies as well as top-level comments, matching what the feed card claims to show.
  long countByPostId(Integer postId);

  /**
   * The same count for a whole page of posts, in one query.
   *
   * <p>Exists because the read endpoints render a page of posts at a time; calling {@link
   * #countByPostId} per row would make the query count grow with the page size. Posts with no
   * comments are simply absent from the result — the caller defaults them to zero.
   */
  @Query(
      """
      SELECT c.postId, COUNT(c) FROM CommentEntity c
      WHERE c.postId IN :postIds
      GROUP BY c.postId
      """)
  List<Object[]> countByPostIdsRaw(@Param("postIds") Collection<Integer> postIds);

  default Map<Integer, Long> countByPostIds(Collection<Integer> postIds) {
    if (postIds.isEmpty()) {
      return Map.of();
    }
    return countByPostIdsRaw(postIds).stream()
        .collect(Collectors.toMap(row -> (Integer) row[0], row -> (Long) row[1]));
  }
}
