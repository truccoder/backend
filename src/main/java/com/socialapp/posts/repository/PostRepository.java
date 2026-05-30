package com.socialapp.posts.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.socialapp.moderation.enums.ModerationStatus;
import com.socialapp.posts.entity.PostEntity;

public interface PostRepository extends JpaRepository<PostEntity, Integer> {
  List<PostEntity> findByModerationStatus(ModerationStatus status);

  List<PostEntity> findByAuthorIdAndModerationStatus(Integer authorId, ModerationStatus status);
}
