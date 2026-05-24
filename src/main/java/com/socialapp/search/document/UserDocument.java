package com.socialapp.search.document;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserDocument {
  private Integer id;
  private String fullName;
  private String username;
  private String profilePictureUrl;
}
