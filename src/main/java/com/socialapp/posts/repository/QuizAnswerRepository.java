package com.socialapp.posts.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.socialapp.posts.entity.QuizAnswerEntity;

@Repository
public interface QuizAnswerRepository extends JpaRepository<QuizAnswerEntity, Integer> {
  boolean existsByPostIdAndUserId(Integer postId, Integer userId);
}
