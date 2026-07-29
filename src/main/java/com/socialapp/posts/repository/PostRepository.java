package com.socialapp.posts.repository;

import java.time.OffsetDateTime;
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

  /**
   * Approved events starting between now and {@code until}, for the reminder job.
   *
   * <p>Native because {@code event_details} is a jsonb column with no mapped startTime to filter
   * on in JPQL. The lower bound is {@code now}: an event that has already begun is past reminding.
   *
   * <p>Two things in here look roundabout and are not:
   *
   * <ul>
   *   <li><b>{@code cast(... as timestamptz)}, not {@code ::timestamptz}</b> — Spring Data parses
   *       {@code :} in a native query as the start of a named parameter, so the shorthand cast
   *       dies at the driver with {@code syntax error at or near ":"}.
   *   <li><b>the {@code jsonb_typeof} branch</b> — Hibernate serialises the embedded {@code
   *       OffsetDateTime} through its own ObjectMapper, which writes dates as epoch numbers, so
   *       every row this application writes stores startTime as a jsonb <i>number</i>. A row
   *       inserted by hand (the documented way to stage test posts) carries an ISO <i>string</i>
   *       instead. Reading only one of the two shapes silently finds no events.
   * </ul>
   */
  @Query(
      value =
          """
                              SELECT p.* FROM socialapp.t_posts p
                              CROSS JOIN LATERAL (
                                SELECT CASE jsonb_typeof(p.event_details -> 'startTime')
                                         WHEN 'number' THEN
                                           to_timestamp(cast(p.event_details ->> 'startTime' AS double precision))
                                         WHEN 'string' THEN
                                           cast(p.event_details ->> 'startTime' AS timestamptz)
                                       END AS start_at
                              ) s
                              WHERE p.post_type = 'EVENT'
                                AND p.moderation_status = 'APPROVED'
                                AND s.start_at IS NOT NULL
                                AND s.start_at > :now
                                AND s.start_at <= :until
                            """,
      nativeQuery = true)
  List<PostEntity> findEventsStartingBetween(
      @Param("now") OffsetDateTime now, @Param("until") OffsetDateTime until);
}
