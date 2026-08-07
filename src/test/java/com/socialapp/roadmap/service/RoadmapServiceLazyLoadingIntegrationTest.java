package com.socialapp.roadmap.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.socialapp.AbstractIntegrationTest;
import com.socialapp.roadmap.dto.RoadmapNodeDto;
import com.socialapp.roadmap.entity.RoadmapEntity;
import com.socialapp.roadmap.entity.RoadmapNodeEntity;
import com.socialapp.roadmap.repository.RoadmapNodeRepository;
import com.socialapp.roadmap.repository.RoadmapRepository;

/**
 * Integration tests for {@link RoadmapService} against a real PostgreSQL instance
 * (Testcontainers), per ISTQB CTFL v4.0.1 Section 2.2.1.
 *
 * <p><b>Why this class is deliberately NOT {@code @Transactional}:</b> {@code
 * spring.jpa.open-in-view} is {@code false}, so a repository read that returns entities with
 * uninitialized LAZY proxies leaves the caller holding detached objects once the repository's own
 * transaction commits. {@code getRoadmapNodes} carries no {@code @Transactional} of its own yet
 * dereferences two LAZY {@code @ManyToOne}s ({@code roadmap}, {@code parentNode}) while mapping to
 * DTOs. A class-level {@code @Transactional} — as the repository tests in this project use — would
 * hold one session open across the whole test and hide exactly the failure being checked for, so
 * seeding is committed through an explicit {@link TransactionTemplate} instead and the service is
 * then called with no transaction in scope, the same way an HTTP request does.
 */
class RoadmapServiceLazyLoadingIntegrationTest extends AbstractIntegrationTest {

  @Autowired private RoadmapService roadmapService;
  @Autowired private RoadmapRepository roadmapRepository;
  @Autowired private RoadmapNodeRepository roadmapNodeRepository;
  @Autowired private PlatformTransactionManager transactionManager;

  private TransactionTemplate tx;
  private Integer roadmapId;
  private Integer parentNodeId;

  @BeforeEach
  void seedCommittedRoadmap() {
    tx = new TransactionTemplate(transactionManager);

    tx.executeWithoutResult(
        status -> {
          RoadmapEntity roadmap =
              roadmapRepository.save(
                  RoadmapEntity.builder().name("Backend").description("Backend track").build());
          roadmapId = roadmap.getId();

          RoadmapNodeEntity parent =
              roadmapNodeRepository.save(
                  RoadmapNodeEntity.builder().roadmap(roadmap).name("Java").orderIndex(0).build());
          parentNodeId = parent.getId();

          roadmapNodeRepository.save(
              RoadmapNodeEntity.builder()
                  .roadmap(roadmap)
                  .parentNode(parent)
                  .name("Spring Boot")
                  .orderIndex(1)
                  .build());
        });
  }

  @AfterEach
  void removeSeededRoadmap() {
    tx.executeWithoutResult(
        status -> {
          roadmapNodeRepository.deleteAll(roadmapNodeRepository.findByRoadmapId(roadmapId));
          roadmapRepository.deleteById(roadmapId);
        });
  }

  @Test
  @DisplayName("getRoadmapNodes should resolve both LAZY associations with no session in scope")
  void shouldResolveLazyAssociations_whenCalledOutsideATransaction() {
    // Given a roadmap committed in a previous transaction (see seedCommittedRoadmap)

    // When — no transaction and, with open-in-view off, no session either
    List<RoadmapNodeDto> nodes = roadmapService.getRoadmapNodes(roadmapId);

    // Then — every node carries its roadmap id, and the child carries its parent id
    assertThat(nodes).hasSize(2);
    assertThat(nodes).allSatisfy(node -> assertThat(node.getRoadmapId()).isEqualTo(roadmapId));
    assertThat(nodes)
        .filteredOn(node -> "Spring Boot".equals(node.getName()))
        .singleElement()
        .satisfies(child -> assertThat(child.getParentNodeId()).isEqualTo(parentNodeId));
    assertThat(nodes)
        .filteredOn(node -> "Java".equals(node.getName()))
        .singleElement()
        .satisfies(parent -> assertThat(parent.getParentNodeId()).isNull());
  }

  @Test
  @DisplayName("getAllRoadmaps should not touch the LAZY nodes collection")
  void shouldListRoadmaps_whenCalledOutsideATransaction() {
    // When
    var roadmaps = roadmapService.getAllRoadmaps();

    // Then
    assertThat(roadmaps).extracting("id").contains(roadmapId);
  }
}
