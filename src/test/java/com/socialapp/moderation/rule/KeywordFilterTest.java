package com.socialapp.moderation.rule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import com.socialapp.moderation.config.ModerationProperties;

/**
 * Component (unit) tests for {@link KeywordFilter}, per ISTQB CTFL v4.0.1 Section 2.2.1
 * (component testing). {@link ResourceLoader} is mocked so the blacklist content is fully
 * controlled per test rather than depending on the real {@code moderation/blacklist.txt} file.
 */
@ExtendWith(MockitoExtension.class)
class KeywordFilterTest {

  @Mock private ResourceLoader resourceLoader;

  private final ModerationProperties properties = new ModerationProperties();

  private KeywordFilter keywordFilter;

  @BeforeEach
  void setUp() {
    keywordFilter = new KeywordFilter(resourceLoader, properties);
  }

  private void stubBlacklistContent(String content) throws IOException {
    Resource resource = mock(Resource.class);
    when(resource.exists()).thenReturn(true);
    when(resource.getInputStream())
        .thenReturn(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
    when(resourceLoader.getResource(properties.getRules().getKeywordBlacklistPath()))
        .thenReturn(resource);
  }

  @Nested
  @DisplayName("init: loading the blacklist file")
  class InitTests {

    @Test
    @DisplayName("shouldSkipCommentsAndBlankLines_whenLoadingTheBlacklist")
    void shouldSkipCommentsAndBlankLines_whenLoadingTheBlacklist() throws IOException {
      // Given
      stubBlacklistContent("# a comment\n\ncon cho\n   \nfuck\n");

      // When
      keywordFilter.init();

      // Then
      assertThat(keywordFilter.containsBlacklistedContent("con cho")).isTrue();
      assertThat(keywordFilter.containsBlacklistedContent("fuck")).isTrue();
    }

    @Test
    @DisplayName("shouldLeaveBlacklistEmpty_whenResourceDoesNotExist")
    void shouldLeaveBlacklistEmpty_whenResourceDoesNotExist() {
      // Given
      Resource resource = mock(Resource.class);
      when(resource.exists()).thenReturn(false);
      when(resourceLoader.getResource(properties.getRules().getKeywordBlacklistPath()))
          .thenReturn(resource);

      // When
      keywordFilter.init();

      // Then
      assertThat(keywordFilter.containsBlacklistedContent("con cho")).isFalse();
    }

    @Test
    @DisplayName("shouldLeaveBlacklistEmpty_whenReadingTheResourceThrows")
    void shouldLeaveBlacklistEmpty_whenReadingTheResourceThrows() throws IOException {
      // Given — a real I/O failure (e.g. a corrupt classpath resource) must not crash startup
      Resource resource = mock(Resource.class);
      when(resource.exists()).thenReturn(true);
      when(resource.getInputStream()).thenThrow(new IOException("boom"));
      when(resourceLoader.getResource(properties.getRules().getKeywordBlacklistPath()))
          .thenReturn(resource);

      // When
      keywordFilter.init();

      // Then
      assertThat(keywordFilter.containsBlacklistedContent("con cho")).isFalse();
    }
  }

  @Nested
  @DisplayName("containsBlacklistedContent")
  class ContainsBlacklistedContentTests {

    @Test
    @DisplayName("shouldReturnFalse_whenContentIsBlank")
    void shouldReturnFalse_whenContentIsBlank() {
      // When
      boolean result = keywordFilter.containsBlacklistedContent("   ");

      // Then
      assertThat(result).isFalse();
    }

    @Test
    @DisplayName("shouldReturnTrue_whenContentContainsAnExactBlacklistedKeyword")
    void shouldReturnTrue_whenContentContainsAnExactBlacklistedKeyword() throws IOException {
      // Given
      stubBlacklistContent("con cho\n");
      keywordFilter.init();

      // When
      boolean result = keywordFilter.containsBlacklistedContent("day la con cho");

      // Then
      assertThat(result).isTrue();
    }

    @Test
    @DisplayName("shouldReturnTrue_whenKeywordMatchesAfterVietnameseDiacriticsAreStripped")
    void shouldReturnTrue_whenKeywordMatchesAfterVietnameseDiacriticsAreStripped()
        throws IOException {
      // Given — blacklist stores "Đồ Ngu" which normalizes down to "do ngu"
      stubBlacklistContent("Đồ Ngu\n");
      keywordFilter.init();

      // When — content uses different diacritics/casing but normalizes to the same form
      boolean result = keywordFilter.containsBlacklistedContent("May dung la ĐỒ NGU roi");

      // Then
      assertThat(result).isTrue();
    }

    @Test
    @DisplayName("shouldReturnTrue_whenKeywordMatchesIgnoringSpacing")
    void shouldReturnTrue_whenKeywordMatchesIgnoringSpacing() throws IOException {
      // Given
      stubBlacklistContent("con cho\n");
      keywordFilter.init();

      // When — content has no spaces around the keyword at all
      boolean result = keywordFilter.containsBlacklistedContent("reallyconchobro");

      // Then
      assertThat(result).isTrue();
    }

    @Test
    @DisplayName("shouldReturnFalse_whenContentContainsNoBlacklistedKeyword")
    void shouldReturnFalse_whenContentContainsNoBlacklistedKeyword() throws IOException {
      // Given
      stubBlacklistContent("con cho\n");
      keywordFilter.init();

      // When
      boolean result = keywordFilter.containsBlacklistedContent("hello world, nice day today");

      // Then
      assertThat(result).isFalse();
    }
  }
}
