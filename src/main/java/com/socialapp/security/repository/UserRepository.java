package com.socialapp.security.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.socialapp.security.entity.UserEntity;

public interface UserRepository extends JpaRepository<UserEntity, Integer> {
  boolean existsByEmailIgnoreCase(String email);

  @Modifying
  @Query("UPDATE UserEntity u SET u.eliteScore = u.eliteScore + :delta WHERE u.id = :userId")
  void adjustEliteScore(@Param("userId") Integer userId, @Param("delta") int delta);

  @Modifying
  @Query("UPDATE UserEntity u SET u.eliteScore = :score WHERE u.id = :userId")
  void setEliteScore(@Param("userId") Integer userId, @Param("score") int score);

  Optional<UserEntity> findByEmail(String email);

  @Query("SELECT u FROM UserEntity u WHERE LOWER(u.email) = LOWER(:email)")
  Optional<UserEntity> findByEmailIgnoreCase(@Param("email") String email);

  /**
   * Looks a user up by handle, case-insensitively.
   *
   * <p>The public profile is routed by username rather than by id so that a stranger cannot
   * enumerate the user table by counting upwards. {@code LOWER(...)} on both sides matches the
   * {@code uq_users_username_lower} unique index from {@code V47}, so this is an index lookup and
   * so it cannot disagree with what the database considers a duplicate.
   */
  @Query("SELECT u FROM UserEntity u WHERE LOWER(u.username) = LOWER(:username)")
  Optional<UserEntity> findByUsernameIgnoreCase(@Param("username") String username);

  @Query(
      """
      SELECT CASE WHEN COUNT(u) > 0 THEN true ELSE false END
      FROM UserEntity u WHERE LOWER(u.username) = LOWER(:username)
      """)
  boolean existsByUsernameIgnoreCase(@Param("username") String username);

  /**
   * {@code excludedIds} carries the caller's block set: someone a user has blocked, or who has
   * blocked them, must not be findable by name — otherwise the block hides their posts while
   * leaving a working search box pointed at their profile. Like {@code friendIds} it must never be
   * empty; the caller substitutes a sentinel id, because {@code NOT IN ()} is not valid SQL.
   *
   * <p>{@code f_unaccent}, not {@code unaccent}: the trigram GIN indexes from {@code
   * V48__add_trigram_search_indexes.sql} are built on the wrapper, and Postgres matches an
   * expression index by the parsed expression. Plain {@code unaccent} still returns the right
   * users, just via a full scan of t_users.
   */
  @Query(
      """
                      SELECT u FROM UserEntity u
                      WHERE (cast(function('f_unaccent', LOWER(u.fullName)) as string)
                              LIKE cast(function('f_unaccent', LOWER(CONCAT('%', :query, '%'))) as string) ESCAPE '\\'
                         OR cast(function('f_unaccent', LOWER(u.username)) as string)
                              LIKE cast(function('f_unaccent', LOWER(CONCAT('%', :query, '%'))) as string) ESCAPE '\\')
                        AND u.id NOT IN :excludedIds
                      ORDER BY CASE WHEN u.id IN :friendIds THEN 0 ELSE 1 END, u.fullName ASC
                    """)
  Page<UserEntity> search(
      @Param("query") String query,
      @Param("friendIds") List<Integer> friendIds,
      @Param("excludedIds") Collection<Integer> excludedIds,
      Pageable pageable);

  /**
   * The type-ahead variant of {@link #search}: same match, no friend-first ranking.
   *
   * <p>Dropping {@code friendIds} is the whole point of having a second query. Ranking friends
   * first means the caller must first fetch the viewer's friend ids, which lives in Neo4j — a
   * second database round trip, on every keystroke. The results page can afford that; a dropdown
   * that fires per character cannot.
   *
   * <p>{@code excludedIds} is not similarly optional: it carries the block set, and a suggestion
   * box that completes the name of someone who blocked you undoes the block on its own. Same
   * never-empty sentinel rule as {@link #search}.
   */
  @Query(
      """
                      SELECT u FROM UserEntity u
                      WHERE (cast(function('f_unaccent', LOWER(u.fullName)) as string)
                              LIKE cast(function('f_unaccent', LOWER(CONCAT('%', :query, '%'))) as string) ESCAPE '\\'
                         OR cast(function('f_unaccent', LOWER(u.username)) as string)
                              LIKE cast(function('f_unaccent', LOWER(CONCAT('%', :query, '%'))) as string) ESCAPE '\\')
                        AND u.id NOT IN :excludedIds
                      ORDER BY u.fullName ASC, u.id ASC
                    """)
  List<UserEntity> suggest(
      @Param("query") String query,
      @Param("excludedIds") Collection<Integer> excludedIds,
      Pageable pageable);
}
