package com.socialapp.matchmaking.entity;

import java.time.OffsetDateTime;
import java.util.List;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import com.socialapp.knowledge.entity.enums.SeniorityLevel;
import com.socialapp.matchmaking.entity.enums.PositionStatus;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Entity
@Table(name = "t_project_positions")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProjectPositionEntity {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Integer id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "project_id", nullable = false)
  @ToString.Exclude
  @EqualsAndHashCode.Exclude
  private ProjectEntity project;

  private String title;

  private String description;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  private List<String> requiredSkills;

  /**
   * The job description of this one role — what {@code description} was being asked to carry as a
   * single blob of prose, split into the three sections a reader actually looks for.
   *
   * <p>{@code roleSummary} is the paragraph at the top; {@code responsibilities} is what the
   * person will do; {@code requirements} is what they must already have. {@code niceToHave} is
   * explicitly the section that does <em>not</em> gate anything — see {@code MatchmakingService},
   * which reads {@code requiredSkills}, {@link #minYearsExperience} and {@link #seniorityLevel}
   * and never this one.
   *
   * <p>Nullable in the schema because 135 seeded positions and every position created before
   * {@code V105} predate them; {@code ProjectPositionRequestDTO} is where they became mandatory,
   * so everything written from now on has them.
   */
  @Column(columnDefinition = "TEXT")
  private String roleSummary;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  private List<String> responsibilities;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  private List<String> requirements;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  private List<String> niceToHave;

  /**
   * The experience bar, and the reason this is structured data rather than a line of
   * {@code requirements} prose: {@code MatchmakingService} treats both as <b>hard filters</b>.
   * A role that asks for five years stops being suggested to someone with one, and stops
   * suggesting them back — "close but not qualified" is not a weak match, it is a wrong one.
   *
   * <p>Null means the role does not care, which is not the same as asking for zero: a null bar
   * lets everyone through, a bar of 0 also lets everyone through but says so on the JD.
   */
  private Integer minYearsExperience;

  @Enumerated(EnumType.STRING)
  private SeniorityLevel seniorityLevel;

  /**
   * The rendered JD, as a PDF in the {@code job-descriptions} bucket, plus when it was rendered.
   *
   * <p>Cached rather than built per request: the bytes only change when the JD does. {@code
   * jdRenderedAt} is compared against {@code updatedAt} on <em>both</em> this position and its
   * project (the company sections live up there), so an edit on either side invalidates the copy.
   * See {@code JobDescriptionService}.
   */
  private String jdObjectKey;

  private OffsetDateTime jdRenderedAt;

  private Integer quantity = 1;

  @Enumerated(EnumType.STRING)
  private PositionStatus status = PositionStatus.OPEN;

  @Version @EqualsAndHashCode.Exclude private Long version;

  @CreationTimestamp private OffsetDateTime createdAt;

  @UpdateTimestamp private OffsetDateTime updatedAt;
}
