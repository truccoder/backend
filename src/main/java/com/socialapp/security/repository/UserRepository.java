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
   * Resolves several handles at once, case-insensitively — the batch form of {@link
   * #findByUsernameIgnoreCase}.
   *
   * <p>For {@code MentionScanner}: a comment can name up to ten people, and looking each one up in
   * turn would be ten round trips on the write path of every comment that contains an {@code @}.
   * Handles that match nobody are simply absent from the result, which is the answer the caller
   * wants — a comment mentioning a handle that does not exist is a comment, not an error.
   *
   * <p>The handles are lower-cased by the caller and compared against {@code LOWER(u.username)}, so
   * this uses the {@code uq_users_username_lower} index from {@code V47} rather than scanning.
   */
  @Query("SELECT u FROM UserEntity u WHERE LOWER(u.username) IN :usernames")
  List<UserEntity> findAllByUsernameLowerIn(@Param("usernames") Collection<String> usernames);

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

  /**
   * The friends the caller may tag, for an {@code @}-dropdown opened before anything is typed.
   *
   * <p>Its own query rather than {@link #suggestMentions} with an empty {@code query}: an empty
   * pattern matches the whole user table, so reusing that one here would answer "who can I tag?"
   * with a page of strangers ordered by name — a user-directory dump, handed out one keystroke
   * after somebody types {@code @}. Before a query narrows anything, the only safe answer is the
   * people the caller already has an edge to.
   *
   * <p>Both id lists follow the never-empty sentinel rule of {@link #search}: {@code IN ()} and
   * {@code NOT IN ()} are not valid SQL. {@code excludedIds} always holds at least the caller's own
   * id, and {@code friendIds} is substituted with a sentinel when the caller has no friends — which
   * then correctly matches nobody.
   */
  @Query(
      """
                      SELECT u FROM UserEntity u
                      WHERE u.id IN :friendIds
                        AND u.id NOT IN :excludedIds
                      ORDER BY u.fullName ASC, u.id ASC
                    """)
  List<UserEntity> findMentionableFriends(
      @Param("friendIds") Collection<Integer> friendIds,
      @Param("excludedIds") Collection<Integer> excludedIds,
      Pageable pageable);

  /**
   * The {@code @}-dropdown once the caller has typed something: same match as {@link #suggest},
   * ranked for tagging rather than for navigating.
   *
   * <p>Three sort keys, in the order the composer needs them.
   *
   * <ol>
   *   <li><b>Friends first.</b> The whole point of the endpoint. It costs the caller a Neo4j round
   *       trip to fetch the friend ids — the exact cost {@link #suggest} refuses to pay per
   *       keystroke — and it is worth paying here because the search box is guessing where you want
   *       to go, while this box already knows you are naming a person, and the people anyone names
   *       are overwhelmingly the ones they are connected to.
   *   <li><b>Prefix before substring.</b> Somebody typing {@code @tr} means a name that starts with
   *       "tr", not one that happens to contain it; without this key {@code Nguyen Tran} and {@code
   *       Bui Van Trong} are ordered by whichever comes first alphabetically. Free — it is one more
   *       ORDER BY expression on rows already being fetched, not a second query.
   *   <li><b>Name, then id.</b> A total order, so paging and repeat keystrokes stay stable.
   * </ol>
   *
   * <p>Non-friends are still returned, after every friend: people tag colleagues and authors they
   * have not friended, and a dropdown that cannot reach them sends the user back to typing the
   * handle from memory. The client tells the two groups apart by {@code isFriend}, not by position.
   *
   * <p>{@code f_unaccent} rather than {@code unaccent}, for the reason {@link #search} gives: the
   * trigram indexes from {@code V48} are built on the wrapper.
   */
  @Query(
      """
                      SELECT u FROM UserEntity u
                      WHERE (cast(function('f_unaccent', LOWER(u.fullName)) as string)
                              LIKE cast(function('f_unaccent', LOWER(CONCAT('%', :query, '%'))) as string) ESCAPE '\\'
                         OR cast(function('f_unaccent', LOWER(u.username)) as string)
                              LIKE cast(function('f_unaccent', LOWER(CONCAT('%', :query, '%'))) as string) ESCAPE '\\')
                        AND u.id NOT IN :excludedIds
                      ORDER BY CASE WHEN u.id IN :friendIds THEN 0 ELSE 1 END,
                               CASE WHEN cast(function('f_unaccent', LOWER(u.username)) as string)
                                          LIKE cast(function('f_unaccent', LOWER(CONCAT(:query, '%'))) as string) ESCAPE '\\'
                                      OR cast(function('f_unaccent', LOWER(u.fullName)) as string)
                                          LIKE cast(function('f_unaccent', LOWER(CONCAT(:query, '%'))) as string) ESCAPE '\\'
                                    THEN 0 ELSE 1 END,
                               u.fullName ASC, u.id ASC
                    """)
  List<UserEntity> suggestMentions(
      @Param("query") String query,
      @Param("friendIds") Collection<Integer> friendIds,
      @Param("excludedIds") Collection<Integer> excludedIds,
      Pageable pageable);
}
