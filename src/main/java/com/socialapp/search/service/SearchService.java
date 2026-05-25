package com.socialapp.search.service;

import static com.socialapp.search.config.OpenSearchConfig.POSTS_INDEX;
import static com.socialapp.search.config.OpenSearchConfig.USERS_INDEX;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.http.util.EntityUtils;
import org.opensearch.client.Request;
import org.opensearch.client.Response;
import org.opensearch.client.RestClient;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.socialapp.search.document.PostDocument;
import com.socialapp.search.document.UserDocument;
import com.socialapp.search.dto.SearchResult;
import com.socialapp.search.dto.UnifiedSearchResponse;
import com.socialapp.search.exception.OpenSearchException;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class SearchService {
  private final OpenSearchClient client;
  private final RestClient restClient;
  private final ObjectMapper objectMapper;

  /** 0–2 chars → exact match, 3–5 → 1 typo allowed, 6+ → 2 typos allowed */
  private static final String FUZZINESS = "AUTO";

  private static final int BOOST_FULL_NAME = 3;
  private static final int BOOST_USERNAME = 2;
  private static final int BOOST_FRIEND = 10;
  private static final int BOOST_OWN_POST = 20;

  public void indexUser(UserDocument doc) {
    index(USERS_INDEX, String.valueOf(doc.getId()), doc);
  }

  public void deleteUser(Integer userId) {
    delete(USERS_INDEX, String.valueOf(userId));
  }

  public void indexPost(PostDocument doc) {
    index(POSTS_INDEX, String.valueOf(doc.getId()), doc);
  }

  public void deletePost(Integer postId) {
    delete(POSTS_INDEX, String.valueOf(postId));
  }

  public UnifiedSearchResponse searchAll(
      String query, int size, Integer currentUserId, List<Integer> friendIds) {
    var users = searchUsers(query, 0, size, friendIds);
    var posts = searchPosts(query, 0, size, currentUserId, friendIds);
    return UnifiedSearchResponse.builder()
        .users(users.getItems())
        .posts(posts.getItems())
        .totalUsers(users.getTotalHits())
        .totalPosts(posts.getTotalHits())
        .build();
  }

  public SearchResult<UserDocument> searchUsers(
      String query, int page, int size, List<Integer> friendIds) {
    String body = buildUserSearchBody(query, page, size, friendIds);
    return executeSearch(USERS_INDEX, body, page, size, UserDocument.class);
  }

  public SearchResult<PostDocument> searchPosts(
      String query, int page, int size, Integer currentUserId, List<Integer> friendIds) {
    String body = buildPostSearchBody(query, page, size, currentUserId, friendIds);
    return executeSearch(POSTS_INDEX, body, page, size, PostDocument.class);
  }

  // spotless:off
  private String buildUserSearchBody(
      String queryText, int page, int size, List<Integer> friendIds) {
    String friendsBoost = friendIds.isEmpty() ? "" :
        """
        , "should": [{ "terms": { "id": %s, "boost": %d } }]
        """.formatted(toJson(friendIds), BOOST_FRIEND);

    return """
        {
          "query": {
            "bool": {
              "must": [{
                "multi_match": {
                  "query": %s,
                  "fields": ["fullName^%d", "username^%d"],
                  "fuzziness": "%s"
                }
              }]%s
            }
          },
          "from": %d,
          "size": %d
        }
        """.formatted(
            toJson(queryText), BOOST_FULL_NAME, BOOST_USERNAME, FUZZINESS,
            friendsBoost,
            page * size, size
        );
  }

  private String buildPostSearchBody(
      String queryText, int page, int size, Integer currentUserId, List<Integer> friendIds) {
    String friendScoreBoost = friendIds.isEmpty() ? "" :
        """
        , { "terms": { "authorId": %s, "boost": %d } }
        """.formatted(toJson(friendIds), BOOST_FRIEND);

    String friendVisibility = friendIds.isEmpty() ? "" :
        """
        , { "bool": { "must": [
              { "terms": { "authorId": %s } },
              { "terms": { "visibility": ["PUBLIC", "FRIENDS"] } }
            ] } }
        """.formatted(toJson(friendIds));

    return """
        {
          "query": {
            "bool": {
              "must": [{
                "match": {
                  "content": { "query": %s, "fuzziness": "%s" }
                }
              }],
              "should": [
                { "term": { "authorId": { "value": %d, "boost": %d } } }%s
              ],
              "filter": [{
                "bool": {
                  "minimum_should_match": 1,
                  "should": [
                    { "term": { "authorId": %d } }%s,
                    { "term": { "visibility": "PUBLIC" } }
                  ]
                }
              }]
            }
          },
          "from": %d,
          "size": %d
        }
        """.formatted(
            toJson(queryText), FUZZINESS,
            currentUserId, BOOST_OWN_POST, friendScoreBoost,
            currentUserId, friendVisibility,
            page * size, size
        );
  }
  // spotless:on

  private String toJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (IOException e) {
      throw new OpenSearchException("Failed to serialize value to JSON", e);
    }
  }

  private <T> void index(String indexName, String id, T document) {
    try {
      client.index(i -> i.index(indexName).id(id).document(document));
    } catch (IOException e) {
      throw new OpenSearchException(
          "Failed to index document [" + id + "] in [" + indexName + "]", e);
    }
  }

  private void delete(String indexName, String id) {
    try {
      client.delete(d -> d.index(indexName).id(id));
    } catch (IOException e) {
      throw new OpenSearchException(
          "Failed to delete document [" + id + "] from [" + indexName + "]", e);
    }
  }

  private <T> SearchResult<T> executeSearch(
      String indexName, String body, int page, int size, Class<T> clazz) {
    try {
      Request request = new Request(HttpMethod.POST.name(), "/" + indexName + "/_search");
      request.setJsonEntity(body);
      Response response = restClient.performRequest(request);
      String responseBody = EntityUtils.toString(response.getEntity());
      return parseSearchResponse(responseBody, page, size, clazz);
    } catch (IOException e) {
      throw new OpenSearchException("Failed to search index [" + indexName + "]", e);
    }
  }

  private <T> SearchResult<T> parseSearchResponse(
      String responseBody, int page, int size, Class<T> clazz) throws IOException {
    JsonNode root = objectMapper.readTree(responseBody);
    JsonNode hitsNode = root.path("hits");
    long totalHits = hitsNode.path("total").path("value").asLong(0);

    List<T> items = new ArrayList<>();
    for (JsonNode hit : hitsNode.path("hits")) {
      items.add(objectMapper.treeToValue(hit.path("_source"), clazz));
    }

    return SearchResult.<T>builder()
        .items(items)
        .totalHits(totalHits)
        .page(page)
        .size(size)
        .build();
  }
}
