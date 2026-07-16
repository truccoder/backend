package com.socialapp.roadmap.service;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialapp.roadmap.dto.RoadmapDto;
import com.socialapp.roadmap.dto.RoadmapNodeDto;
import com.socialapp.roadmap.entity.RoadmapEntity;
import com.socialapp.roadmap.entity.RoadmapNodeEntity;
import com.socialapp.roadmap.repository.RoadmapNodeRepository;
import com.socialapp.roadmap.repository.RoadmapRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class RoadmapService {

  private final RoadmapRepository roadmapRepository;
  private final RoadmapNodeRepository roadmapNodeRepository;

  @Transactional
  public RoadmapDto createRoadmap(RoadmapDto dto) {
    RoadmapEntity entity =
        RoadmapEntity.builder().name(dto.getName()).description(dto.getDescription()).build();
    entity = roadmapRepository.save(entity);
    dto.setId(entity.getId());
    return dto;
  }

  public List<RoadmapDto> getAllRoadmaps() {
    return roadmapRepository.findAll().stream()
        .map(
            e -> {
              RoadmapDto dto = new RoadmapDto();
              dto.setId(e.getId());
              dto.setName(e.getName());
              dto.setDescription(e.getDescription());
              return dto;
            })
        .collect(Collectors.toList());
  }

  @Transactional
  public RoadmapNodeDto addNodeToRoadmap(Integer roadmapId, RoadmapNodeDto dto) {
    RoadmapEntity roadmap =
        roadmapRepository
            .findById(roadmapId)
            .orElseThrow(() -> new RuntimeException("Roadmap not found"));

    RoadmapNodeEntity parentNode = null;
    if (dto.getParentNodeId() != null) {
      parentNode =
          roadmapNodeRepository
              .findById(dto.getParentNodeId())
              .orElseThrow(() -> new RuntimeException("Parent node not found"));
    }

    RoadmapNodeEntity node =
        RoadmapNodeEntity.builder()
            .roadmap(roadmap)
            .name(dto.getName())
            .description(dto.getDescription())
            .parentNode(parentNode)
            .orderIndex(dto.getOrderIndex() != null ? dto.getOrderIndex() : 0)
            .build();

    node = roadmapNodeRepository.save(node);
    dto.setId(node.getId());
    dto.setRoadmapId(roadmapId);
    return dto;
  }

  public List<RoadmapNodeDto> getRoadmapNodes(Integer roadmapId) {
    return roadmapNodeRepository.findByRoadmapId(roadmapId).stream()
        .map(
            e -> {
              RoadmapNodeDto dto = new RoadmapNodeDto();
              dto.setId(e.getId());
              dto.setRoadmapId(e.getRoadmap().getId());
              dto.setName(e.getName());
              dto.setDescription(e.getDescription());
              dto.setParentNodeId(e.getParentNode() != null ? e.getParentNode().getId() : null);
              dto.setOrderIndex(e.getOrderIndex());
              return dto;
            })
        .collect(Collectors.toList());
  }
}
