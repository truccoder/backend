package com.socialapp.moderation.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import com.socialapp.moderation.entity.UserBanEntity;

@Repository
public interface UserBanRepository extends JpaRepository<UserBanEntity, Long> {
  List<UserBanEntity> findByUserIdOrderByCreatedAtDesc(Integer userId);

  /**
   * Every ban belonging to a page of users, newest first, in one query.
   *
   * <p>Same reason as {@code ModerationLogRepository.findByPostIdInOrderByCreatedAtAsc}: the
   * banned-user list loaded one user and one ban history per row.
   */
  List<UserBanEntity> findByUserIdInOrderByCreatedAtDesc(Collection<Integer> userIds);

  long countByUserId(Integer userId);

  @Query(
      """
                      SELECT b.userId FROM UserBanEntity b
                      GROUP BY b.userId
                      ORDER BY MAX(b.createdAt) DESC
                    """)
  Page<Integer> findBannedUserIds(Pageable pageable);
}
