package com.socialapp.posts.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.socialapp.posts.entity.PostEntity;

public interface PostRepository extends JpaRepository<PostEntity, Integer> {}
