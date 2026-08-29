package com.socialapp.roadmap.entity;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import com.socialapp.common.enums.LearningCategory;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

@Entity
@Table(name = "t_roadmaps")
@Data
@NoArgsConstructor
public class RoadmapEntity {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Integer id;

  @Column(nullable = false)
  private String name;

  @Column(columnDefinition = "TEXT")
  private String description;

  /**
   * Chủ đề của lộ trình, để FE chia màn hình Lộ trình thành tab.
   *
   * <p>Không phân trang ở server ({@code GET /v1/api/roadmaps} trả về toàn bộ danh sách), nên
   * không có tham số lọc đi kèm: FE nhận cả danh sách rồi tự gom nhóm. Cột chỉ tồn tại ở đây vì
   * nhãn phải là dữ liệu — suy chủ đề từ {@code name} bằng chuỗi thì mỗi client đoán một kiểu.
   */
  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  private LearningCategory category = LearningCategory.OTHER;

  @OneToMany(mappedBy = "roadmap", cascade = CascadeType.ALL, orphanRemoval = true)
  @ToString.Exclude
  @EqualsAndHashCode.Exclude
  @Getter(AccessLevel.NONE)
  @Setter(AccessLevel.NONE)
  private List<RoadmapNodeEntity> nodes;

  @CreationTimestamp
  @Column(name = "created_at", updatable = false)
  private OffsetDateTime createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at")
  private OffsetDateTime updatedAt;

  @Builder
  public RoadmapEntity(
      Integer id,
      String name,
      String description,
      LearningCategory category,
      List<RoadmapNodeEntity> nodes,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {
    this.id = id;
    this.name = name;
    this.description = description;
    // Không dùng @Builder.Default được — @Builder ở đây đặt trên constructor, và Lombok cấm hai
    // thứ đó đi cùng nhau (xem ghi chú tương tự ở ExplanationEntity.version). Null-coalesce giữ
    // đúng hành vi "không khai báo -> OTHER", vốn là thứ cột NOT NULL ở V76 cần.
    this.category = category == null ? LearningCategory.OTHER : category;
    this.nodes = nodes == null ? null : new ArrayList<>(nodes);
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }

  public List<RoadmapNodeEntity> getNodes() {
    return nodes == null ? List.of() : List.copyOf(nodes);
  }

  public void setNodes(List<RoadmapNodeEntity> nodes) {
    this.nodes = nodes == null ? null : new ArrayList<>(nodes);
  }
}
