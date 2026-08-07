package com.socialapp.knowledge.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.socialapp.knowledge.entity.UserProfessionalProfileEntity;

public interface UserProfessionalProfileRepository
    extends JpaRepository<UserProfessionalProfileEntity, Integer> {

  /**
   * <p><b>{@code socialapp.} is not optional here.</b> {@code hibernate.default_schema: socialapp}
   * qualifies table names that Hibernate generates from entity mappings; it does not touch a
   * native query, which is passed to the driver as written and resolved against the connection's
   * {@code search_path}. Without the prefix this query raised "relation
   * t_user_professional_profiles does not exist", which surfaced as a 500 on {@code GET
   * /v1/api/projects/positions/{id}/suggested-candidates}. Every other native query in this repo
   * spells the schema out; this was the one that did not.
   */
  @org.springframework.data.jpa.repository.Query(
      value =
          "SELECT * FROM socialapp.t_user_professional_profiles p "
              + "WHERE EXISTS (SELECT 1 FROM jsonb_array_elements_text(p.known_tech_stack) as skill WHERE skill IN :skills)",
      nativeQuery = true)
  java.util.List<UserProfessionalProfileEntity> findBySkillsMatch(
      @org.springframework.data.repository.query.Param("skills") java.util.List<String> skills);
}
