package com.socialapp.posts.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.socialapp.posts.entity.CommentEntity;

@Repository
public interface CommentRepository extends JpaRepository<CommentEntity, Integer> {
  List<CommentEntity> findByPostIdAndParentIdIsNullOrderByCreatedAtAsc(Integer postId);

  List<CommentEntity> findByParentIdOrderByCreatedAtAsc(Integer parentId);

  boolean existsByIdAndParentIdIsNull(Integer id);
}
