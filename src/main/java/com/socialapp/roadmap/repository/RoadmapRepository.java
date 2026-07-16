package com.socialapp.roadmap.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.socialapp.roadmap.entity.RoadmapEntity;

@Repository
public interface RoadmapRepository extends JpaRepository<RoadmapEntity, Integer> {}
