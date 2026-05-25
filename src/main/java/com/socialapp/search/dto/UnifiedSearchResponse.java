package com.socialapp.search.dto;

import java.util.List;

import com.socialapp.search.document.PostDocument;
import com.socialapp.search.document.UserDocument;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UnifiedSearchResponse {
  private List<UserDocument> users;
  private List<PostDocument> posts;
  private long totalUsers;
  private long totalPosts;
}
