package com.socialapp.search.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserDto {
  private Integer id;
  private String fullName;
  private String username;
  private String profilePictureUrl;
  private Integer eliteScore;
}
