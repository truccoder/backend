package com.socialapp.cloud.minio;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nl.siegmann.epublib.domain.Book;
import nl.siegmann.epublib.domain.Identifier;
import nl.siegmann.epublib.domain.Resource;
import nl.siegmann.epublib.epub.EpubWriter;

/**
 * Nạp lên MinIO mọi object mà bộ seed SQL trỏ tới, khi cờ được bật.
 *
 * <p><b>Vấn đề nó giải quyết.</b> Flyway ghi vào {@code t_users.profile_picture_url},
 * {@code t_users.cover_image_url}, {@code t_posts.images} và {@code t_books.*_key} những chuỗi trỏ
 * tới object trong MinIO — nhưng SQL không tạo được object. Không có bước này thì danh sách vẫn
 * hiện đủ, còn avatar / ảnh bìa / ảnh bài viết / bìa sách đều 404: trình duyệt hiện <b>ảnh vỡ</b>
 * thay vì rơi về chữ viết tắt, tức tệ hơn cả để NULL.
 *
 * <p><b>Vì sao nằm trong ứng dụng thay vì để ở docker-compose.</b> Máy dev có hai service
 * {@code minio-seed-objects} + {@code minio-init} chạy {@code docker/minio/generate-seed-objects.py}
 * để làm đúng việc này. Production chạy compose của repo DATN-infra, nơi không có service đó — nên
 * production nạp seed xong là có một kho ảnh trống, và lỗi ấy chỉ lộ ra khi ai đó mở một trang có
 * ảnh. Cùng lý do đã đưa {@code Neo4jSeedInitializer} và {@code NewsfeedSeedInitializer} vào ứng
 * dụng: một cơ chế nằm trong ứng dụng đi theo ứng dụng tới mọi môi trường; một service trong
 * compose thì chỉ có ở nơi người ta nhớ chép nó sang.
 *
 * <p><b>Ba nguồn nội dung, xét theo thứ tự.</b>
 *
 * <ol>
 *   <li><b>Ảnh nướng sẵn</b> ({@code minio.seed-objects-dir}, mặc định rỗng). Dockerfile có một
 *       stage chạy {@code generate-seed-objects.py} NGAY TRÊN GITHUB RUNNER — nơi đường ra
 *       DiceBear/Picsum/Open Library sạch — rồi copy kết quả vào image. Có thư mục này thì
 *       production dùng đúng bộ ảnh mà bản build tạo ra: tất định, offline, vài giây. Đây là cách
 *       production nên chạy.
 *   <li><b>Tải ảnh thật</b> tại URL nguồn trong manifest. Chỉ dùng khi (1) không có; là đường dev
 *       và đường dự phòng nếu ảnh nướng sẵn thiếu.
 *   <li><b>Sinh ô màu / file mẫu</b> khi cả hai đường trên thất bại. Một ô màu vẫn hơn một ảnh vỡ.
 * </ol>
 *
 * <p><b>Ô màu KHÔNG còn là vĩnh viễn.</b> Trước đây bộ khởi tạo bỏ qua mọi object đã tồn tại, nên
 * một lần chạy đầu tồi trên VPS (nguồn công cộng bóp băng thông IP datacenter) đóng băng cả kho
 * thành ô màu — không lần deploy nào sửa. Nay khi {@code minio.seed-objects-replace-placeholders}
 * bật, object nào nhỏ hơn {@code MIN_REAL_IMAGE_BYTES} mà manifest có khai nguồn thật được coi là ô
 * màu và thử lấy lại ảnh thật (một ảnh thật đã lưu luôn ≥ ngưỡng đó, nên không có dương tính giả).
 *
 * <p><b>Mặc định TẮT</b> ({@code minio.seed-objects-on-start}), cùng lý do với hai initializer kia.
 * {@code application-prod.yml} bật nó; máy dev để {@code docker-compose} lo.
 *
 * <p><b>Chạy nền, không chặn khởi động.</b> Lần đầu trên một MinIO trắng, nếu phải tải mạng thì mất
 * vài phút, và mọi listener {@code ApplicationReadyEvent} chạy TRƯỚC khi Spring đánh dấu "sẵn sàng".
 * Vì vậy listener chỉ khởi một luồng nền rồi trả về ngay.
 *
 * <p><b>Idempotent.</b> Mỗi object kiểm kích thước trước; đã có ảnh thật thì bỏ qua. Hai key
 * {@code khong-ton-tai} cố ý KHÔNG nằm trong manifest nên không bao giờ được tạo.
 *
 * <p><b>Không bao giờ làm hỏng khởi động.</b> Mọi lỗi được ghi log rồi bỏ qua.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MinIOSeedObjectInitializer {

  private static final String MANIFEST_PATH = "db/seed/seed-manifest.tsv";

  /**
   * Prefix của key ⇢ BUCKET chứa nó. Phải khớp từng dòng với {@code BUCKET_OF_PREFIX} trong
   * {@code scripts/seed/generate_seed.py} và các lệnh {@code mc cp} của {@code minio-init}. Object
   * được nạp lên với đúng key trong manifest (đã bao gồm prefix), vào bucket tra ở bảng này.
   */
  private static final Map<String, String> BUCKET_OF_PREFIX =
      Map.of(
          "avatars", "profile-pictures",
          "covers-user", "profile-pictures",
          "posts", "post-media",
          "books", "books",
          "previews", "books",
          "covers", "book-covers");

  /**
   * Bốn luồng — đúng con số đo được của {@code generate-seed-objects.py}: nút thắt là giới hạn tốc
   * độ THEO NGUỒN (DiceBear/Pravatar/Picsum/Open Library đều là dịch vụ công cộng), không phải băng
   * thông. Đẩy mạnh tay thì nguồn bắt đầu từ chối và mọi ảnh bị từ chối rơi về ô màu.
   */
  private static final int FETCH_WORKERS = 4;

  /**
   * Ảnh tải về (hoặc file nướng sẵn) nhỏ hơn ngưỡng này bị coi là hỏng / là ô màu. Open Library
   * trả HTTP 200 kèm một ảnh 1x1 khi không có bìa cho ISBN đó, nên chỉ kiểm mã trạng thái là không
   * đủ; và mọi ô màu do lớp này sinh ra đều nằm xa dưới ngưỡng.
   */
  private static final int MIN_REAL_IMAGE_BYTES = 2000;

  /**
   * 20 giây, không phải 8. Trên VPS, DiceBear render PNG và Open Library tra bìa có thể chậm hơn 8
   * giây dưới tải — timeout ngắn biến một nguồn chậm thành một ô màu. Nhánh này chỉ chạy khi không
   * có ảnh nướng sẵn, nên nới rộng ở đây không phạt đường production đã bake.
   */
  private static final Duration FETCH_TIMEOUT = Duration.ofSeconds(20);

  /** Thử lại một lần: phần lớn lỗi ở các nguồn này là chập chờn (rate-limit tạm, reset kết nối). */
  private static final int FETCH_ATTEMPTS = 2;

  private static final String USER_AGENT = "elitenexus-seed/1.0";

  /** Bìa mỗi thứ một màu, sao chép đúng bảng của {@code generate-seed-objects.py}. */
  private static final int[][] PALETTE = {
    {37, 99, 235}, {5, 150, 105}, {219, 39, 119}, {217, 119, 6},
    {124, 58, 237}, {13, 148, 136}, {190, 24, 93}, {2, 132, 199},
  };

  private final MinIOService minIOService;

  /**
   * Bật cùng lúc với việc nạp seed. {@code application-prod.yml} đặt {@code true}; env
   * {@code MINIO_SEED_OBJECTS_ON_START} ghi đè được để tắt khẩn cấp mà không cần build lại.
   */
  @Value("${minio.seed-objects-on-start:false}")
  private boolean seedObjectsOnStart;

  /**
   * Cho phép tắt bước tải ảnh thật — mọi object thành ô màu / file mẫu. Test dùng để chạy offline
   * và tất định; production để {@code true} (nhưng thường không cần vì đã có ảnh nướng sẵn).
   */
  @Value("${minio.seed-objects-fetch-remote:true}")
  private boolean fetchRemote;

  /**
   * Thư mục chứa ảnh seed thật được NƯỚNG vào image lúc build (stage {@code seed-objects} trong
   * Dockerfile). Có giá trị ⇒ file tìm thấy ở đây được dùng nguyên vẹn, không gọi mạng. Rỗng (mặc
   * định) ⇒ giữ nguyên đường tải-từ-xa mà dev và test dùng.
   */
  @Value("${minio.seed-objects-dir:}")
  private String bakedDir;

  /**
   * Cho phép ghi đè một ô màu đã nằm trong MinIO bằng ảnh thật khi lần này lấy được. Mặc định tắt
   * để lần chạy thường chỉ là ~1.100 lượt kiểm-rồi-bỏ-qua; bật khi cần chữa một kho đã lỡ đóng băng
   * thành ô màu (ví dụ sau một lần deploy đầu tải mạng thất bại).
   */
  @Value("${minio.seed-objects-replace-placeholders:false}")
  private boolean replacePlaceholders;

  @Order(30)
  @EventListener(ApplicationReadyEvent.class)
  public void onApplicationReady() {
    if (!seedObjectsOnStart) {
      return;
    }
    Thread worker = new Thread(this::seedObjects, "minio-seed-objects");
    worker.setDaemon(true);
    worker.start();
  }

  /** Bộ đếm cho một lượt đồng bộ. Gom lại để {@link #processRow} không nhận chục tham số. */
  private static final class Stats {
    final AtomicInteger uploaded = new AtomicInteger();
    final AtomicInteger real = new AtomicInteger();
    final AtomicInteger baked = new AtomicInteger();
    final AtomicInteger generated = new AtomicInteger();
    final AtomicInteger replaced = new AtomicInteger();
    final AtomicInteger skipped = new AtomicInteger();
    final AtomicInteger failed = new AtomicInteger();
    final AtomicInteger unknownPrefix = new AtomicInteger();
  }

  /**
   * Đồng bộ toàn bộ manifest lên MinIO. Gọi được trực tiếp (test chạy đồng bộ); listener gọi qua
   * một luồng nền.
   */
  void seedObjects() {
    List<ManifestRow> rows;
    try {
      rows = readManifest();
    } catch (IOException | RuntimeException e) {
      log.error(
          "minio.seed-objects-on-start: không đọc được {}, bỏ qua bước nạp object seed.",
          MANIFEST_PATH,
          e);
      return;
    }

    for (String bucket : List.of("profile-pictures", "post-media", "books", "book-covers")) {
      try {
        minIOService.ensureBucketExists(bucket);
      } catch (RuntimeException e) {
        log.warn("minio.seed-objects-on-start: không chuẩn bị được bucket {}", bucket, e);
      }
    }

    Stats stats = new Stats();

    HttpClient http =
        HttpClient.newBuilder()
            .connectTimeout(FETCH_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    boolean haveBaked = bakedDir != null && !bakedDir.isBlank();
    log.info(
        "minio.seed-objects-on-start: {} object trong manifest{}{}{}",
        rows.size(),
        haveBaked ? " (ảnh nướng sẵn: " + bakedDir + ")" : "",
        fetchRemote ? "" : " (chế độ chỉ-placeholder)",
        replacePlaceholders ? " (thay ô màu bằng ảnh thật)" : "");

    ExecutorService pool = Executors.newFixedThreadPool(FETCH_WORKERS);
    try {
      List<Future<?>> futures = new ArrayList<>(rows.size());
      for (int i = 0; i < rows.size(); i++) {
        int index = i;
        ManifestRow row = rows.get(i);
        futures.add(pool.submit(() -> processRow(index, row, http, stats)));
      }
      for (Future<?> f : futures) {
        f.get();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      log.warn("minio.seed-objects-on-start: bị ngắt giữa chừng");
    } catch (Exception e) {
      log.error("minio.seed-objects-on-start: lỗi không lường trước", e);
    } finally {
      pool.shutdownNow();
    }

    log.info(
        "minio.seed-objects-on-start: xong — tải lên {} ({} nướng sẵn, {} tải mới, {} ô màu; trong"
            + " đó {} ghi đè ô màu cũ), bỏ qua {} đã có, {} lỗi",
        stats.uploaded.get(),
        stats.baked.get(),
        stats.real.get(),
        stats.generated.get(),
        stats.replaced.get(),
        stats.skipped.get(),
        stats.failed.get());
    if (stats.unknownPrefix.get() > 0) {
      log.error(
          "minio.seed-objects-on-start: {} object có prefix chưa khai trong BUCKET_OF_PREFIX —"
              + " những ảnh đó vẫn 404.",
          stats.unknownPrefix.get());
    }
  }

  private void processRow(int index, ManifestRow row, HttpClient http, Stats stats) {
    String prefix = row.key().contains("/") ? row.key().substring(0, row.key().indexOf('/')) : "";
    String bucket = BUCKET_OF_PREFIX.get(prefix);
    if (bucket == null) {
      stats.unknownPrefix.incrementAndGet();
      log.error("minio.seed-objects-on-start: prefix '{}' chưa map, bỏ qua {}", prefix, row.key());
      return;
    }

    try {
      long existingSize = minIOService.objectSize(bucket, row.key());
      boolean present = existingSize >= 0;
      boolean placeholderInPlace =
          present && existingSize < MIN_REAL_IMAGE_BYTES && row.sourceUrl() != null;

      if (present && !(placeholderInPlace && replacePlaceholders)) {
        stats.skipped.incrementAndGet();
        return;
      }

      byte[] data = loadBaked(row.key());
      boolean fromBake = data != null;
      if (data == null && fetchRemote && row.sourceUrl() != null) {
        data = fetch(http, row.sourceUrl());
      }
      boolean wasReal = data != null;

      if (!wasReal) {
        if (present) {
          // Đã có một ô màu và lần này vẫn không lấy được ảnh thật — đừng ghi đè bằng ô màu khác.
          stats.skipped.incrementAndGet();
          return;
        }
        data = placeholder(row.key(), index);
      }

      minIOService.uploadBytes(bucket, row.key(), data, contentTypeOf(row.key()));
      stats.uploaded.incrementAndGet();
      if (fromBake) {
        stats.baked.incrementAndGet();
      } else if (wasReal) {
        stats.real.incrementAndGet();
      } else {
        stats.generated.incrementAndGet();
      }
      if (wasReal && present) {
        stats.replaced.incrementAndGet();
      }
    } catch (RuntimeException e) {
      stats.failed.incrementAndGet();
      log.warn("minio.seed-objects-on-start: bỏ qua {} do lỗi: {}", row.key(), e.toString());
    }
  }

  // ── Manifest ────────────────────────────────────────────────────────────────────────────────

  private record ManifestRow(String key, String kind, String sourceUrl) {}

  private List<ManifestRow> readManifest() throws IOException {
    List<ManifestRow> rows = new ArrayList<>();
    try (InputStream in = new ClassPathResource(MANIFEST_PATH).getInputStream()) {
      String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      for (String line : content.split("\n")) {
        line = line.strip();
        if (line.isEmpty() || line.startsWith("#")) {
          continue;
        }
        String[] parts = line.split("\t", -1);
        String key = parts[0];
        String kind = parts.length > 1 ? parts[1] : "";
        String url = parts.length > 2 && !parts[2].isBlank() ? parts[2] : null;
        rows.add(new ManifestRow(key, kind, url));
      }
    }
    return rows;
  }

  // ── Ảnh nướng sẵn ───────────────────────────────────────────────────────────────────────────

  /**
   * Đọc một object từ {@link #bakedDir} trên đĩa. Trả {@code null} khi thư mục không được cấu hình,
   * file không có, hoặc file nhỏ hơn ngưỡng ảnh thật (nghĩa là bản build cũng chỉ có ô màu — để
   * đường tải-từ-xa thử tiếp).
   */
  private byte[] loadBaked(String key) {
    if (bakedDir == null || bakedDir.isBlank()) {
      return null;
    }
    try {
      Path path = Path.of(bakedDir, key.split("/"));
      if (Files.isRegularFile(path) && Files.size(path) >= MIN_REAL_IMAGE_BYTES) {
        return Files.readAllBytes(path);
      }
    } catch (IOException | RuntimeException e) {
      log.debug("seed-objects-dir: không đọc được {} : {}", key, e.toString());
    }
    return null;
  }

  // ── Tải ảnh thật ────────────────────────────────────────────────────────────────────────────

  /** Tải một ảnh, thử tối đa {@link #FETCH_ATTEMPTS} lần. Trả bytes hoặc {@code null}. */
  private byte[] fetch(HttpClient http, String url) {
    for (int attempt = 1; attempt <= FETCH_ATTEMPTS; attempt++) {
      byte[] data = fetchOnce(http, url);
      if (data != null) {
        return data;
      }
    }
    return null;
  }

  /** Một lượt tải. Không bao giờ ném ra ngoài. */
  private byte[] fetchOnce(HttpClient http, String url) {
    try {
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(url))
              .header("User-Agent", USER_AGENT)
              .timeout(FETCH_TIMEOUT)
              .GET()
              .build();
      HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
      if (response.statusCode() != 200) {
        return null;
      }
      byte[] body = response.body();
      return body != null && body.length >= MIN_REAL_IMAGE_BYTES ? body : null;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return null;
    } catch (Exception e) {
      return null;
    }
  }

  // ── Bộ sinh dự phòng ────────────────────────────────────────────────────────────────────────

  private byte[] placeholder(String key, int index) {
    int[] rgb = PALETTE[index % PALETTE.length];
    if (key.startsWith("posts/") || key.startsWith("covers-user/")) {
      return solidImage(640, 360, rgb, "png");
    }
    if (key.startsWith("avatars/")) {
      return solidImage(256, 256, rgb, "png");
    }
    if (key.startsWith("covers/")) {
      return solidImage(400, 560, rgb, "jpg");
    }
    if (key.endsWith(".epub")) {
      return minimalEpub(stemOf(key));
    }
    return minimalPdf(stemOf(key));
  }

  private byte[] solidImage(int width, int height, int[] rgb, String format) {
    BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = img.createGraphics();
    try {
      g.setColor(new Color(rgb[0], rgb[1], rgb[2]));
      g.fillRect(0, 0, width, height);
    } finally {
      g.dispose();
    }
    try {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      if (!ImageIO.write(img, format, out)) {
        throw new IOException("không có ImageIO writer cho định dạng " + format);
      }
      return out.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private byte[] minimalPdf(String title) {
    try (PDDocument doc = new PDDocument()) {
      PDPage page = new PDPage(PDRectangle.A4);
      doc.addPage(page);
      PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
      try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
        cs.beginText();
        cs.setFont(font, 16);
        cs.newLineAtOffset(60, 760);
        cs.showText(asciiSafe(title));
        cs.endText();
        cs.beginText();
        cs.setFont(font, 11);
        cs.newLineAtOffset(60, 730);
        cs.showText("File mau cho bo seed - khong phai noi dung that.");
        cs.endText();
      }
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      doc.save(out);
      return out.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private byte[] minimalEpub(String title) {
    try {
      Book book = new Book();
      book.getMetadata().addTitle(title);
      book.getMetadata().addIdentifier(new Identifier());
      String html =
          "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
              + "<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>"
              + escapeXml(title)
              + "</title></head><body><h1>"
              + escapeXml(title)
              + "</h1><p>File mẫu cho bộ seed — không phải nội dung thật.</p></body></html>\n";
      book.addSection(title, new Resource(html.getBytes(StandardCharsets.UTF_8), "chapter1.html"));
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      new EpubWriter().write(book, out);
      return out.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  // ── Tiện ích ────────────────────────────────────────────────────────────────────────────────

  private static String contentTypeOf(String key) {
    if (key.endsWith(".png")) {
      return "image/png";
    }
    if (key.endsWith(".jpg") || key.endsWith(".jpeg")) {
      return "image/jpeg";
    }
    if (key.endsWith(".pdf")) {
      return "application/pdf";
    }
    if (key.endsWith(".epub")) {
      return "application/epub+zip";
    }
    return "application/octet-stream";
  }

  private static String stemOf(String key) {
    String name = key.substring(key.lastIndexOf('/') + 1);
    int dot = name.lastIndexOf('.');
    if (dot > 0) {
      name = name.substring(0, dot);
    }
    return name.replace('-', ' ');
  }

  private static String asciiSafe(String s) {
    return s.replaceAll("[^\\x20-\\x7E]", "?");
  }

  private static String escapeXml(String s) {
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }
}
