package com.socialapp.friendships.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.data.repository.query.Param;

import com.socialapp.friendships.entity.UserNode;

public interface FriendshipRepository extends Neo4jRepository<UserNode, Long> {
  Optional<UserNode> findByUserId(Integer userId);

  @Query("MERGE (u:User {userId: $userId}) RETURN u")
  UserNode mergeUser(@Param("userId") Integer userId);

  @Query(
      """
      MATCH (u1:User {userId: $userId1}), (u2:User {userId: $userId2})
      MERGE (u1)-[:FRIENDS_WITH]-(u2)
      """)
  void createFriendship(@Param("userId1") Integer userId1, @Param("userId2") Integer userId2);

  /**
   * Removes the friendship, whichever direction it was written in.
   *
   * <p>The pattern is undirected ({@code -[r:FRIENDS_WITH]-}) to match the undirected {@code MERGE}
   * in {@link #createFriendship}. Neo4j always stores a relationship with a direction even when it
   * is created without one, so a directed delete here would remove only the half that happens to
   * point the way it was written and leave the other pair orderings untouched — and {@link
   * #countFriends}, which also matches undirected, would keep counting the leftover as a friend.
   */
  @Query(
      """
      MATCH (u1:User {userId: $userId1})-[r:FRIENDS_WITH]-(u2:User {userId: $userId2})
      DELETE r
      """)
  void deleteFriendship(@Param("userId1") Integer userId1, @Param("userId2") Integer userId2);

  @Query(
      """
      OPTIONAL MATCH (u1:User {userId: $userId1})-[:FRIENDS_WITH]-(u2:User {userId: $userId2})
      RETURN u2 IS NOT NULL
      """)
  boolean areFriends(@Param("userId1") Integer userId1, @Param("userId2") Integer userId2);

  @Query(
      """
      MATCH (u:User {userId: $userId})-[:FRIENDS_WITH]-(friend:User)
      RETURN friend.userId
      """)
  List<Integer> findFriendIds(@Param("userId") Integer userId);

  @Query(
      """
                    MATCH (u:User {userId: $userId})-[:FRIENDS_WITH]-(friend:User)
                    WHERE $cursor IS NULL OR friend.userId > $cursor
                    RETURN friend.userId AS userId
                    ORDER BY friend.userId ASC
                    LIMIT $limit
                    """)
  List<Integer> findFriendIdsAfterCursor(
      @Param("userId") Integer userId, @Param("cursor") Integer cursor, @Param("limit") int limit);

  @Query(
      """
                    MATCH (u:User {userId: $userId})-[:FRIENDS_WITH]-(friend:User)
                    RETURN COUNT(friend)
                    """)
  long countFriends(@Param("userId") Integer userId);
}
