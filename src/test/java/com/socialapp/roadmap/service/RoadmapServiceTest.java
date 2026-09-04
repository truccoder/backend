package com.socialapp.roadmap.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.socialapp.common.enums.LearningCategory;
import com.socialapp.common.exception.NotFoundException;
import com.socialapp.roadmap.dto.RoadmapDto;
import com.socialapp.roadmap.dto.RoadmapNodeDto;
import com.socialapp.roadmap.entity.RoadmapEntity;
import com.socialapp.roadmap.entity.RoadmapNodeEntity;
import com.socialapp.roadmap.repository.RoadmapNodeRepository;
import com.socialapp.roadmap.repository.RoadmapRepository;

/**
 * Component (unit) tests for {@link RoadmapService}, per ISTQB CTFL v4.0.1 (Section 2.2.1
 * component testing; Section 4.2.2 boundary value analysis on the default-orderIndex ternary;
 * Section 4.3.2 branch testing over the optional-parent-node partitions).
 */
@ExtendWith(MockitoExtension.class)
class RoadmapServiceTest {

  private static final Integer ROADMAP_ID = 1;
  private static final Integer NODE_ID = 10;
  private static final Integer PARENT_NODE_ID = 5;

  @Mock private RoadmapRepository roadmapRepository;
  @Mock private RoadmapNodeRepository roadmapNodeRepository;

  @InjectMocks private RoadmapService roadmapService;

  private static RoadmapEntity roadmap(Integer id) {
    return RoadmapEntity.builder()
        .id(id)
        .name("Backend Roadmap")
        .description("Learn Spring")
        .build();
  }

  private static RoadmapNodeEntity node(
      Integer id, RoadmapEntity roadmap, RoadmapNodeEntity parent) {
    return RoadmapNodeEntity.builder()
        .id(id)
        .roadmap(roadmap)
        .name("JPA")
        .description("Learn JPA")
        .parentNode(parent)
        .orderIndex(0)
        .build();
  }

  private static RoadmapNodeDto nodeDto(Integer parentNodeId, Integer orderIndex) {
    RoadmapNodeDto dto = new RoadmapNodeDto();
    dto.setName("JPA");
    dto.setDescription("Learn JPA");
    dto.setParentNodeId(parentNodeId);
    dto.setOrderIndex(orderIndex);
    return dto;
  }

  // =====================================================================
  // createRoadmap
  // =====================================================================

  @Nested
  @DisplayName("createRoadmap")
  class CreateRoadmapTests {

    @Test
    @DisplayName("should persist the roadmap and echo back its generated id")
    void shouldPersistRoadmapAndReturnGeneratedId() {
      // Given
      RoadmapDto dto = new RoadmapDto();
      dto.setName("Backend Roadmap");
      dto.setDescription("Learn Spring");
      when(roadmapRepository.save(any())).thenReturn(roadmap(ROADMAP_ID));

      // When
      RoadmapDto result = roadmapService.createRoadmap(dto);

      // Then
      assertThat(result.getId()).isEqualTo(ROADMAP_ID);
      assertThat(result.getName()).isEqualTo("Backend Roadmap");
    }

    @Test
    @DisplayName("should persist the chosen category")
    void shouldPersistChosenCategory() {
      // Given
      RoadmapDto dto = new RoadmapDto();
      dto.setName("Mobile Roadmap");
      dto.setCategory(LearningCategory.MOBILE);
      ArgumentCaptor<RoadmapEntity> captor = ArgumentCaptor.forClass(RoadmapEntity.class);
      when(roadmapRepository.save(captor.capture())).thenReturn(roadmap(ROADMAP_ID));

      // When
      roadmapService.createRoadmap(dto);

      // Then
      assertThat(captor.getValue().getCategory()).isEqualTo(LearningCategory.MOBILE);
    }

    @Test
    @DisplayName("should fall back to OTHER when no category is given, and say so in the reply")
    void shouldDefaultToOtherWhenCategoryOmitted() {
      // Given: cột là NOT NULL ở V76, nên bỏ trống phải thành OTHER chứ không phải null — và
      // phản hồi phải nói ra giá trị đã lưu, nếu không client vẫn tưởng lộ trình không có chủ đề.
      RoadmapDto dto = new RoadmapDto();
      dto.setName("Backend Roadmap");
      ArgumentCaptor<RoadmapEntity> captor = ArgumentCaptor.forClass(RoadmapEntity.class);
      when(roadmapRepository.save(captor.capture())).thenReturn(roadmap(ROADMAP_ID));

      // When
      RoadmapDto result = roadmapService.createRoadmap(dto);

      // Then
      assertThat(captor.getValue().getCategory()).isEqualTo(LearningCategory.OTHER);
      assertThat(result.getCategory()).isEqualTo(LearningCategory.OTHER);
    }
  }

  // =====================================================================
  // getAllRoadmaps
  // =====================================================================

  @Nested
  @DisplayName("getAllRoadmaps")
  class GetAllRoadmapsTests {

    @Test
    @DisplayName("should return an empty list when there are no roadmaps")
    void shouldReturnEmptyList_whenNoRoadmapsExist() {
      // Given
      when(roadmapRepository.findAll()).thenReturn(List.of());

      // When
      List<RoadmapDto> result = roadmapService.getAllRoadmaps();

      // Then
      assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("should map every roadmap entity to a DTO")
    void shouldMapAllRoadmaps() {
      // Given
      when(roadmapRepository.findAll()).thenReturn(List.of(roadmap(ROADMAP_ID)));

      // When
      List<RoadmapDto> result = roadmapService.getAllRoadmaps();

      // Then
      assertThat(result).hasSize(1);
      assertThat(result.get(0).getId()).isEqualTo(ROADMAP_ID);
      assertThat(result.get(0).getName()).isEqualTo("Backend Roadmap");
      assertThat(result.get(0).getDescription()).isEqualTo("Learn Spring");
    }

    @Test
    @DisplayName("should carry the category into the DTO the tabs are built from")
    void shouldMapCategory() {
      // Given: FE gom nhóm phía client, nên nhãn phải có mặt trong CHÍNH danh sách này — không
      // có endpoint nào khác để đi hỏi lại.
      RoadmapEntity entity = roadmap(ROADMAP_ID);
      entity.setCategory(LearningCategory.DEVOPS);
      when(roadmapRepository.findAll()).thenReturn(List.of(entity));

      // When
      List<RoadmapDto> result = roadmapService.getAllRoadmaps();

      // Then
      assertThat(result.get(0).getCategory()).isEqualTo(LearningCategory.DEVOPS);
    }
  }

  // =====================================================================
  // addNodeToRoadmap
  // =====================================================================

  @Nested
  @DisplayName("addNodeToRoadmap")
  class AddNodeToRoadmapTests {

    @Test
    @DisplayName("should reject when the roadmap does not exist")
    void shouldThrowNotFoundException_whenRoadmapDoesNotExist() {
      // Given
      when(roadmapRepository.findById(ROADMAP_ID)).thenReturn(Optional.empty());

      // When / Then
      assertThatThrownBy(() -> roadmapService.addNodeToRoadmap(ROADMAP_ID, nodeDto(null, null)))
          .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("should reject when the requested parent node does not exist")
    void shouldThrowNotFoundException_whenParentNodeDoesNotExist() {
      // Given
      when(roadmapRepository.findById(ROADMAP_ID)).thenReturn(Optional.of(roadmap(ROADMAP_ID)));
      when(roadmapNodeRepository.findById(PARENT_NODE_ID)).thenReturn(Optional.empty());

      // When / Then
      assertThatThrownBy(
              () -> roadmapService.addNodeToRoadmap(ROADMAP_ID, nodeDto(PARENT_NODE_ID, null)))
          .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("should create a root node with no parent when parentNodeId is not given")
    void shouldCreateRootNode_whenParentNodeIdIsNull() {
      // Given
      when(roadmapRepository.findById(ROADMAP_ID)).thenReturn(Optional.of(roadmap(ROADMAP_ID)));
      when(roadmapNodeRepository.save(any()))
          .thenAnswer(
              inv -> {
                RoadmapNodeEntity entity = inv.getArgument(0);
                entity.setId(NODE_ID);
                return entity;
              });

      // When
      RoadmapNodeDto result = roadmapService.addNodeToRoadmap(ROADMAP_ID, nodeDto(null, null));

      // Then
      assertThat(result.getId()).isEqualTo(NODE_ID);
      assertThat(result.getRoadmapId()).isEqualTo(ROADMAP_ID);
      assertThat(result.getParentNodeId()).isNull();
    }

    @Test
    @DisplayName("should link the new node to its parent when parentNodeId is given")
    void shouldLinkParentNode_whenParentNodeIdIsPresent() {
      // Given
      RoadmapEntity roadmap = roadmap(ROADMAP_ID);
      RoadmapNodeEntity parent = node(PARENT_NODE_ID, roadmap, null);
      when(roadmapRepository.findById(ROADMAP_ID)).thenReturn(Optional.of(roadmap));
      when(roadmapNodeRepository.findById(PARENT_NODE_ID)).thenReturn(Optional.of(parent));
      when(roadmapNodeRepository.save(any()))
          .thenAnswer(
              inv -> {
                RoadmapNodeEntity entity = inv.getArgument(0);
                entity.setId(NODE_ID);
                return entity;
              });

      // When
      RoadmapNodeDto result =
          roadmapService.addNodeToRoadmap(ROADMAP_ID, nodeDto(PARENT_NODE_ID, null));

      // Then
      assertThat(result.getParentNodeId()).isEqualTo(PARENT_NODE_ID);
    }

    @Test
    @DisplayName("should default the order index to zero when not provided")
    void shouldDefaultOrderIndexToZero_whenNotProvided() {
      // Given
      when(roadmapRepository.findById(ROADMAP_ID)).thenReturn(Optional.of(roadmap(ROADMAP_ID)));
      when(roadmapNodeRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

      // When
      roadmapService.addNodeToRoadmap(ROADMAP_ID, nodeDto(null, null));

      // Then
      ArgumentCaptor<RoadmapNodeEntity> captor = ArgumentCaptor.forClass(RoadmapNodeEntity.class);
      verify(roadmapNodeRepository).save(captor.capture());
      assertThat(captor.getValue().getOrderIndex()).isEqualTo(0);
    }

    @Test
    @DisplayName("should use the provided order index when given")
    void shouldUseProvidedOrderIndex_whenPresent() {
      // Given
      when(roadmapRepository.findById(ROADMAP_ID)).thenReturn(Optional.of(roadmap(ROADMAP_ID)));
      when(roadmapNodeRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

      // When
      roadmapService.addNodeToRoadmap(ROADMAP_ID, nodeDto(null, 3));

      // Then
      ArgumentCaptor<RoadmapNodeEntity> captor = ArgumentCaptor.forClass(RoadmapNodeEntity.class);
      verify(roadmapNodeRepository).save(captor.capture());
      assertThat(captor.getValue().getOrderIndex()).isEqualTo(3);
    }
  }

  // =====================================================================
  // getRoadmapNodes
  // =====================================================================

  @Nested
  @DisplayName("getRoadmapNodes")
  class GetRoadmapNodesTests {

    @Test
    @DisplayName("should return an empty list when the roadmap has no nodes")
    void shouldReturnEmptyList_whenNoNodesExist() {
      // Given
      when(roadmapNodeRepository.findByRoadmapId(ROADMAP_ID)).thenReturn(List.of());

      // When
      List<RoadmapNodeDto> result = roadmapService.getRoadmapNodes(ROADMAP_ID);

      // Then
      assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("should map a root node with a null parentNodeId")
    void shouldMapRootNode_withNullParentNodeId() {
      // Given
      RoadmapEntity roadmap = roadmap(ROADMAP_ID);
      when(roadmapNodeRepository.findByRoadmapId(ROADMAP_ID))
          .thenReturn(List.of(node(NODE_ID, roadmap, null)));

      // When
      List<RoadmapNodeDto> result = roadmapService.getRoadmapNodes(ROADMAP_ID);

      // Then
      assertThat(result.get(0).getId()).isEqualTo(NODE_ID);
      assertThat(result.get(0).getParentNodeId()).isNull();
    }

    @Test
    @DisplayName("should map a child node with its parent's id")
    void shouldMapChildNode_withParentNodeId() {
      // Given
      RoadmapEntity roadmap = roadmap(ROADMAP_ID);
      RoadmapNodeEntity parent = node(PARENT_NODE_ID, roadmap, null);
      RoadmapNodeEntity child = node(NODE_ID, roadmap, parent);
      when(roadmapNodeRepository.findByRoadmapId(ROADMAP_ID)).thenReturn(List.of(child));

      // When
      List<RoadmapNodeDto> result = roadmapService.getRoadmapNodes(ROADMAP_ID);

      // Then
      assertThat(result.get(0).getParentNodeId()).isEqualTo(PARENT_NODE_ID);
    }
  }
}
