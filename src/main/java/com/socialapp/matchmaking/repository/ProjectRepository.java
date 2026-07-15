package com.socialapp.matchmaking.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.socialapp.matchmaking.entity.ProjectEntity;

public interface ProjectRepository extends JpaRepository<ProjectEntity, Integer> {}
