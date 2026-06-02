package com.socialapp.knowledge.entity;

import java.time.OffsetDateTime;
import java.util.List;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import com.socialapp.knowledge.entity.enums.SeniorityLevel;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "t_user_professional_profiles")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserProfessionalProfileEntity {
  @Id private Integer userId;

  private String jobTitle;

  @Enumerated(EnumType.STRING)
  private SeniorityLevel seniorityLevel;

  private Integer yearsOfExperience;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  private List<String> knownTechStack;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  private List<WorkExperience> workHistory;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  private List<String> interestedDomains;

  @CreationTimestamp private OffsetDateTime createdAt;

  @UpdateTimestamp private OffsetDateTime updatedAt;
}
