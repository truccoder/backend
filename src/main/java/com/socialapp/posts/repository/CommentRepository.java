package com.socialapp.posts.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.socialapp.posts.entity.CommentEntity;

@Repository
public interface CommentRepository extends JpaRepository<CommentEntity, Integer> {
  List<CommentEntity> findByPostIdOrderByCreatedAtAsc(Integer postId);

  List<CommentEntity> findByPostIdAndParentIdIsNullOrderByCreatedAtAsc(Integer postId);

  List<CommentEntity> findByParentIdOrderByCreatedAtAsc(Integer parentId);

  boolean existsByIdAndParentIdIsNull(Integer id);

  // Counts replies as well as top-level comments, matching what the feed card claims to show.
  long countByPostId(Integer postId);
}
