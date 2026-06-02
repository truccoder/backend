package com.socialapp.trending.service;

import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.socialapp.knowledge.client.GeminiClient;
import com.socialapp.trending.crawler.CrawledItem;
import com.socialapp.trending.entity.enums.TrendingCategory;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class TrendingClassificationService {
  private final GeminiClient geminiClient;
  private final ObjectMapper objectMapper;

  private static final int BATCH_SIZE = 10;

  public TrendingCategory classify(CrawledItem item) {
    try {
      String prompt = buildSingleClassificationPrompt(item);
      String response = geminiClient.generateContent(prompt);
      return parseCategory(response);
    } catch (Exception e) {
      log.warn(
          "Failed to classify item '{}', defaulting to OTHER: {}", item.getTitle(), e.getMessage());
      return TrendingCategory.OTHER;
    }
  }

  public List<TrendingCategory> classifyBatch(List<CrawledItem> items) {
    if (items.size() <= BATCH_SIZE) {
      return classifyBatchInternal(items);
    }

    List<TrendingCategory> results = new java.util.ArrayList<>();
    for (int i = 0; i < items.size(); i += BATCH_SIZE) {
      List<CrawledItem> batch = items.subList(i, Math.min(i + BATCH_SIZE, items.size()));
      results.addAll(classifyBatchInternal(batch));
    }
    return results;
  }

  private List<TrendingCategory> classifyBatchInternal(List<CrawledItem> items) {
    try {
      String prompt = buildBatchClassificationPrompt(items);
      String response = geminiClient.generateContent(prompt);
      return parseBatchCategories(response, items.size());
    } catch (Exception e) {
      log.warn("Batch classification failed, defaulting all to OTHER: {}", e.getMessage());
      return items.stream().map(i -> TrendingCategory.OTHER).toList();
    }
  }

  private String buildSingleClassificationPrompt(CrawledItem item) {
    return String.format(
        """
        Classify this tech article into exactly ONE category.

        Categories:
        - OPENSOURCE: Open source projects, libraries, frameworks, repos
        - EVENT: Tech events, conferences, meetups, launches (Google IO, WWDC, etc.)
        - NEW_TECH: New technologies, products, innovations, breakthroughs
        - REGULATION: Tech laws, policies, regulations, government actions on tech
        - MINDSET: Engineering philosophy, career advice, leadership insights, soft skills
        - TOOL: Developer tools, productivity, workflows, IDE, CLI tools
        - CAREER: Job market, hiring, interviews, salary, company culture
        - OTHER: Doesn't fit above categories

        Article:
        Title: %s
        Summary: %s
        Source: %s

        Respond with ONLY a JSON object: {"category": "CATEGORY_NAME", "tags": ["tag1", "tag2"]}
        """,
        item.getTitle(), Objects.toString(item.getSummary(), ""), item.getSource());
  }

  private String buildBatchClassificationPrompt(List<CrawledItem> items) {
    StringBuilder sb = new StringBuilder();
    sb.append(
        """
        Classify each tech article into exactly ONE category.

        Categories:
        - OPENSOURCE: Open source projects, libraries, frameworks, repos
        - EVENT: Tech events, conferences, meetups, launches
        - NEW_TECH: New technologies, products, innovations, breakthroughs
        - REGULATION: Tech laws, policies, regulations, government actions on tech
        - MINDSET: Engineering philosophy, career advice, leadership insights
        - TOOL: Developer tools, productivity, workflows
        - CAREER: Job market, hiring, interviews, salary
        - OTHER: Doesn't fit above

        Articles:
        """);

    for (int i = 0; i < items.size(); i++) {
      CrawledItem item = items.get(i);
      sb.append(
          String.format(
              "%d. Title: %s | Summary: %s\n",
              i + 1, item.getTitle(), Objects.toString(item.getSummary(), "N/A")));
    }

    sb.append(
        "\nRespond with ONLY a JSON object: {\"classifications\": [{\"index\": 1, \"category\":"
            + " \"CATEGORY_NAME\", \"tags\": [\"tag1\"]}, ...]}");

    return sb.toString();
  }

  private TrendingCategory parseCategory(String response) {
    try {
      JsonNode root = objectMapper.readTree(response);
      String category = root.path("category").asText("OTHER");
      return TrendingCategory.valueOf(category);
    } catch (Exception e) {
      return TrendingCategory.OTHER;
    }
  }

  private List<TrendingCategory> parseBatchCategories(String response, int expectedSize) {
    try {
      JsonNode root = objectMapper.readTree(response);
      JsonNode classifications = root.path("classifications");

      List<TrendingCategory> results = new java.util.ArrayList<>();
      for (int i = 0; i < expectedSize; i++) {
        results.add(TrendingCategory.OTHER);
      }

      if (classifications.isArray()) {
        for (JsonNode node : classifications) {
          int index = node.path("index").asInt(0) - 1;
          String category = node.path("category").asText("OTHER");
          if (index >= 0 && index < expectedSize) {
            try {
              results.set(index, TrendingCategory.valueOf(category));
            } catch (IllegalArgumentException ignored) {
            }
          }
        }
      }

      return results;
    } catch (Exception e) {
      log.warn("Failed to parse batch classification response: {}", e.getMessage());
      List<TrendingCategory> defaults = new java.util.ArrayList<>();
      for (int i = 0; i < expectedSize; i++) {
        defaults.add(TrendingCategory.OTHER);
      }
      return defaults;
    }
  }
}
