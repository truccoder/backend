package com.socialapp.roadmap.controller;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import com.socialapp.roadmap.dto.RoadmapDto;
import com.socialapp.roadmap.dto.RoadmapNodeDto;
import com.socialapp.roadmap.service.RoadmapService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/v1/api/roadmaps")
@RequiredArgsConstructor
public class RoadmapController {

  private final RoadmapService roadmapService;

  @PostMapping
  @PreAuthorize("hasRole('ADMIN')")
  public RoadmapDto createRoadmap(@Valid @RequestBody RoadmapDto request) {
    return roadmapService.createRoadmap(request);
  }

  @GetMapping
  public List<RoadmapDto> getAllRoadmaps() {
    return roadmapService.getAllRoadmaps();
  }

  @PostMapping("/{id}/nodes")
  @PreAuthorize("hasRole('ADMIN')")
  public RoadmapNodeDto addNode(
      @PathVariable Integer id, @Valid @RequestBody RoadmapNodeDto request) {
    return roadmapService.addNodeToRoadmap(id, request);
  }

  @GetMapping("/{id}/nodes")
  public List<RoadmapNodeDto> getNodes(@PathVariable Integer id) {
    return roadmapService.getRoadmapNodes(id);
  }
}
