package com.socialapp.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import com.socialapp.AbstractIntegrationTest;
import com.socialapp.matchmaking.dto.ProjectResponseDto;
import com.socialapp.matchmaking.entity.ProjectEntity;
import com.socialapp.matchmaking.entity.ProjectPositionEntity;
import com.socialapp.matchmaking.repository.ProjectPositionRepository;
import com.socialapp.matchmaking.repository.ProjectRepository;
import com.socialapp.matchmaking.service.ProjectQueryService;
import com.socialapp.roadmap.dto.RoadmapDto;
import com.socialapp.roadmap.entity.RoadmapEntity;
import com.socialapp.roadmap.entity.RoadmapNodeEntity;
import com.socialapp.roadmap.repository.RoadmapNodeRepository;
import com.socialapp.roadmap.repository.RoadmapRepository;
import com.socialapp.roadmap.service.RoadmapService;
import com.socialapp.search.util.SearchQuerySanitizer;
import com.socialapp.security.entity.UserEntity;
import com.socialapp.security.repository.UserRepository;

/**
 * Integration tests for the backend-plan B33/B36 branches — {@code
 * ProjectQueryService.searchProjects} and {@code RoadmapService.searchRoadmaps} — against a real
 * PostgreSQL instance (Testcontainers).
 *
 * <p>These exercise what the {@code @WebMvcTest} slice cannot: the native SQL, the {@code
 * f_unaccent} trigram path, {@code jsonb_array_elements_text} over the nullable {@code tags} /
 * {@code required_skills} columns, the node-level {@code EXISTS} added for B36, and that {@code
 * ESCAPE '\'} treats a sanitised {@code %} as a literal.
 */
@Transactional
class ProjectRoadmapSearchIntegrationTest extends AbstractIntegrationTest {

  @Autowired private ProjectQueryService projectQueryService;
  @Autowired private RoadmapService roadmapService;
  @Autowired private ProjectRepository projectRepository;
  @Autowired private ProjectPositionRepository positionRepository;
  @Autowired private RoadmapRepository roadmapRepository;
  @Autowired private RoadmapNodeRepository roadmapNodeRepository;
  @Autowired private UserRepository userRepository;

  private Integer titleMatchProjectId;
  private Integer skillMatchProjectId;
  private Integer nodeMatchRoadmapId;

  @BeforeEach
  void seed() {
    UserEntity owner = new UserEntity();
    owner.setEmail("b33-" + System.nanoTime() + "@example.com");
    owner.setPassword("{noop}x");
    owner.setUsername(owner.getEmail());
    owner.setFullName("B33 Owner");
    owner.setEmailVerified(true);
    owner = userRepository.save(owner);

    ProjectEntity byTitle = new ProjectEntity();
    byTitle.setAuthor(owner);
    byTitle.setTitle("Nền tảng Java tuyển dụng");
    byTitle.setDescription("Backend recruiting board");
    byTitle.setTags(List.of("hiring", "backend"));
    titleMatchProjectId = projectRepository.save(byTitle).getId();

    ProjectEntity bySkill = new ProjectEntity();
    bySkill.setAuthor(owner);
    bySkill.setTitle("Realtime chat");
    bySkill.setDescription("A websocket playground");
    // no tags — exercises coalesce(tags, '[]')
    bySkill = projectRepository.save(bySkill);
    skillMatchProjectId = bySkill.getId();

    ProjectPositionEntity position = new ProjectPositionEntity();
    position.setProject(bySkill);
    position.setTitle("Backend engineer");
    position.setRequiredSkills(List.of("Java", "Spring Boot"));
    positionRepository.save(position);

    RoadmapEntity roadmap = new RoadmapEntity();
    roadmap.setName("Java Backend Track");
    roadmap.setDescription("From servlets to virtual threads");
    roadmapRepository.save(roadmap);

    // B36 — a track whose name/description say nothing about the topic; only a node inside it
    // does. Without the EXISTS branch this track is unfindable by the skill it actually teaches.
    RoadmapEntity byNode = new RoadmapEntity();
    byNode.setName("Distributed Systems Track");
    byNode.setDescription("Scaling a backend beyond one process");
    byNode = roadmapRepository.save(byNode);
    nodeMatchRoadmapId = byNode.getId();

    RoadmapNodeEntity node = new RoadmapNodeEntity();
    node.setRoadmap(byNode);
    node.setName("Message queues with Kafka");
    roadmapNodeRepository.save(node);
  }

  @Test
  @DisplayName("searchProjects matches title and, separately, a position's required_skills")
  void searchProjectsMatchesTitleAndSkills() {
    List<ProjectResponseDto> hits =
        projectQueryService.searchProjects(SearchQuerySanitizer.sanitize("java"), 20);

    assertThat(hits)
        .extracting(ProjectResponseDto::getId)
        .contains(titleMatchProjectId, skillMatchProjectId);
    // the skill-matched project still carries its position skills back for the "why it matched" UI
    assertThat(hits)
        .filteredOn(p -> p.getId().equals(skillMatchProjectId))
        .singleElement()
        .satisfies(p -> assertThat(p.getPositions().get(0).getRequiredSkills()).contains("Java"));
  }

  @Test
  @DisplayName("searchProjects is diacritics-insensitive and treats a sanitised % as a literal")
  void searchProjectsFoldsDiacriticsAndEscapesWildcards() {
    assertThat(projectQueryService.searchProjects(SearchQuerySanitizer.sanitize("nen tang"), 20))
        .extracting(ProjectResponseDto::getId)
        .contains(titleMatchProjectId);

    assertThat(projectQueryService.searchProjects(SearchQuerySanitizer.sanitize("ja%va"), 20))
        .isEmpty();
  }

  @Test
  @DisplayName("searchRoadmaps matches name and description")
  void searchRoadmapsMatches() {
    assertThat(roadmapService.searchRoadmaps(SearchQuerySanitizer.sanitize("java"), 20))
        .extracting(RoadmapDto::getName)
        .contains("Java Backend Track");
    assertThat(roadmapService.searchRoadmaps(SearchQuerySanitizer.sanitize("virtual threads"), 20))
        .extracting(RoadmapDto::getName)
        .contains("Java Backend Track");
  }

  @Test
  @DisplayName("searchRoadmaps also matches a node's name inside the track (B36)")
  void searchRoadmapsMatchesNodeName() {
    // "kafka" appears in neither the roadmap's name nor its description — only on one of its
    // nodes. Before B36 this returned nothing.
    assertThat(roadmapService.searchRoadmaps(SearchQuerySanitizer.sanitize("kafka"), 20))
        .extracting(RoadmapDto::getId)
        .contains(nodeMatchRoadmapId);
  }
}
