package com.socialapp.posts.repository;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.socialapp.moderation.enums.ModerationStatus;
import com.socialapp.posts.entity.PostEntity;
import com.socialapp.posts.entity.enums.PostVisibility;

import jakarta.persistence.LockModeType;

public interface PostRepository extends JpaRepository<PostEntity, Integer> {

  /**
   * Locks the post row for the rest of the transaction.
   *
   * <p>For the RSVP capacity check, which reads the GOING count and then inserts: without a lock
   * each concurrent RSVP counts against a snapshot that does not yet include the others, and an
   * event with maxAttendees=10 accepts more than ten. Same pattern and same reason as {@code
   * ProjectPositionRepository.findByIdForUpdate}.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select p from PostEntity p where p.id = :id")
  java.util.Optional<PostEntity> findByIdForUpdate(@Param("id") Integer id);

  List<PostEntity> findByModerationStatus(ModerationStatus status);

  /**
   * An author's posts in one moderation state.
   *
   * <p>No production caller — it is kept for the integration and repository tests, which use it to
   * assert what actually reached the database after a request. That is a real use: an assertion
   * helper that reads through the same mapping the application does catches a mapping bug an
   * in-memory check would miss.
   */
  List<PostEntity> findByAuthorIdAndModerationStatus(Integer authorId, ModerationStatus status);

  /**
   * Paged variant, for the feed rebuild — which walks every approved post in the database and so
   * must not load them all at once. The unpaged form above is fine for its callers, which read
   * only the small moderation queues.
   */
  Page<PostEntity> findByModerationStatus(ModerationStatus status, Pageable pageable);

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

  /**
   * One page of {@code authorId}'s posts that {@code viewer} is allowed to see, newest first.
   *
   * <p>The caller passes the visibilities and moderation statuses it has already decided the
   * viewer may see (see {@code PostVisibilityService}) rather than passing the viewer id and
   * having this query work it out: friendship lives in Neo4j and is not joinable from here.
   *
   * <p><b>Cursor is the post id, not {@code createdAt}.</b> Two posts can share a timestamp and
   * there is no second column to break the tie, so a timestamp cursor can either repeat or skip
   * rows at a page boundary. Ids come from a sequence, so descending id is descending insertion
   * order — the same ordering, with a unique key.
   */
  @Query(
      """
      SELECT p FROM PostEntity p
      WHERE p.authorId = :authorId
        AND p.visibility IN :visibilities
        AND p.moderationStatus IN :statuses
        AND (:cursor IS NULL OR p.id < :cursor)
      ORDER BY p.id DESC
      """)
  List<PostEntity> findByAuthorForViewer(
      @Param("authorId") Integer authorId,
      @Param("visibilities") List<PostVisibility> visibilities,
      @Param("statuses") List<ModerationStatus> statuses,
      @Param("cursor") Integer cursor,
      Pageable pageable);

  /**
   * One page of the whole-system discovery feed, newest first.
   *
   * <p>A query, deliberately not a fan-out: fan-out pushes a post id into a Redis list <i>per
   * user</i>, so a system-wide stream done that way would write one entry per user for every
   * public post ever written. Same cursor reasoning as {@link #findByAuthorForViewer}.
   *
   * <p>{@code excludedAuthorIds} carries the caller's block set and must never be empty — {@code
   * NOT IN ()} is not valid SQL. Callers pass a sentinel id no user can have; see {@code
   * PostQueryService}.
   */
  @Query(
      """
      SELECT p FROM PostEntity p
      WHERE p.visibility = com.socialapp.posts.entity.enums.PostVisibility.PUBLIC
        AND p.moderationStatus = com.socialapp.moderation.enums.ModerationStatus.APPROVED
        AND p.authorId NOT IN :excludedAuthorIds
        AND (:cursor IS NULL OR p.id < :cursor)
      ORDER BY p.id DESC
      """)
  List<PostEntity> findPublicFeed(
      @Param("excludedAuthorIds") Collection<Integer> excludedAuthorIds,
      @Param("cursor") Integer cursor,
      Pageable pageable);

  /**
   * Substring search over post content and event title.
   *
   * <p><b>The function name matters.</b> {@code f_unaccent} is the IMMUTABLE wrapper created by
   * {@code V48__add_trigram_search_indexes.sql}, and the two trigram GIN indexes there are built
   * on {@code f_unaccent(lower(...))}. Postgres picks an expression index by comparing the parsed
   * expression, so switching this back to plain {@code unaccent(...)} does not fail — it just
   * stops using the index and goes back to scanning the whole table, with nothing in the log to
   * say so.
   *
   * <p>The pattern still needs three characters before trigram matching can help; a one- or
   * two-character query has no full trigram to look up and falls back to a scan by design.
   *
   * <p><b>The moderation predicate is not optional.</b> This query filtered visibility and blocks
   * but not {@code moderation_status}, so a post an admin had REJECTED — or one that {@code
   * PostReportService.escalateIfEnoughReporters} had just pulled to PENDING_REVIEW — stayed fully
   * findable here, making a takedown cosmetic as far as search was concerned. {@code
   * findPublicFeed} above and {@code PostVisibilityService.isVisibleTo} both check status first;
   * this was the one read path that did not. The author is exempted, matching {@code isVisibleTo},
   * so you can still find your own post while it is awaiting review. It must stay in both the
   * main query and the {@code countQuery} or the total will not match the rows returned.
   */
  @Query(
      value =
          """
                              SELECT * FROM socialapp.t_posts p
                              WHERE (f_unaccent(LOWER(p.content)) LIKE f_unaccent(LOWER(CONCAT('%', :query, '%'))) ESCAPE '\\'
                                 OR f_unaccent(LOWER(p.event_details ->> 'eventTitle'))
                                      LIKE f_unaccent(LOWER(CONCAT('%', :query, '%'))) ESCAPE '\\')
                                AND p.author_id NOT IN :excludedAuthorIds
                                AND (p.moderation_status = 'APPROVED' OR p.author_id = :currentUserId)
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
                              WHERE (f_unaccent(LOWER(p.content)) LIKE f_unaccent(LOWER(CONCAT('%', :query, '%'))) ESCAPE '\\'
                                 OR f_unaccent(LOWER(p.event_details ->> 'eventTitle'))
                                      LIKE f_unaccent(LOWER(CONCAT('%', :query, '%'))) ESCAPE '\\')
                                AND p.author_id NOT IN :excludedAuthorIds
                                AND (p.moderation_status = 'APPROVED' OR p.author_id = :currentUserId)
                                AND (p.visibility = 'PUBLIC'
                                     OR p.author_id = :currentUserId
                                     OR (p.author_id IN :friendIds AND p.visibility IN ('PUBLIC', 'FRIENDS')))
                            """,
      nativeQuery = true)
  Page<PostEntity> searchByContentOrEventName(
      @Param("query") String query,
      @Param("currentUserId") Integer currentUserId,
      @Param("friendIds") List<Integer> friendIds,
      @Param("excludedAuthorIds") Collection<Integer> excludedAuthorIds,
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
