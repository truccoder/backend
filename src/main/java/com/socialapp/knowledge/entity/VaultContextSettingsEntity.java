package com.socialapp.knowledge.entity;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Which parts of a user's vault may be used as AI context.
 *
 * <p>Keyed by {@code userId} with no surrogate id: there is exactly one row per user or none at
 * all, and "none" is a meaningful state — it means nothing has been configured, which reads as no
 * filtering.
 *
 * <p>The jsonb lists are copied in and out for the same reason {@code VaultNoteEntity} does it: a
 * caller holding the collection Hibernate is managing can mutate persistent state without ever
 * calling a setter.
 */
@Entity
@Table(name = "t_vault_context_settings")
@Data
@NoArgsConstructor
public class VaultContextSettingsEntity {

  @Id
  @Column(name = "user_id")
  private Integer userId;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  @Getter(AccessLevel.NONE)
  @Setter(AccessLevel.NONE)
  private List<String> includeTags;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  @Getter(AccessLevel.NONE)
  @Setter(AccessLevel.NONE)
  private List<String> excludeTags;

  @CreationTimestamp private OffsetDateTime createdAt;

  @UpdateTimestamp private OffsetDateTime updatedAt;

  @Builder
  public VaultContextSettingsEntity(
      Integer userId,
      List<String> includeTags,
      List<String> excludeTags,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {
    this.userId = userId;
    this.includeTags = includeTags == null ? null : new ArrayList<>(includeTags);
    this.excludeTags = excludeTags == null ? null : new ArrayList<>(excludeTags);
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }

  public List<String> getIncludeTags() {
    return includeTags == null ? List.of() : List.copyOf(includeTags);
  }

  public void setIncludeTags(List<String> includeTags) {
    this.includeTags = includeTags == null ? null : new ArrayList<>(includeTags);
  }

  public List<String> getExcludeTags() {
    return excludeTags == null ? List.of() : List.copyOf(excludeTags);
  }

  public void setExcludeTags(List<String> excludeTags) {
    this.excludeTags = excludeTags == null ? null : new ArrayList<>(excludeTags);
  }
}
