package com.socialapp.cloud.minio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
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

  /** Any size at or above {@code MIN_REAL_IMAGE_BYTES}: "a real image is already stored here". */
  private static final long REAL_SIZE = 123_456L;

  /** Below {@code MIN_REAL_IMAGE_BYTES}: "only a solid-colour placeholder is stored here". */
  private static final long PLACEHOLDER_SIZE = 800L;

  @Mock private MinIOService minIOService;

  private MinIOSeedObjectInitializer initializer;

  @BeforeEach
  void setUp() {
    initializer = new MinIOSeedObjectInitializer(minIOService);
    ReflectionTestUtils.setField(initializer, "seedObjectsOnStart", true);
    ReflectionTestUtils.setField(initializer, "fetchRemote", false);
    ReflectionTestUtils.setField(initializer, "bakedDir", "");
    ReflectionTestUtils.setField(initializer, "replacePlaceholders", false);
  }

  @Test
  @DisplayName("cờ tắt: listener không đụng gì tới MinIO")
  void flagOff_touchesNothing() {
    ReflectionTestUtils.setField(initializer, "seedObjectsOnStart", false);

    initializer.onApplicationReady();

    verifyNoInteractions(minIOService);
  }

  @Test
  @DisplayName("mọi object đã có (ảnh thật): không nạp lên gì")
  void everythingPresent_uploadsNothing() {
    when(minIOService.objectSize(anyString(), anyString())).thenReturn(REAL_SIZE);

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

    when(minIOService.objectSize(anyString(), anyString())).thenReturn(REAL_SIZE);
    missing.forEach((key, bucket) -> when(minIOService.objectSize(bucket, key)).thenReturn(-1L));

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
  @DisplayName(
      "file sách sinh ra mang đúng nội dung bốn trang của quyển đó, không phải trang trắng")
  void generatedBookFileCarriesTheBooksOwnPages() throws IOException {
    // Bản trước sinh đúng một trang A4 chỉ mang tên file, nên "Xem thử" trên một quyển có bìa thật
    // lại mở ra một trang trắng. Bốn trang ở đây đến từ db/seed/book-previews.json.
    when(minIOService.objectSize(anyString(), anyString())).thenReturn(REAL_SIZE);
    when(minIOService.objectSize("books", "previews/9008/9780134190440-preview.pdf"))
        .thenReturn(-1L);

    initializer.seedObjects();

    ArgumentCaptor<byte[]> bytesCap = ArgumentCaptor.forClass(byte[].class);
    verify(minIOService, times(1)).uploadBytes(anyString(), anyString(), bytesCap.capture(), any());

    try (PDDocument doc = Loader.loadPDF(bytesCap.getValue())) {
      // Số trang PHẢI bằng t_books.preview_pages của bộ seed SQL — cả hai lấy từ cùng một mảng
      // `pages` trong book-previews.json, và giao diện đem con số ấy ra hiển thị.
      assertThat(doc.getNumberOfPages()).isEqualTo(4);
      String text = new PDFTextStripper().getText(doc);
      assertThat(text).contains("The Go Programming Language").contains("9780134190440");
      assertThat(text).doesNotContain("File mau cho bo seed");
    }
  }

  @Test
  @DisplayName("EPUB sinh ra có một chương cho mỗi trang, chữ giữ nguyên dấu")
  void generatedEpubHasOneChapterPerPage() throws IOException {
    when(minIOService.objectSize(anyString(), anyString())).thenReturn(REAL_SIZE);
    when(minIOService.objectSize("books", "previews/9001/9780132350884-preview.epub"))
        .thenReturn(-1L);

    initializer.seedObjects();

    ArgumentCaptor<byte[]> bytesCap = ArgumentCaptor.forClass(byte[].class);
    verify(minIOService, times(1)).uploadBytes(anyString(), anyString(), bytesCap.capture(), any());

    List<String> chapters = new java.util.ArrayList<>();
    StringBuilder all = new StringBuilder();
    try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytesCap.getValue()))) {
      for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
        if (entry.getName().endsWith(".html") || entry.getName().endsWith(".xhtml")) {
          chapters.add(entry.getName());
          all.append(new String(zip.readAllBytes(), StandardCharsets.UTF_8));
        }
      }
    }

    assertThat(chapters).hasSize(4);
    assertThat(all.toString()).contains("Clean Code").contains("Robert C. Martin");
  }

  @Test
  @DisplayName("chỉ nạp object có trong manifest — không bịa key, không đụng fixture khong-ton-tai")
  void onlyUploadsManifestKeys() {
    // Chỉ một key được coi là thiếu ⇒ đúng một lượt nạp lên. Bộ khởi tạo duyệt manifest chứ không
    // suy ra key, nên không thể tạo ra hai fixture khong-ton-tai (chúng không nằm trong manifest).
    when(minIOService.objectSize(anyString(), anyString())).thenReturn(REAL_SIZE);
    when(minIOService.objectSize("profile-pictures", "avatars/9001/avatar.png")).thenReturn(-1L);

    initializer.seedObjects();

    ArgumentCaptor<String> keyCap = ArgumentCaptor.forClass(String.class);
    verify(minIOService, times(1)).uploadBytes(anyString(), keyCap.capture(), any(), anyString());
    assertThat(keyCap.getValue()).isEqualTo("avatars/9001/avatar.png");
  }

  @Test
  @DisplayName("ô màu tại chỗ + replace tắt: bỏ qua, không ghi đè")
  void placeholderInPlace_replaceOff_isSkipped() {
    when(minIOService.objectSize(anyString(), anyString())).thenReturn(PLACEHOLDER_SIZE);

    initializer.seedObjects();

    verify(minIOService, never()).uploadBytes(anyString(), anyString(), any(), anyString());
  }

  @Test
  @DisplayName(
      "ô màu tại chỗ + replace bật + không có nguồn thật: ảnh giữ nguyên, file sách được dựng lại")
  void placeholderInPlace_replaceOn_rewritesBookFilesOnly() {
    ReflectionTestUtils.setField(initializer, "replacePlaceholders", true);
    // fetchRemote vẫn tắt và không có bakedDir ⇒ không lấy được ảnh thật cho key nào.
    when(minIOService.objectSize(anyString(), anyString())).thenReturn(PLACEHOLDER_SIZE);

    initializer.seedObjects();

    ArgumentCaptor<String> keyCap = ArgumentCaptor.forClass(String.class);
    verify(minIOService, org.mockito.Mockito.atLeastOnce())
        .uploadBytes(anyString(), keyCap.capture(), any(), anyString());

    // Ảnh: ghi đè một ô màu bằng một ô màu khác thì không được gì, nên vẫn bỏ qua.
    assertThat(keyCap.getAllValues()).noneMatch(key -> key.startsWith("avatars/"));
    assertThat(keyCap.getAllValues()).noneMatch(key -> key.startsWith("covers/"));

    // File sách: bản dựng ở đây LÀ nội dung đích, nên một file một-trang của thế hệ trước phải
    // được thay. Không có vế này thì mọi môi trường đã seed một lần giữ nguyên trang trắng cũ.
    assertThat(keyCap.getAllValues()).anyMatch(key -> key.startsWith("previews/"));
    assertThat(keyCap.getAllValues()).anyMatch(key -> key.startsWith("books/"));
  }

  @Test
  @DisplayName("ảnh nướng sẵn được dùng nguyên vẹn, không cần mạng, kể cả khi object đã có ô màu")
  void bakedImageIsUsedVerbatim(@TempDir Path bakedRoot) throws IOException {
    byte[] bakedAvatar = new byte[5000];
    for (int i = 0; i < bakedAvatar.length; i++) {
      bakedAvatar[i] = (byte) (i % 251);
    }
    Path avatarFile = bakedRoot.resolve("avatars/9001/avatar.png");
    Files.createDirectories(avatarFile.getParent());
    Files.write(avatarFile, bakedAvatar);

    ReflectionTestUtils.setField(initializer, "bakedDir", bakedRoot.toString());
    ReflectionTestUtils.setField(initializer, "replacePlaceholders", true);

    // Mọi object coi như đã có ảnh thật, TRỪ avatar 9001 đang là ô màu.
    when(minIOService.objectSize(anyString(), anyString())).thenReturn(REAL_SIZE);
    when(minIOService.objectSize("profile-pictures", "avatars/9001/avatar.png"))
        .thenReturn(PLACEHOLDER_SIZE);

    initializer.seedObjects();

    ArgumentCaptor<byte[]> bytesCap = ArgumentCaptor.forClass(byte[].class);
    verify(minIOService, times(1))
        .uploadBytes(
            eq("profile-pictures"), eq("avatars/9001/avatar.png"), bytesCap.capture(), anyString());
    assertThat(bytesCap.getValue()).isEqualTo(bakedAvatar);
  }

  @Test
  @DisplayName("file nướng sẵn nhỏ hơn ngưỡng (bản build cũng chỉ có ô màu): bỏ qua nó")
  void bakedImageBelowThresholdIsIgnored(@TempDir Path bakedRoot) throws IOException {
    Path avatarFile = bakedRoot.resolve("avatars/9001/avatar.png");
    Files.createDirectories(avatarFile.getParent());
    Files.write(avatarFile, new byte[500]);

    ReflectionTestUtils.setField(initializer, "bakedDir", bakedRoot.toString());
    when(minIOService.objectSize(anyString(), anyString())).thenReturn(REAL_SIZE);
    when(minIOService.objectSize("profile-pictures", "avatars/9001/avatar.png")).thenReturn(-1L);

    initializer.seedObjects();

    // fetchRemote tắt ⇒ rơi về placeholder do lớp này sinh, KHÔNG phải 500 byte từ đĩa.
    ArgumentCaptor<byte[]> bytesCap = ArgumentCaptor.forClass(byte[].class);
    verify(minIOService)
        .uploadBytes(
            eq("profile-pictures"), eq("avatars/9001/avatar.png"), bytesCap.capture(), anyString());
    assertThat(bytesCap.getValue().length).isNotEqualTo(500);
    assertThat(isPng(bytesCap.getValue())).isTrue();
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
