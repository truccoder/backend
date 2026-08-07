package com.socialapp.github.dto;

import java.time.OffsetDateTime;

import com.fasterxml.jackson.databind.JsonNode;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GithubStatsResponse {
  private String githubUsername;
  private Integer publicReposCount;
  private Integer followersCount;
  private JsonNode pinnedRepos;
  private JsonNode contributionGraph;
  private OffsetDateTime lastSyncedAt;
}
