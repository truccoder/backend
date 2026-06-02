package com.socialapp.posts.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.socialapp.posts.entity.EventRsvpEntity;
import com.socialapp.posts.entity.enums.RsvpStatus;

public interface EventRsvpRepository extends JpaRepository<EventRsvpEntity, Integer> {

  Optional<EventRsvpEntity> findByPostIdAndUserId(Integer postId, Integer userId);

  List<EventRsvpEntity> findByPostIdAndStatus(Integer postId, RsvpStatus status);

  int countByPostIdAndStatus(Integer postId, RsvpStatus status);

  List<EventRsvpEntity> findByPostId(Integer postId);
}
