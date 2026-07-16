package com.socialapp.roadmap.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.socialapp.roadmap.entity.RoadmapNodeEntity;

@Repository
public interface RoadmapNodeRepository extends JpaRepository<RoadmapNodeEntity, Integer> {
  List<RoadmapNodeEntity> findByRoadmapId(Integer roadmapId);
}
