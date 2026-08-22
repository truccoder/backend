package com.socialapp.roadmap.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.socialapp.roadmap.entity.UserRoadmapProgressEntity;
import com.socialapp.roadmap.enums.VerificationStatus;

@Repository
public interface UserRoadmapProgressRepository
    extends JpaRepository<UserRoadmapProgressEntity, Integer> {
  List<UserRoadmapProgressEntity> findByUserId(Integer userId);

  List<UserRoadmapProgressEntity> findByStatus(VerificationStatus status);

  /**
   * Same rows as {@link #findByStatus}, but with {@code user} and {@code node} joined in — the
   * moderation queue reads both for every row, which was two extra queries apiece without this.
   */
  @Query(
      "SELECT p FROM UserRoadmapProgressEntity p "
          + "JOIN FETCH p.user JOIN FETCH p.node "
          + "WHERE p.status = :status")
  List<UserRoadmapProgressEntity> findByStatusWithUserAndNode(
      @Param("status") VerificationStatus status);

  /**
   * One user's whole roadmap progress with the node joined in.
   *
   * <p>{@link #findByUserId} leaves {@code node} lazy, and with {@code open-in-view} off every row
   * of the profile card would then be its own query — or a {@code LazyInitializationException} if
   * the mapping happened one layer too late.
   */
  @Query(
      "SELECT p FROM UserRoadmapProgressEntity p "
          + "JOIN FETCH p.node "
          + "WHERE p.user.id = :userId "
          + "ORDER BY p.node.orderIndex ASC, p.node.id ASC")
  List<UserRoadmapProgressEntity> findByUserIdWithNode(@Param("userId") Integer userId);

  /**
   * Just the node names of one user's verified skills, in the order the roadmap lists them.
   *
   * <p>A projection rather than {@link #findByUserIdWithNode} filtered in Java: the public profile
   * header renders a strip of names and nothing else, and loading whole progress rows to read one
   * column off each also loads {@code proofUrl} — a private link submitted to a moderator — into a
   * service that serves guests. What is never fetched cannot be leaked by a later edit to a
   * mapper.
   */
  @Query(
      "SELECT p.node.name FROM UserRoadmapProgressEntity p "
          + "WHERE p.user.id = :userId AND p.status = :status "
          + "ORDER BY p.node.orderIndex ASC, p.node.id ASC")
  List<String> findSkillNamesByUserIdAndStatus(
      @Param("userId") Integer userId, @Param("status") VerificationStatus status);

  Optional<UserRoadmapProgressEntity> findByUserIdAndNodeId(Integer userId, Integer nodeId);

  boolean existsByUserIdAndStatus(Integer userId, VerificationStatus status);
}
