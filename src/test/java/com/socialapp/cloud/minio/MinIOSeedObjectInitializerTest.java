package com.socialapp.cloud.minio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Component tests for {@link MinIOSeedObjectInitializer}. The real manifest
 * ({@code src/main/resources/db/seed/seed-manifest.tsv}) is on the test classpath, so these run
 * against it directly — {@link MinIOService} is mocked so no MinIO server is touched and no image
 * is fetched from the network (fetch is switched off).
 */
@ExtendWith(MockitoExtension.class)
class MinIOSeedObjectInitializerTest {

  @Mock private MinIOService minIOService;

  private MinIOSeedObjectInitializer initializer;

  @BeforeEach
  void setUp() {
    initializer = new MinIOSeedObjectInitializer(minIOService);
    ReflectionTestUtils.setField(initializer, "seedObjectsOnStart", true);
    ReflectionTestUtils.setField(initializer, "fetchRemote", false);
  }

  @Test
  @DisplayName("cờ tắt: listener không đụng gì tới MinIO")
  void flagOff_touchesNothing() {
    ReflectionTestUtils.setField(initializer, "seedObjectsOnStart", false);

    initializer.onApplicationReady();

    verifyNoInteractions(minIOService);
  }

  @Test
  @DisplayName("mọi object đã có: không nạp lên gì")
  void everythingPresent_uploadsNothing() {
    when(minIOService.objectExists(anyString(), anyString())).thenReturn(true);

    initializer.seedObjects();

    verify(minIOService, never()).uploadBytes(anyString(), anyString(), any(), anyString());
  }

  @Test
  @DisplayName("object thiếu được sinh dự phòng và nạp vào đúng bucket, đúng content-type")
  void missingObjects_generatedAndUploadedToRightBucket() {
    // key ⇢ bucket kỳ vọng (khớp minio-init + generate_seed.py BUCKET_OF_PREFIX)
    Map<String, String> missing = new HashMap<>();
    missing.put("avatars/9001/avatar.png", "profile-pictures");
    missing.put("covers-user/9001/cover.png", "profile-pictures");
    missing.put("posts/100241/cover.png", "post-media");
    missing.put("covers/9001/9780132350884.jpg", "book-covers");
    missing.put("books/9001/9780132350884.epub", "books");
    missing.put("books/9008/9780134190440.pdf", "books");

    when(minIOService.objectExists(anyString(), anyString())).thenReturn(true);
    missing.forEach(
        (key, bucket) -> when(minIOService.objectExists(bucket, key)).thenReturn(false));

    initializer.seedObjects();

    ArgumentCaptor<String> bucketCap = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> keyCap = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<byte[]> bytesCap = ArgumentCaptor.forClass(byte[].class);
    ArgumentCaptor<String> typeCap = ArgumentCaptor.forClass(String.class);
    verify(minIOService, times(6))
        .uploadBytes(bucketCap.capture(), keyCap.capture(), bytesCap.capture(), typeCap.capture());

    Map<String, byte[]> uploadedBytes = new HashMap<>();
    Map<String, String> uploadedBucket = new HashMap<>();
    Map<String, String> uploadedType = new HashMap<>();
    for (int i = 0; i < keyCap.getAllValues().size(); i++) {
      String key = keyCap.getAllValues().get(i);
      uploadedBytes.put(key, bytesCap.getAllValues().get(i));
      uploadedBucket.put(key, bucketCap.getAllValues().get(i));
      uploadedType.put(key, typeCap.getAllValues().get(i));
    }

    assertThat(uploadedBucket).containsAllEntriesOf(missing);

    assertThat(isPng(uploadedBytes.get("avatars/9001/avatar.png"))).isTrue();
    assertThat(uploadedType.get("avatars/9001/avatar.png")).isEqualTo("image/png");
    assertThat(isPng(uploadedBytes.get("posts/100241/cover.png"))).isTrue();

    assertThat(isJpeg(uploadedBytes.get("covers/9001/9780132350884.jpg"))).isTrue();
    assertThat(uploadedType.get("covers/9001/9780132350884.jpg")).isEqualTo("image/jpeg");

    byte[] pdf = uploadedBytes.get("books/9008/9780134190440.pdf");
    assertThat(new String(pdf, 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
    assertThat(uploadedType.get("books/9008/9780134190440.pdf")).isEqualTo("application/pdf");

    byte[] epub = uploadedBytes.get("books/9001/9780132350884.epub");
    assertThat(epub[0]).isEqualTo((byte) 'P');
    assertThat(epub[1]).isEqualTo((byte) 'K');
    assertThat(new String(epub, StandardCharsets.ISO_8859_1)).contains("application/epub+zip");
    assertThat(uploadedType.get("books/9001/9780132350884.epub")).isEqualTo("application/epub+zip");
  }

  @Test
  @DisplayName("chỉ nạp object có trong manifest — không bịa key, không đụng fixture khong-ton-tai")
  void onlyUploadsManifestKeys() {
    // Chỉ một key được coi là thiếu ⇒ đúng một lượt nạp lên. Bộ khởi tạo duyệt manifest chứ không
    // suy ra key, nên không thể tạo ra hai fixture khong-ton-tai (chúng không nằm trong manifest).
    when(minIOService.objectExists(anyString(), anyString())).thenReturn(true);
    when(minIOService.objectExists("profile-pictures", "avatars/9001/avatar.png"))
        .thenReturn(false);

    initializer.seedObjects();

    ArgumentCaptor<String> keyCap = ArgumentCaptor.forClass(String.class);
    verify(minIOService, times(1)).uploadBytes(anyString(), keyCap.capture(), any(), anyString());
    assertThat(keyCap.getValue()).isEqualTo("avatars/9001/avatar.png");
  }

  private static boolean isPng(byte[] b) {
    return b != null
        && b.length > 4
        && (b[0] & 0xFF) == 0x89
        && b[1] == 'P'
        && b[2] == 'N'
        && b[3] == 'G';
  }

  private static boolean isJpeg(byte[] b) {
    return b != null && b.length > 2 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8;
  }
}
