package com.socialapp.matchmaking.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.socialapp.matchmaking.entity.ProjectApplicationEntity;
import com.socialapp.matchmaking.entity.enums.ApplicationStatus;

public interface ProjectApplicationRepository
    extends JpaRepository<ProjectApplicationEntity, Integer> {
  long countByPositionIdAndStatus(Integer positionId, ApplicationStatus status);

  /**
   * The applications sent to one project, newest first — a project owner's inbox.
   *
   * <p>Everything the DTO reads is join-fetched. {@code ProjectApplicationResponseDto} walks
   * project, position and applicant, so leaving those lazy turns a 20-row inbox into 61 queries
   * with {@code open-in-view} off.
   */
  @Query(
      """
      SELECT a FROM ProjectApplicationEntity a
      JOIN FETCH a.project
      JOIN FETCH a.position
      JOIN FETCH a.applicant
      WHERE a.project.id = :projectId
      ORDER BY a.id DESC
      """)
  List<ProjectApplicationEntity> findByProjectIdForInbox(@Param("projectId") Integer projectId);

  /** The applications one user has sent, newest first. Same join-fetch reasoning. */
  @Query(
      """
      SELECT a FROM ProjectApplicationEntity a
      JOIN FETCH a.project
      JOIN FETCH a.position
      JOIN FETCH a.applicant
      WHERE a.applicant.id = :applicantId
      ORDER BY a.id DESC
      """)
  List<ProjectApplicationEntity> findByApplicantId(@Param("applicantId") Integer applicantId);
}
