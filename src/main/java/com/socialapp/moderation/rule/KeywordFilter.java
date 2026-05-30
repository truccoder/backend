package com.socialapp.moderation.rule;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import com.socialapp.moderation.config.ModerationProperties;

import io.jsonwebtoken.lang.Strings;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class KeywordFilter {
  private final ResourceLoader resourceLoader;
  private final ModerationProperties properties;

  private static final Pattern DIACRITICS_PATTERN =
      Pattern.compile("\\p{InCombiningDiacriticalMarks}+");
  private static final Pattern LEET_SPEAK_PATTERN = Pattern.compile("[0-9@$!]+");

  private final Set<String> blacklist = new HashSet<>();

  @PostConstruct
  public void init() {
    loadBlacklist();
  }

  public boolean containsBlacklistedContent(String content) {
    if (Strings.hasText(content)) {
      return false;
    }

    String normalized = normalizeVietnamese(content.toLowerCase());
    String withoutSpaces = normalized.replaceAll("\\s+", "");

    for (String keyword : blacklist) {
      if (normalized.contains(keyword) || withoutSpaces.contains(keyword)) {
        log.debug("Blacklisted keyword detected: {}", keyword);
        return true;
      }
    }

    return false;
  }

  private String normalizeVietnamese(String text) {
    String decomposed = Normalizer.normalize(text, Normalizer.Form.NFD);
    String withoutDiacritics = DIACRITICS_PATTERN.matcher(decomposed).replaceAll("");
    return withoutDiacritics.replace("đ", "d").replace("Đ", "D");
  }

  private void loadBlacklist() {
    try {
      var resource = resourceLoader.getResource(properties.getRules().getKeywordBlacklistPath());
      if (!resource.exists()) {
        log.warn(
            "Blacklist file not found at: {}", properties.getRules().getKeywordBlacklistPath());
        return;
      }

      try (BufferedReader reader =
          new BufferedReader(
              new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
        String line;
        while ((line = reader.readLine()) != null) {
          String trimmed = line.trim().toLowerCase();
          if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
            blacklist.add(normalizeVietnamese(trimmed));
          }
        }
      }

      log.info("Loaded {} blacklisted keywords", blacklist.size());
    } catch (Exception e) {
      log.error("Failed to load keyword blacklist", e);
    }
  }
}
