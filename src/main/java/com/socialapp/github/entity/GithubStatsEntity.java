package com.socialapp.github.entity;

import java.time.OffsetDateTime;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import com.fasterxml.jackson.databind.JsonNode;
import com.socialapp.common.crypto.EncryptedStringConverter;
import com.socialapp.security.entity.UserEntity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Entity
@Table(name = "t_user_github_stats")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GithubStatsEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "user_github_stats_id_generator")
  @SequenceGenerator(
      name = "user_github_stats_id_generator",
      sequenceName = "q_user_github_stats_id",
      allocationSize = 1)
  private Integer id;

  @OneToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "user_id", referencedColumnName = "id", nullable = false, unique = true)
  @ToString.Exclude
  @EqualsAndHashCode.Exclude
  private UserEntity user;

  @Column(name = "github_username", nullable = false)
  private String githubUsername;

  /**
   * Encrypted at rest — the scope is {@code read:user user:email}, so in plaintext this row reads
   * a user's private GitHub email addresses to anyone who can read the database.
   */
  @Convert(converter = EncryptedStringConverter.class)
  @Column(name = "access_token", length = 1024)
  private String accessToken;

  @Column(name = "public_repos_count")
  @Builder.Default
  private Integer publicReposCount = 0;

  @Column(name = "followers_count")
  @Builder.Default
  private Integer followersCount = 0;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "pinned_repos_json", columnDefinition = "jsonb")
  private JsonNode pinnedReposJson;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "contribution_graph_json", columnDefinition = "jsonb")
  private JsonNode contributionGraphJson;

  @Column(name = "last_synced_at")
  private OffsetDateTime lastSyncedAt;

  @CreationTimestamp
  @Column(name = "created_at", updatable = false)
  private OffsetDateTime createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at")
  private OffsetDateTime updatedAt;
}
