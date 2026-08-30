package com.socialapp.linkpreview.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.socialapp.linkpreview.dto.LinkPreviewResponseDto;

/**
 * Component tests for {@link OpenGraphParser}, per ISTQB CTFL v4.0.1 Section 2.2.2.
 *
 * <p>The cases are the shapes real pages come in — attributes in either order, single quotes,
 * repeated {@code og:image}, entity-escaped titles, relative image paths — rather than the tidy
 * markup a parser is easy to write against. That is the whole risk of matching HTML with regular
 * expressions, so it is what these tests spend their time on.
 */
class OpenGraphParserTest {

  private final OpenGraphParser parser = new OpenGraphParser();

  private static final URI PAGE = URI.create("https://example.com/blog/post");

  @Nested
  @DisplayName("parse")
  class ParseTests {

    @Test
    @DisplayName("shouldReadAllFourFieldsFromOpenGraphTags_happyPath")
    void shouldReadOpenGraphTags() {
      // Given
      String html =
          """
          <html><head>
            <meta property="og:title" content="How caching bites back">
            <meta property="og:description" content="A four-hour debugging session, written down">
            <meta property="og:image" content="https://cdn.example.com/cover.png">
            <meta property="og:site_name" content="Example Engineering">
          </head><body>ignored</body></html>
          """;

      // When
      LinkPreviewResponseDto preview = parser.parse(html, PAGE);

      // Then
      assertThat(preview.title()).isEqualTo("How caching bites back");
      assertThat(preview.description()).isEqualTo("A four-hour debugging session, written down");
      assertThat(preview.thumbnailUrl()).isEqualTo("https://cdn.example.com/cover.png");
      assertThat(preview.siteName()).isEqualTo("Example Engineering");
    }

    @Test
    @DisplayName("shouldFallBackToTwitterCardTags_whenOpenGraphIsAbsent")
    void shouldFallBackToTwitterTags() {
      // Given — plenty of sites ship one set and not the other
      String html =
          """
          <meta name="twitter:title" content="Twitter only">
          <meta name="twitter:image" content="https://cdn.example.com/t.png">
          """;

      // When
      LinkPreviewResponseDto preview = parser.parse(html, PAGE);

      // Then
      assertThat(preview.title()).isEqualTo("Twitter only");
      assertThat(preview.thumbnailUrl()).isEqualTo("https://cdn.example.com/t.png");
    }

    @Test
    @DisplayName("shouldFallBackToTheTitleTagAndTheHost_whenNoMetadataExistsAtAll")
    void shouldFallBackToTitleTag() {
      // Given — the ordinary case for an older page: a <title> and nothing else
      String html = "<html><head><title>  Just a title  </title></head></html>";

      // When
      LinkPreviewResponseDto preview = parser.parse(html, PAGE);

      // Then — the host is a poor site name and a far better one than nothing
      assertThat(preview.title()).isEqualTo("Just a title");
      assertThat(preview.siteName()).isEqualTo("example.com");
      assertThat(preview.thumbnailUrl()).isNull();
    }

    @Test
    @DisplayName("shouldReturnAnEmptyPreviewForAPageThatSaysNothingAboutItself")
    void shouldReturnEmptyPreview() {
      // Given / When — a page describing itself is a courtesy, not a requirement
      LinkPreviewResponseDto preview = parser.parse("<html><body>hi</body></html>", PAGE);

      // Then — nulls, not an error: the composer prefills what it can and lets the author type
      // the rest, exactly as it did before this endpoint existed
      assertThat(preview.title()).isNull();
      assertThat(preview.description()).isNull();
      assertThat(preview.thumbnailUrl()).isNull();
      assertThat(preview.siteName()).isEqualTo("example.com");
    }

    @Test
    @DisplayName("shouldReadAttributesInEitherOrderAndInSingleQuotes")
    void shouldHandleAttributeVariations() {
      // Given — content-before-property is legal and common, and single quotes are too
      String html =
          "<meta content='Backwards' property='og:title'>"
              + "<meta content=Unquoted name=og:site_name>";

      // When
      LinkPreviewResponseDto preview = parser.parse(html, PAGE);

      // Then
      assertThat(preview.title()).isEqualTo("Backwards");
      assertThat(preview.siteName()).isEqualTo("Unquoted");
    }

    @Test
    @DisplayName("shouldKeepTheFirstOfARepeatedTag")
    void shouldKeepFirstOfRepeatedTag() {
      // Given — pages routinely list several og:image sizes; the first is the one the author put
      // there for this purpose
      String html =
          "<meta property=\"og:image\" content=\"https://cdn.example.com/large.png\">"
              + "<meta property=\"og:image\" content=\"https://cdn.example.com/thumb.png\">";

      // When / Then
      assertThat(parser.parse(html, PAGE).thumbnailUrl())
          .isEqualTo("https://cdn.example.com/large.png");
    }

    @Test
    @DisplayName("shouldResolveARelativeImagePathAgainstThePageItCameFrom")
    void shouldResolveRelativeImage() {
      // Given — og:image is specified as absolute and very often is not. Passed through unchanged,
      // "/static/cover.png" would resolve against the CLIENT's origin and fetch its 404 page.
      String html = "<meta property=\"og:image\" content=\"/static/cover.png\">";

      // When / Then
      assertThat(parser.parse(html, PAGE).thumbnailUrl())
          .isEqualTo("https://example.com/static/cover.png");
    }

    @Test
    @DisplayName("shouldDropAnImageUrlABrowserWouldNotFetchAsAnImage")
    void shouldDropNonHttpImage() {
      // Given — whatever is returned here ends up inside an <img> on somebody's page
      String html = "<meta property=\"og:image\" content=\"javascript:alert(1)\">";

      // When / Then
      assertThat(parser.parse(html, PAGE).thumbnailUrl()).isNull();
    }

    @Test
    @DisplayName("shouldDecodeTheEntitiesAPageEscapesItsOwnMetadataWith")
    void shouldDecodeEntities() {
      // Given — an ampersand in a headline is escaped by every publishing system there is
      String html =
          "<meta property=\"og:title\" content=\"Tools &amp; tricks: &quot;fast&quot; &#8212; part 2\">";

      // When / Then
      assertThat(parser.parse(html, PAGE).title()).isEqualTo("Tools & tricks: \"fast\" — part 2");
    }

    @Test
    @DisplayName("shouldNotTurnAnEscapedAmpersandIntoAnEntityOfItsOwn")
    void shouldDecodeAmpersandLast() {
      // Given — the page wrote &amp;#39; to say the text is literally "&#39;", not an apostrophe.
      // Decoding & first would turn it into one, which is the classic double-decoding bug.
      String html = "<meta property=\"og:title\" content=\"literal &amp;#39; entity\">";

      // When / Then
      assertThat(parser.parse(html, PAGE).title()).isEqualTo("literal &#39; entity");
    }

    @Test
    @DisplayName("shouldTruncateAStuffedTag_boundary")
    void shouldTruncateOverlongValues() {
      // Given — BVA on MAX_TITLE (300) and MAX_DESCRIPTION (1000): the values come from a page
      // this server does not control, and land in a column and a layout that do have limits
      String html =
          "<meta property=\"og:title\" content=\""
              + "t".repeat(500)
              + "\"><meta property=\"og:description\" content=\""
              + "d".repeat(2000)
              + "\">";

      // When
      LinkPreviewResponseDto preview = parser.parse(html, PAGE);

      // Then
      assertThat(preview.title()).hasSize(300);
      assertThat(preview.description()).hasSize(1000);
    }

    @Test
    @DisplayName("shouldTreatABlankTagAsAbsent")
    void shouldTreatBlankAsAbsent() {
      // Given — an empty content attribute is a tag a template rendered with nothing in it
      String html = "<meta property=\"og:title\" content=\"   \"><title>Real title</title>";

      // When / Then — the blank must not win over the fallback
      assertThat(parser.parse(html, PAGE).title()).isEqualTo("Real title");
    }
  }
}
