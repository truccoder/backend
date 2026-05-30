package com.socialapp.posts.repository;

import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.socialapp.posts.entity.PostReactionEntity;
import com.socialapp.posts.entity.PostReactionId;
import com.socialapp.posts.entity.enums.ReactionType;

public interface PostReactionRepository extends JpaRepository<PostReactionEntity, PostReactionId> {
  long countByIdPostId(Integer postId);

  @Query(
      """
      SELECT r.reactionType, COUNT(r) FROM PostReactionEntity r
      WHERE r.id.postId = :postId
      GROUP BY r.reactionType
      """)
  java.util.List<Object[]> countByTypeRaw(@Param("postId") Integer postId);

  default Map<ReactionType, Long> countByType(Integer postId) {
    return countByTypeRaw(postId).stream()
        .collect(Collectors.toMap(row -> (ReactionType) row[0], row -> (Long) row[1]));
  }
}
