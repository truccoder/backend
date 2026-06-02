package com.socialapp.knowledge.entity;

import java.time.OffsetDateTime;
import java.util.List;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "t_vault_notes")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VaultNoteEntity {
  @Id
  @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "vault_notes_seq_gen")
  @SequenceGenerator(
      name = "vault_notes_seq_gen",
      sequenceName = "q_vault_notes_id",
      allocationSize = 1)
  private Integer id;

  private Integer userId;

  private String filename;

  @Column(columnDefinition = "TEXT")
  private String content;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  private List<String> tags;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  private List<String> links;

  @CreationTimestamp private OffsetDateTime createdAt;

  @UpdateTimestamp private OffsetDateTime updatedAt;
}
