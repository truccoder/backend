package com.socialapp.knowledge.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class WorkExperience {
  private String company;
  private String domain;
  private String role;
  private Integer durationMonths;
}
