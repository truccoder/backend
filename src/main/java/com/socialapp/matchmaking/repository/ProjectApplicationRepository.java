package com.socialapp.matchmaking.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.socialapp.matchmaking.entity.ProjectApplicationEntity;

public interface ProjectApplicationRepository
    extends JpaRepository<ProjectApplicationEntity, Integer> {}
