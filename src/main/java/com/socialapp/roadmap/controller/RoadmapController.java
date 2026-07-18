package com.socialapp.roadmap.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
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
  public ResponseEntity<RoadmapDto> createRoadmap(@Valid @RequestBody RoadmapDto request) {
    return ResponseEntity.ok(roadmapService.createRoadmap(request));
  }

  @GetMapping
  public ResponseEntity<List<RoadmapDto>> getAllRoadmaps() {
    return ResponseEntity.ok(roadmapService.getAllRoadmaps());
  }

  @PostMapping("/{id}/nodes")
  @PreAuthorize("hasRole('ADMIN')")
  public ResponseEntity<RoadmapNodeDto> addNode(
      @PathVariable Integer id, @Valid @RequestBody RoadmapNodeDto request) {
    return ResponseEntity.ok(roadmapService.addNodeToRoadmap(id, request));
  }

  @GetMapping("/{id}/nodes")
  public ResponseEntity<List<RoadmapNodeDto>> getNodes(@PathVariable Integer id) {
    return ResponseEntity.ok(roadmapService.getRoadmapNodes(id));
  }
}
