package com.socialapp.knowledge.dto;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VaultNoteDto {
  private String filename;
  private String content;
  private List<String> tags;
  private List<String> links;
}
