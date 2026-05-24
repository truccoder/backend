package com.socialapp.search.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Data;

@Data
@ConfigurationProperties(prefix = "opensearch")
public class OpenSearchProperties {
  private String host;
  private int port;
  private String scheme;
}
