package com.socialapp.blocks.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.socialapp.blocks.entity.UserBlockEntity;
import com.socialapp.blocks.entity.UserBlockId;

public interface UserBlockRepository extends JpaRepository<UserBlockEntity, UserBlockId> {

  List<UserBlockEntity> findByIdBlockerIdOrderByCreatedAtDesc(Integer blockerId);

  /** Who this user has blocked. */
  @Query("SELECT b.id.blockedId FROM UserBlockEntity b WHERE b.id.blockerId = :userId")
  List<Integer> findBlockedIds(@Param("userId") Integer userId);

  /** Who has blocked this user — the half a filter is most likely to forget. */
  @Query("SELECT b.id.blockerId FROM UserBlockEntity b WHERE b.id.blockedId = :userId")
  List<Integer> findBlockerIds(@Param("userId") Integer userId);

  /** Whether either of the two has blocked the other. */
  @Query(
      """
      SELECT CASE WHEN COUNT(b) > 0 THEN true ELSE false END
      FROM UserBlockEntity b
      WHERE (b.id.blockerId = :a AND b.id.blockedId = :b)
         OR (b.id.blockerId = :b AND b.id.blockedId = :a)
      """)
  boolean existsBetween(@Param("a") Integer a, @Param("b") Integer b);
}
