package com.socialapp.cloud.minio;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.TestPropertySource;

import com.socialapp.AbstractIntegrationTest;

/**
 * End-to-end check of {@link MinIOSeedObjectInitializer} against a real MinIO container.
 *
 * <p>{@code minio.seed-objects-fetch-remote=false} keeps it offline and deterministic — every
 * object is a generated placeholder, no DiceBear/Picsum/Open Library call. {@code
 * minio.seed-objects-on-start} is left at its default (false) so the {@code ApplicationReadyEvent}
 * listener does not also fire a background run that would race these assertions; the test drives
 * {@link MinIOSeedObjectInitializer#seedObjects()} directly instead.
 */
@TestPropertySource(properties = "minio.seed-objects-fetch-remote=false")
class MinIOSeedObjectInitializerIntegrationTest extends AbstractIntegrationTest {

  private static final Map<String, String> BUCKET_OF_PREFIX =
      Map.of(
          "avatars", "profile-pictures",
          "covers-user", "profile-pictures",
          "posts", "post-media",
          "books", "books",
          "previews", "books",
          "covers", "book-covers");

  @Autowired private MinIOSeedObjectInitializer initializer;
  @Autowired private MinIOService minIOService;

  @Test
  @DisplayName(
      "nạp mọi key trong manifest vào đúng bucket, bỏ qua fixture khong-ton-tai, idempotent")
  void seedsEveryManifestObjectAndIsIdempotent() throws Exception {
    List<String> manifestKeys = readManifestKeys();
    assertThat(manifestKeys).isNotEmpty().noneMatch(k -> k.contains("khong-ton-tai"));

    initializer.seedObjects();

    for (String bucket : List.of("profile-pictures", "post-media", "books", "book-covers")) {
      List<String> present = minIOService.listAllFiles(bucket);
      List<String> expected =
          manifestKeys.stream()
              .filter(k -> bucket.equals(BUCKET_OF_PREFIX.get(prefixOf(k))))
              .toList();
      assertThat(present).as("bucket %s", bucket).containsAll(expected);
    }

    int total = countAll();
    assertThat(total).isGreaterThanOrEqualTo(manifestKeys.size());

    // Lần chạy thứ hai: object đã có ⇒ không thêm cái nào.
    initializer.seedObjects();
    assertThat(countAll()).isEqualTo(total);
  }

  private int countAll() {
    int n = 0;
    for (String bucket : List.of("profile-pictures", "post-media", "books", "book-covers")) {
      n += minIOService.listAllFiles(bucket).size();
    }
    return n;
  }

  private static String prefixOf(String key) {
    return key.contains("/") ? key.substring(0, key.indexOf('/')) : "";
  }

  private static List<String> readManifestKeys() throws Exception {
    List<String> keys = new ArrayList<>();
    try (InputStream in = new ClassPathResource("db/seed/seed-manifest.tsv").getInputStream()) {
      String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      for (String line : content.split("\n")) {
        line = line.strip();
        if (!line.isEmpty() && !line.startsWith("#")) {
          keys.add(line.split("\t", -1)[0]);
        }
      }
    }
    return keys;
  }
}
