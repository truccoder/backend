package com.socialapp.matchmaking.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.socialapp.matchmaking.entity.ProjectApplicationEntity;
import com.socialapp.matchmaking.entity.enums.ApplicationStatus;

public interface ProjectApplicationRepository
    extends JpaRepository<ProjectApplicationEntity, Integer> {
  long countByPositionIdAndStatus(Integer positionId, ApplicationStatus status);

  /** Whether this applicant has already applied to this position — one application each. */
  boolean existsByPositionIdAndApplicantId(Integer positionId, Integer applicantId);

  /**
   * Whether a position has at least one application in the given status — the guard behind
   * "cannot delete a position that has an accepted member". A derived {@code exists} rather than
   * a count because the caller only asks yes/no.
   */
  boolean existsByPositionIdAndStatus(Integer positionId, ApplicationStatus status);

  /**
   * The team on a project: its {@code ACCEPTED} applications, newest-accepted first, with
   * applicant and position join-fetched for {@link
   * com.socialapp.matchmaking.dto.ProjectMemberDto#from}. Status is passed rather than hardcoded
   * so the one caller ({@code ProjectQueryService.getMembers}) states its intent at the call
   * site.
   */
  @Query(
      """
      SELECT a FROM ProjectApplicationEntity a
      JOIN FETCH a.applicant
      JOIN FETCH a.position
      WHERE a.project.id = :projectId AND a.status = :status
      ORDER BY a.updatedAt DESC, a.id DESC
      """)
  List<ProjectApplicationEntity> findByProjectIdAndStatusForRoster(
      @Param("projectId") Integer projectId, @Param("status") ApplicationStatus status);

  /**
   * One user's applications to one project in a given status, position join-fetched. Backs the
   * owner's "remove from team" action: a person accepted to two roles on the same project has two
   * {@code ACCEPTED} rows, and removing the member ends both.
   */
  @Query(
      """
      SELECT a FROM ProjectApplicationEntity a
      JOIN FETCH a.position
      WHERE a.project.id = :projectId
        AND a.applicant.id = :applicantId
        AND a.status = :status
      """)
  List<ProjectApplicationEntity> findByProjectAndApplicantAndStatus(
      @Param("projectId") Integer projectId,
      @Param("applicantId") Integer applicantId,
      @Param("status") ApplicationStatus status);

  /**
   * Deletes every application row for a position, whatever its status. Called just before the
   * position itself is deleted: the {@code t_project_applications.position_id} FK is {@code ON
   * DELETE CASCADE}, so the database would clear these anyway, but doing it explicitly keeps
   * Hibernate's first-level cache from holding rows the database has removed underneath it.
   */
  @Modifying
  @Query("DELETE FROM ProjectApplicationEntity a WHERE a.position.id = :positionId")
  void deleteByPositionId(@Param("positionId") Integer positionId);

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

  /**
   * Everyone who has already applied to this position, whatever came of it.
   *
   * <p>For filtering candidate suggestions. Deliberately not restricted to {@code PENDING}:
   * re-suggesting someone the owner already rejected is worse than useless, and re-suggesting
   * someone already accepted is plainly wrong.
   *
   * <p>Ids only rather than entities — the caller needs a {@code contains} check, and loading
   * whole applications with their three associations to read one column each would be the same
   * mistake {@code findByProjectIdForInbox} exists to avoid.
   */
  @Query("SELECT a.applicant.id FROM ProjectApplicationEntity a WHERE a.position.id = :positionId")
  List<Integer> findApplicantIdsByPositionId(@Param("positionId") Integer positionId);

  /**
   * Every project this user has already applied to, through any of its positions.
   *
   * <p>{@code DISTINCT} because a project with several open roles can hold several applications
   * from the same person, and the caller only asks "have I engaged with this project already?".
   */
  @Query(
      "SELECT DISTINCT a.project.id FROM ProjectApplicationEntity a "
          + "WHERE a.applicant.id = :applicantId")
  List<Integer> findProjectIdsByApplicantId(@Param("applicantId") Integer applicantId);

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
