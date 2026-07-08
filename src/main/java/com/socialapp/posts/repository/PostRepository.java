package com.socialapp.posts.repository;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.socialapp.moderation.enums.ModerationStatus;
import com.socialapp.posts.entity.PostEntity;

public interface PostRepository extends JpaRepository<PostEntity, Integer> {
  List<PostEntity> findByModerationStatus(ModerationStatus status);

  List<PostEntity> findByAuthorIdAndModerationStatus(Integer authorId, ModerationStatus status);

  @Query(
      """
                      SELECT p FROM PostEntity p
                      WHERE (:postId IS NULL OR p.id = :postId)
                        AND (:authorId IS NULL OR p.authorId = :authorId)
                        AND (:status IS NULL OR p.moderationStatus = :status)
                      ORDER BY p.createdAt DESC
                    """)
  Page<PostEntity> search(
      @Param("postId") Integer postId,
      @Param("authorId") Integer authorId,
      @Param("status") ModerationStatus status,
      Pageable pageable);

  @Query(
      value =
          """
                              SELECT * FROM socialapp.t_posts p
                              WHERE (unaccent(LOWER(p.content)) LIKE unaccent(LOWER(CONCAT('%', :query, '%'))) ESCAPE '\\'
                                 OR unaccent(LOWER(p.event_details ->> 'eventTitle'))
                                      LIKE unaccent(LOWER(CONCAT('%', :query, '%'))) ESCAPE '\\')
                                AND (p.visibility = 'PUBLIC'
                                     OR p.author_id = :currentUserId
                                     OR (p.author_id IN :friendIds AND p.visibility IN ('PUBLIC', 'FRIENDS')))
                              ORDER BY CASE
                                         WHEN p.author_id = :currentUserId THEN 0
                                         WHEN p.author_id IN :friendIds THEN 1
                                         ELSE 2
                                       END,
                                       p.created_at DESC
                            """,
      countQuery =
          """
                              SELECT COUNT(*) FROM socialapp.t_posts p
                              WHERE (unaccent(LOWER(p.content)) LIKE unaccent(LOWER(CONCAT('%', :query, '%'))) ESCAPE '\\'
                                 OR unaccent(LOWER(p.event_details ->> 'eventTitle'))
                                      LIKE unaccent(LOWER(CONCAT('%', :query, '%'))) ESCAPE '\\')
                                AND (p.visibility = 'PUBLIC'
                                     OR p.author_id = :currentUserId
                                     OR (p.author_id IN :friendIds AND p.visibility IN ('PUBLIC', 'FRIENDS')))
                            """,
      nativeQuery = true)
  Page<PostEntity> searchByContentOrEventName(
      @Param("query") String query,
      @Param("currentUserId") Integer currentUserId,
      @Param("friendIds") List<Integer> friendIds,
      Pageable pageable);
}
