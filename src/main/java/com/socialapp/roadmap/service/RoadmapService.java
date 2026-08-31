package com.socialapp.roadmap.service;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialapp.common.exception.NotFoundException;
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
        RoadmapEntity.builder()
            .name(dto.getName())
            .description(dto.getDescription())
            .category(dto.getCategory())
            .build();
    entity = roadmapRepository.save(entity);
    dto.setId(entity.getId());
    // Đọc ngược từ entity chứ không giữ nguyên dto: người gọi bỏ trống category thì thứ đã được
    // lưu là OTHER, và phản hồi phải nói đúng thứ đã lưu.
    dto.setCategory(entity.getCategory());
    return dto;
  }

  public List<RoadmapDto> getAllRoadmaps() {
    return roadmapRepository.findAll().stream()
        .map(RoadmapService::toDto)
        .collect(Collectors.toList());
  }

  /**
   * Roadmaps matching a free-text query — the server side of the search page's "Lộ trình" tab
   * (backend-plan B33), replacing a client-side filter over the whole catalogue.
   *
   * <p>{@code sanitizedQuery} is expected pre-escaped for {@code LIKE}; see {@code
   * ProjectQueryService.searchProjects} for why the sanitiser is not called here. Node names are
   * not searched — that would be one query per roadmap.
   */
  public List<RoadmapDto> searchRoadmaps(String sanitizedQuery, int limit) {
    return roadmapRepository.search(sanitizedQuery, limit).stream()
        .map(RoadmapService::toDto)
        .collect(Collectors.toList());
  }

  private static RoadmapDto toDto(RoadmapEntity e) {
    RoadmapDto dto = new RoadmapDto();
    dto.setId(e.getId());
    dto.setName(e.getName());
    dto.setDescription(e.getDescription());
    dto.setCategory(e.getCategory());
    return dto;
  }

  @Transactional
  public RoadmapNodeDto addNodeToRoadmap(Integer roadmapId, RoadmapNodeDto dto) {
    RoadmapEntity roadmap =
        roadmapRepository
            .findById(roadmapId)
            .orElseThrow(() -> new NotFoundException("Roadmap not found"));

    RoadmapNodeEntity parentNode = null;
    if (dto.getParentNodeId() != null) {
      parentNode =
          roadmapNodeRepository
              .findById(dto.getParentNodeId())
              .orElseThrow(() -> new NotFoundException("Parent node not found"));
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

  // Reads e.getRoadmap()/e.getParentNode(), both LAZY. It happens to work without a session
  // because only the identifier is dereferenced and Hibernate serves that off the proxy without
  // initializing it — too subtle a property to leave the endpoint resting on. Covered by
  // RoadmapServiceLazyLoadingIntegrationTest.
  @Transactional(readOnly = true)
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
