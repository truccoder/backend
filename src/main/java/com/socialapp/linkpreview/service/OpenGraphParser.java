package com.socialapp.linkpreview.service;

import java.net.URI;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.socialapp.linkpreview.dto.LinkPreviewResponseDto;

import lombok.extern.slf4j.Slf4j;

/**
 * Reads the handful of {@code <meta>} tags a page uses to describe itself to anything that links to
 * it — Open Graph first, Twitter cards second, plain HTML last.
 *
 * <p><b>Regular expressions, not an HTML parser, and that is a decision rather than a shortcut.</b>
 * The alternative is a new dependency (jsoup) to build a full document tree, out of which four
 * strings are read and the rest thrown away. What is being matched here is not arbitrary markup: it
 * is {@code <meta>} elements, which are void, cannot nest, and cannot contain anything. The input
 * is also already bounded to half a megabyte by the caller, so the usual catastrophic-backtracking
 * worry has a fixed ceiling on it. A page that hides its Open Graph tags somewhere this misses
 * yields a null field, which is a supported outcome — the composer simply asks the author to type
 * that one in, exactly as it did before this endpoint existed.
 */
@Slf4j
@Component
public class OpenGraphParser {

  private static final Pattern META_TAG =
      Pattern.compile("<meta\\b[^>]*>", Pattern.CASE_INSENSITIVE);

  private static final Pattern ATTRIBUTE =
      Pattern.compile(
          "(property|name|content)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'>]+))",
          Pattern.CASE_INSENSITIVE);

  private static final Pattern TITLE_TAG =
      Pattern.compile("<title[^>]*>(.*?)</title>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

  private static final Pattern NUMERIC_ENTITY = Pattern.compile("&#(x?)([0-9a-fA-F]+);");

  /** Long enough for any real headline; short enough that a stuffed tag cannot fill a column. */
  private static final int MAX_TITLE = 300;

  private static final int MAX_DESCRIPTION = 1000;

  /**
   * @param html the page source, already size-capped by the caller
   * @param finalUrl the URL the body actually came from, after redirects — relative image paths
   *     resolve against this and not against what the user typed
   */
  public LinkPreviewResponseDto parse(String html, URI finalUrl) {
    Map<String, String> meta = metaTags(html);

    String title = firstOf(meta, "og:title", "twitter:title");
    if (Objects.isNull(title)) {
      title = titleTag(html);
    }

    String description = firstOf(meta, "og:description", "twitter:description", "description");
    String image = firstOf(meta, "og:image", "og:image:url", "twitter:image", "twitter:image:src");
    String siteName = firstOf(meta, "og:site_name", "application-name");

    return new LinkPreviewResponseDto(
        truncate(title, MAX_TITLE),
        truncate(description, MAX_DESCRIPTION),
        absolute(image, finalUrl),
        // The host is a poor site name and a much better one than nothing: it is what a reader
        // would have called the site anyway, and it is never absent.
        Objects.nonNull(siteName) ? truncate(siteName, MAX_TITLE) : finalUrl.getHost());
  }

  /**
   * Every {@code <meta>} on the page, keyed by its lower-cased {@code property} or {@code name}.
   *
   * <p>First occurrence wins. Pages do repeat these — most often {@code og:image} listing several
   * sizes — and the first is the one the author put there for exactly this purpose.
   */
  private Map<String, String> metaTags(String html) {
    Map<String, String> tags = new HashMap<>();
    Matcher tagMatcher = META_TAG.matcher(html);

    while (tagMatcher.find()) {
      String tag = tagMatcher.group();
      String key = null;
      String content = null;

      Matcher attributeMatcher = ATTRIBUTE.matcher(tag);
      while (attributeMatcher.find()) {
        String name = attributeMatcher.group(1).toLowerCase(Locale.ROOT);
        String value =
            firstNonNull(
                attributeMatcher.group(2), attributeMatcher.group(3), attributeMatcher.group(4));
        if ("content".equals(name)) {
          content = value;
        } else if (Objects.isNull(key)) {
          // property and name mean the same thing to us — Open Graph uses the first, Twitter and
          // plain HTML the second — so whichever appears is the key.
          key = Objects.nonNull(value) ? value.toLowerCase(Locale.ROOT).trim() : null;
        }
      }

      if (Objects.nonNull(key) && Objects.nonNull(content) && !tags.containsKey(key)) {
        tags.put(key, decodeEntities(content).trim());
      }
    }

    return tags;
  }

  private String titleTag(String html) {
    Matcher matcher = TITLE_TAG.matcher(html);
    return matcher.find() ? decodeEntities(matcher.group(1)).trim() : null;
  }

  private String firstOf(Map<String, String> meta, String... keys) {
    for (String key : keys) {
      String value = meta.get(key);
      if (Objects.nonNull(value) && !value.isBlank()) {
        return value;
      }
    }
    return null;
  }

  private String firstNonNull(String... values) {
    for (String value : values) {
      if (Objects.nonNull(value)) {
        return value;
      }
    }
    return null;
  }

  /**
   * Turns a possibly-relative image reference into something a browser elsewhere can load.
   *
   * <p>{@code og:image} is specified as absolute and is very often not, so a preview that passed it
   * through unchanged would hand the client {@code /static/cover.png} — a path that resolves
   * against the client's own origin and returns that site's 404 page.
   */
  private String absolute(String image, URI finalUrl) {
    if (Objects.isNull(image) || image.isBlank()) {
      return null;
    }
    try {
      URI resolved = finalUrl.resolve(image.trim());
      // Anything that resolved to a scheme a browser will not fetch as an image — javascript:,
      // data:, file: — is dropped rather than passed on to be embedded in a page.
      String scheme = resolved.getScheme();
      if (Objects.isNull(scheme)
          || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
        return null;
      }
      return resolved.toString();
    } catch (IllegalArgumentException e) {
      log.debug("Unusable og:image '{}' on {}: {}", image, finalUrl, e.getMessage());
      return null;
    }
  }

  /**
   * Undoes the entity escaping a page applies to its own metadata.
   *
   * <p>The named entities that have to be escaped inside an attribute, plus {@code &nbsp;} and the
   * numeric forms. Not a general decoder: a title carrying a rarer entity keeps the raw text, which
   * is a cosmetic flaw in a field the author can edit, and the alternative is carrying a table of
   * two thousand names for it.
   */
  private String decodeEntities(String value) {
    String decoded =
        value
            .replace("&nbsp;", " ")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">");

    Matcher matcher = NUMERIC_ENTITY.matcher(decoded);
    StringBuilder out = new StringBuilder();
    while (matcher.find()) {
      String replacement;
      try {
        int codePoint = Integer.parseInt(matcher.group(2), matcher.group(1).isEmpty() ? 10 : 16);
        replacement =
            Character.isValidCodePoint(codePoint)
                ? new String(Character.toChars(codePoint))
                : matcher.group();
      } catch (NumberFormatException e) {
        replacement = matcher.group();
      }
      matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
    }
    matcher.appendTail(out);

    // Last, so that an escaped ampersand does not turn its neighbour into an entity: the page
    // wrote "&amp;" precisely to say that what follows is text, not the start of one.
    return out.toString().replace("&amp;", "&");
  }

  private String truncate(String value, int max) {
    if (Objects.isNull(value) || value.isBlank()) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
  }
}
