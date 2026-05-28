package com.socialapp.friendships.repository;

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

  @Query(
      """
      OPTIONAL MATCH (u1:User {userId: $userId1})-[:FRIENDS_WITH]-(u2:User {userId: $userId2})
      RETURN u2 IS NOT NULL
      """)
  boolean areFriends(@Param("userId1") Integer userId1, @Param("userId2") Integer userId2);
}
