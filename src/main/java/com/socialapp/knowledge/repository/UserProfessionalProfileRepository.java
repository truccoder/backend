package com.socialapp.knowledge.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.socialapp.knowledge.entity.UserProfessionalProfileEntity;

public interface UserProfessionalProfileRepository
    extends JpaRepository<UserProfessionalProfileEntity, Integer> {

  /**
   * The best-matching profiles for a set of required skills, most overlap first.
   *
   * <p>This replaced a plain "shares at least one skill" filter that returned every matching row
   * in whatever order Postgres felt like. Three things were wrong with that and are fixed here:
   *
   * <ul>
   *   <li><b>Ranking.</b> Someone matching one skill out of five ranked identically to someone
   *       matching all five. The {@code ORDER BY} counts the overlap so the pool handed to the
   *       service is already the people worth looking at.
   *   <li><b>No bound.</b> With no {@code LIMIT}, a position requiring something as common as
   *       {@code "Java"} selected most of the profile table into memory to be mapped into DTOs.
   *   <li><b>Case.</b> {@code skill IN :skills} is case-sensitive, so a profile listing {@code
   *       "react"} did not match a position asking for {@code "React"}. Both sides are lowered —
   *       callers must pass {@code skills} already lowercased, which is the half SQL cannot do
   *       for them.
   * </ul>
   *
   * <p>{@code excludeUserId} keeps the project owner out of their own candidate list. It is a
   * parameter rather than a Java-side filter because the row should never be fetched at all: it
   * would otherwise occupy a slot in the {@code LIMIT}.
   *
   * <p><b>{@code socialapp.} is not optional here.</b> {@code hibernate.default_schema: socialapp}
   * qualifies table names that Hibernate generates from entity mappings; it does not touch a
   * native query, which is passed to the driver as written and resolved against the connection's
   * {@code search_path}. Without the prefix this query raised "relation
   * t_user_professional_profiles does not exist", which surfaced as a 500 on {@code GET
   * /v1/api/projects/positions/{id}/suggested-candidates}. Every other native query in this repo
   * spells the schema out; this was the one that did not.
   */
  @Query(
      value =
          """
          SELECT * FROM socialapp.t_user_professional_profiles p
          WHERE p.user_id <> :excludeUserId
            AND EXISTS (SELECT 1 FROM jsonb_array_elements_text(p.known_tech_stack) AS skill
                        WHERE lower(skill) IN (:skills))
          ORDER BY (SELECT count(*) FROM jsonb_array_elements_text(p.known_tech_stack) AS skill
                    WHERE lower(skill) IN (:skills)) DESC,
                   p.user_id
          LIMIT :poolSize
          """,
      nativeQuery = true)
  List<UserProfessionalProfileEntity> findCandidatesBySkills(
      @Param("skills") List<String> skills,
      @Param("excludeUserId") Integer excludeUserId,
      @Param("poolSize") int poolSize);
}
