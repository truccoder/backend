package com.socialapp.friendships.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.socialapp.friendships.entity.FriendRequestEntity;
import com.socialapp.friendships.entity.enums.FriendRequestStatus;

public interface FriendRequestRepository extends JpaRepository<FriendRequestEntity, Integer> {
  @Query(
      """
      SELECT f FROM FriendRequestEntity f
      WHERE ((f.requesterId = :a AND f.addresseeId = :b)
          OR (f.requesterId = :b AND f.addresseeId = :a))
        AND f.status = :status
      """)
  Optional<FriendRequestEntity> findByParticipantsAndStatus(
      @Param("a") Integer a, @Param("b") Integer b, @Param("status") FriendRequestStatus status);

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
