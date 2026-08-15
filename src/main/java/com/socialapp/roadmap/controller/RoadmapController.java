package com.socialapp.roadmap.controller;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import com.socialapp.roadmap.dto.RoadmapDto;
import com.socialapp.roadmap.dto.RoadmapNodeDto;
import com.socialapp.roadmap.service.RoadmapService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * The roadmap catalogue: readable by any signed-in user, authored by admins only.
 *
 * <p>The two {@code @PreAuthorize} guards below enforced nothing until {@code @EnableMethodSecurity}
 * was added to {@code SecurityConfig} — any signed-in user could create roadmaps and nodes. They are
 * duplicated as path rules there; see the note on that class for why the annotation is not trusted
 * as the only control.
 */
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
