package com.socialapp.knowledge.entity;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "t_vault_notes")
@Data
@NoArgsConstructor
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
  @Getter(AccessLevel.NONE)
  @Setter(AccessLevel.NONE)
  private List<String> tags;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  @Getter(AccessLevel.NONE)
  @Setter(AccessLevel.NONE)
  private List<String> links;

  @CreationTimestamp private OffsetDateTime createdAt;

  @UpdateTimestamp private OffsetDateTime updatedAt;

  @Builder
  public VaultNoteEntity(
      Integer id,
      Integer userId,
      String filename,
      String content,
      List<String> tags,
      List<String> links,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {
    this.id = id;
    this.userId = userId;
    this.filename = filename;
    this.content = content;
    this.tags = tags == null ? null : new ArrayList<>(tags);
    this.links = links == null ? null : new ArrayList<>(links);
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }

  public List<String> getTags() {
    return tags == null ? List.of() : List.copyOf(tags);
  }

  public void setTags(List<String> tags) {
    this.tags = tags == null ? null : new ArrayList<>(tags);
  }

  public List<String> getLinks() {
    return links == null ? List.of() : List.copyOf(links);
  }

  public void setLinks(List<String> links) {
    this.links = links == null ? null : new ArrayList<>(links);
  }
}
