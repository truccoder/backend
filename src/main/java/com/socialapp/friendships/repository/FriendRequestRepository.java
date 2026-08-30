package com.socialapp.friendships.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.socialapp.friendships.entity.FriendRequestEntity;
import com.socialapp.friendships.entity.enums.FriendRequestStatus;

public interface FriendRequestRepository extends JpaRepository<FriendRequestEntity, Integer> {
  List<FriendRequestEntity> findByAddresseeIdAndStatusOrderByCreatedAtDesc(
      Integer addresseeId, FriendRequestStatus status);

  /**
   * One page of requests addressed to a user, newest first.
   *
   * <p>Cursored on the request id rather than {@code createdAt}: ids come from a sequence, so
   * descending id is descending insertion order with a unique tie-break, and a timestamp cursor
   * would repeat or skip rows when two requests share a millisecond. Same reasoning as
   * {@code PostRepository.findByAuthorForViewer}.
   */
  @Query(
      """
      SELECT r FROM FriendRequestEntity r
      WHERE r.addresseeId = :userId
        AND r.status = :status
        AND (:cursor IS NULL OR r.id < :cursor)
      ORDER BY r.id DESC
      """)
  List<FriendRequestEntity> findIncomingForPage(
      @Param("userId") Integer userId,
      @Param("status") FriendRequestStatus status,
      @Param("cursor") Integer cursor,
      Pageable pageable);

  /** The mirror of {@link #findIncomingForPage} for requests the user sent. */
  @Query(
      """
      SELECT r FROM FriendRequestEntity r
      WHERE r.requesterId = :userId
        AND r.status = :status
        AND (:cursor IS NULL OR r.id < :cursor)
      ORDER BY r.id DESC
      """)
  List<FriendRequestEntity> findOutgoingForPage(
      @Param("userId") Integer userId,
      @Param("status") FriendRequestStatus status,
      @Param("cursor") Integer cursor,
      Pageable pageable);

  List<FriendRequestEntity> findByRequesterIdAndStatusOrderByCreatedAtDesc(
      Integer requesterId, FriendRequestStatus status);

  @Query(
      """
      SELECT f FROM FriendRequestEntity f
      WHERE ((f.requesterId = :a AND f.addresseeId = :b)
          OR (f.requesterId = :b AND f.addresseeId = :a))
        AND f.status = :status
      """)
  Optional<FriendRequestEntity> findByParticipantsAndStatus(
      @Param("a") Integer a, @Param("b") Integer b, @Param("status") FriendRequestStatus status);

  /**
   * Clears the accepted request(s) that recorded a now-ended friendship.
   *
   * <p>Deletes rather than marks: the row's only job is to say these two are friends — Neo4j holds
   * the friendship itself — and a row left behind says the opposite of the truth. Written as a
   * bulk delete over both directions because the pair can only be identified by two columns whose
   * order depends on who asked first, and because a pair can carry more than one accepted row from
   * repeated friend/unfriend cycles, which a single-result lookup would blow up on.
   */
  @Modifying
  @Query(
      """
      DELETE FROM FriendRequestEntity f
      WHERE ((f.requesterId = :a AND f.addresseeId = :b)
          OR (f.requesterId = :b AND f.addresseeId = :a))
        AND f.status = com.socialapp.friendships.entity.enums.FriendRequestStatus.ACCEPTED
      """)
  int deleteAcceptedBetween(@Param("a") Integer a, @Param("b") Integer b);

  /**
   * Cancels any request still waiting for an answer between two users, in either direction.
   *
   * <p>Used when one blocks the other: leaving a pending request alive would let the blocked user
   * become a friend by accepting it, straight through the block. Cancelled rather than deleted —
   * unlike the accepted row above, a request that was sent is a thing that happened, and CANCELLED
   * is already how this app records a request that ended without an answer.
   */
  @Modifying
  @Query(
      """
      UPDATE FriendRequestEntity f
      SET f.status = com.socialapp.friendships.entity.enums.FriendRequestStatus.CANCELLED
      WHERE ((f.requesterId = :a AND f.addresseeId = :b)
          OR (f.requesterId = :b AND f.addresseeId = :a))
        AND f.status = com.socialapp.friendships.entity.enums.FriendRequestStatus.PENDING
      """)
  int cancelPendingBetween(@Param("a") Integer a, @Param("b") Integer b);

  @Query(
      """
      SELECT CASE WHEN COUNT(f) > 0 THEN true ELSE false END
      FROM FriendRequestEntity f
      WHERE ((f.requesterId = :a AND f.addresseeId = :b)
          OR (f.requesterId = :b AND f.addresseeId = :a))
        AND f.status = 'PENDING'
      """)
  boolean hasPendingRequestBetween(@Param("a") Integer a, @Param("b") Integer b);
}
