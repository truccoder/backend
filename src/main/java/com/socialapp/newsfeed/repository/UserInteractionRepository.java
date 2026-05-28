package com.socialapp.newsfeed.repository;

import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.socialapp.newsfeed.entity.UserInteractionEntity;

public interface UserInteractionRepository extends JpaRepository<UserInteractionEntity, Integer> {
  @Query(
      """
      SELECT ui.authorId AS authorId, COUNT(ui) AS interactionCount
      FROM UserInteractionEntity ui
      WHERE ui.userId = :userId AND ui.createdAt > :since
      GROUP BY ui.authorId
      """)
  List<AuthorInteractionCount> countInteractionsByAuthor(
      @Param("userId") Integer userId, @Param("since") OffsetDateTime since);
}
