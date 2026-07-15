package com.socialapp.knowledge.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.socialapp.knowledge.entity.UserProfessionalProfileEntity;

public interface UserProfessionalProfileRepository
    extends JpaRepository<UserProfessionalProfileEntity, Integer> {

  @org.springframework.data.jpa.repository.Query(
      value =
          "SELECT * FROM t_user_professional_profiles p "
              + "WHERE EXISTS (SELECT 1 FROM jsonb_array_elements_text(p.known_tech_stack) as skill WHERE skill IN :skills)",
      nativeQuery = true)
  java.util.List<UserProfessionalProfileEntity> findBySkillsMatch(
      @org.springframework.data.repository.query.Param("skills") java.util.List<String> skills);
}
