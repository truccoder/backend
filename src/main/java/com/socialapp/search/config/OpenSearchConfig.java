package com.socialapp.search.config;

import java.io.IOException;

import org.apache.http.HttpHost;
import org.opensearch.client.Request;
import org.opensearch.client.Response;
import org.opensearch.client.RestClient;
import org.opensearch.client.json.jackson.JacksonJsonpMapper;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.transport.rest_client.RestClientTransport;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Configuration
@EnableConfigurationProperties(OpenSearchProperties.class)
public class OpenSearchConfig {
  public static final String USERS_INDEX = "users";
  public static final String POSTS_INDEX = "posts";

  @Bean
  public RestClient restClient(OpenSearchProperties props) {
    return RestClient.builder(new HttpHost(props.getHost(), props.getPort(), props.getScheme()))
        .build();
  }

  @Bean
  public OpenSearchClient openSearchClient(RestClient restClient) {
    var transport = new RestClientTransport(restClient, new JacksonJsonpMapper());
    return new OpenSearchClient(transport);
  }

  @Bean
  public CommandLineRunner initializeIndices(RestClient restClient) {
    return args -> {
      createIndexIfNotExists(restClient, USERS_INDEX, USERS_INDEX_BODY);
      createIndexIfNotExists(restClient, POSTS_INDEX, POSTS_INDEX_BODY);
    };
  }

  private void createIndexIfNotExists(RestClient restClient, String index, String body) {
    try {
      Response head = restClient.performRequest(new Request(HttpMethod.HEAD.name(), "/" + index));
      if (head.getStatusLine().getStatusCode() == HttpStatus.OK.value()) {
        log.info("OpenSearch index already exists: {}", index);
        return;
      }
    } catch (IOException ignored) {
    }

    try {
      Request request = new Request(HttpMethod.PUT.name(), "/" + index);
      request.setJsonEntity(body);
      restClient.performRequest(request);
      log.info("Created OpenSearch index: {}", index);
    } catch (IOException e) {
      log.error("Failed to create OpenSearch index: {}", index, e);
    }
  }

  // spotless:off
  private static final String USERS_INDEX_BODY = """
      {
        "settings": { "number_of_shards": 1, "number_of_replicas": 0 },
        "mappings": {
          "properties": {
            "id":        { "type": "integer" },
            "fullName":  { "type": "text", "analyzer": "standard" },
            "username":  { "type": "text", "analyzer": "standard" },
            "profilePictureUrl": { "type": "keyword", "index": false }
          }
        }
      }
      """;

  private static final String POSTS_INDEX_BODY = """
      {
        "settings": { "number_of_shards": 1, "number_of_replicas": 0 },
        "mappings": {
          "properties": {
            "id":              { "type": "integer" },
            "content":         { "type": "text", "analyzer": "standard" },
            "authorId":        { "type": "integer" },
            "authorFullName":  { "type": "text" },
            "authorProfilePictureUrl": { "type": "keyword", "index": false },
            "visibility":      { "type": "keyword" },
            "createdAt":       { "type": "date" }
          }
        }
      }
      """;
  // spotless:on
}
