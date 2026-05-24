package com.socialapp.search.dto;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SearchResult<T> {
  private List<T> items;
  private long totalHits;
  private int page;
  private int size;
}
