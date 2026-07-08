package com.socialapp.security.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.socialapp.security.entity.UserEntity;

public interface UserRepository extends JpaRepository<UserEntity, Integer> {
  boolean existsByEmailIgnoreCase(String email);

  Optional<UserEntity> findByEmail(String email);

  @Query("SELECT u FROM UserEntity u WHERE LOWER(u.email) = LOWER(:email)")
  Optional<UserEntity> findByEmailIgnoreCase(@Param("email") String email);

  @Query(
      """
                      SELECT u FROM UserEntity u
                      WHERE cast(function('unaccent', LOWER(u.fullName)) as string)
                              LIKE cast(function('unaccent', LOWER(CONCAT('%', :query, '%'))) as string) ESCAPE '\\'
                         OR cast(function('unaccent', LOWER(u.username)) as string)
                              LIKE cast(function('unaccent', LOWER(CONCAT('%', :query, '%'))) as string) ESCAPE '\\'
                      ORDER BY CASE WHEN u.id IN :friendIds THEN 0 ELSE 1 END, u.fullName ASC
                    """)
  Page<UserEntity> search(
      @Param("query") String query, @Param("friendIds") List<Integer> friendIds, Pageable pageable);
}
