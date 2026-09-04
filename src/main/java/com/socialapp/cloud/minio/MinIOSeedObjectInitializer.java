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
import java.text.Normalizer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

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
 * <p><b>File sách có nội dung, không còn là trang trắng.</b> Mỗi PDF/EPUB của gian sách được dựng
 * bốn trang — bìa lót, mục lục, hai trang mở đầu chương một — từ {@code db/seed/book-previews.json}
 * đóng sẵn trong jar. File đó do {@code scripts/seed/crawl_book_previews.py} sinh lúc soạn: dữ kiện
 * thư mục (tiêu đề, tác giả, nhà xuất bản, năm, mục lục) lấy từ Open Library, văn xuôi do máy sinh.
 * Bản trước sinh đúng một trang A4 mang mỗi tên file, nên bấm "Xem thử" trên một quyển có bìa thật
 * lại mở ra một trang trắng.
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

  /** Nội dung bốn trang của từng quyển sách, dựng sẵn lúc soạn và đóng vào jar cùng manifest. */
  private static final String BOOK_PREVIEWS_PATH = "db/seed/book-previews.json";

  /**
   * 13 chữ số đầu tiên trong tên file là ISBN: {@code previews/9001/9780132350884-preview.pdf} và
   * {@code books/9001/9780132350884.epub} tra về cùng một quyển.
   */
  private static final Pattern ISBN_IN_KEY = Pattern.compile("(\\d{13})");

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

  /**
   * Hình trang của file sách sinh ra, sao chép đúng các con số của
   * {@code generate-seed-objects.py} để hai đường cho ra cùng một bố cục.
   */
  private static final float MARGIN_X = 64;

  private static final float TOP_Y = 770;
  private static final float BOTTOM_Y = 64;
  private static final float HEADING_SIZE = 16;
  private static final float BODY_SIZE = 11;
  private static final float LINE_HEIGHT = 15.5f;
  private static final float PARAGRAPH_GAP = 8;

  /** Bìa mỗi thứ một màu, sao chép đúng bảng của {@code generate-seed-objects.py}. */
  private static final int[][] PALETTE = {
    {37, 99, 235}, {5, 150, 105}, {219, 39, 119}, {217, 119, 6},
    {124, 58, 237}, {13, 148, 136}, {190, 24, 93}, {2, 132, 199},
  };

  private final MinIOService minIOService;

  /**
   * Nạp một lần ở lượt đồng bộ đầu tiên. Không dùng {@code @PostConstruct}: bước này chỉ chạy khi
   * {@code seed-objects-on-start} bật, còn bean thì luôn được tạo.
   */
  private volatile Map<String, BookDocument> bookPreviews;

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
      // File sách sinh ra cũng được coi là "ô màu" khi nó nhỏ hơn ngưỡng, dù manifest không khai
      // nguồn tải nào cho nó. Không có vế này thì mọi môi trường đã seed một lần vẫn giữ nguyên bản
      // PDF một trang trắng của thế hệ trước — bốn trang mới chỉ có ở một MinIO hoàn toàn trống.
      boolean placeholderInPlace =
          present
              && existingSize < MIN_REAL_IMAGE_BYTES
              && (row.sourceUrl() != null || bookOf(row.key()) != null);

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
        if (present && bookOf(row.key()) == null) {
          // Đã có một ô màu và lần này vẫn không lấy được ảnh thật — đừng ghi đè bằng ô màu khác.
          // File sách thì ngược lại: bản sinh ra ở đây LÀ nội dung đích, nên nó được ghi đè.
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
    BookDocument book = bookOf(key);
    List<BookPage> pages = book != null ? book.pages() : fallbackPages(stemOf(key));
    if (key.endsWith(".epub")) {
      return documentEpub(book != null ? book.title() : stemOf(key), pages);
    }
    return documentPdf(pages);
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

  // ── File sách: bốn trang dựng sẵn ───────────────────────────────────────────────────────────

  /** Một trang: tiêu đề (có thể rỗng ở trang tiếp nối) và các đoạn văn. */
  private record BookPage(String heading, List<String> paragraphs) {}

  /** Một quyển sách trong {@code book-previews.json}. */
  private record BookDocument(String title, List<BookPage> pages) {}

  /**
   * Bản ghi của quyển sách mà {@code key} trỏ tới, hoặc {@code null} nếu key không tra ra quyển nào.
   *
   * <p>Thiếu file {@code book-previews.json} hoặc file hỏng thì trả {@code null} cho MỌI key và
   * đường sinh rơi về một trang mẫu — chậm chân một nhịp còn hơn làm hỏng khởi động.
   */
  private BookDocument bookOf(String key) {
    // CHỈ FILE NỘI DUNG. Ảnh bìa nằm ở covers/<uploader>/<isbn>.jpg — cũng mang ISBN trong tên, nên
    // thiếu vế này thì mọi ảnh bìa bị coi là file sách và bị dựng lại thành PDF.
    if (!key.endsWith(".pdf") && !key.endsWith(".epub")) {
      return null;
    }
    Map<String, BookDocument> previews = bookPreviews;
    if (previews == null) {
      synchronized (this) {
        if (bookPreviews == null) {
          bookPreviews = readBookPreviews();
        }
        previews = bookPreviews;
      }
    }
    String name = key.substring(key.lastIndexOf('/') + 1);
    Matcher matcher = ISBN_IN_KEY.matcher(name);
    return matcher.find() ? previews.get(matcher.group(1)) : null;
  }

  private Map<String, BookDocument> readBookPreviews() {
    Map<String, BookDocument> out = new LinkedHashMap<>();
    try (InputStream in = new ClassPathResource(BOOK_PREVIEWS_PATH).getInputStream()) {
      JsonNode books = new ObjectMapper().readTree(in).path("books");
      books
          .properties()
          .forEach(
              entry -> {
                List<BookPage> pages = new ArrayList<>();
                for (JsonNode page : entry.getValue().path("pages")) {
                  List<String> paragraphs = new ArrayList<>();
                  page.path("paragraphs").forEach(par -> paragraphs.add(par.asText()));
                  pages.add(new BookPage(page.path("heading").asText(""), paragraphs));
                }
                if (!pages.isEmpty()) {
                  out.put(
                      entry.getKey(),
                      new BookDocument(entry.getValue().path("title").asText(""), pages));
                }
              });
      log.info("minio.seed-objects-on-start: {} có {} quyển sách", BOOK_PREVIEWS_PATH, out.size());
    } catch (IOException | RuntimeException e) {
      log.warn(
          "minio.seed-objects-on-start: không đọc được {} — file sách sẽ chỉ có một trang mẫu.",
          BOOK_PREVIEWS_PATH,
          e);
    }
    return out;
  }

  /**
   * Nội dung cho một file sách không tra ra quyển nào — manifest và {@code book-previews.json} đang
   * lệch nhau, chạy lại {@code scripts/seed/crawl_book_previews.py} rồi {@code generate_seed.py}.
   */
  private List<BookPage> fallbackPages(String stem) {
    return List.of(
        new BookPage(
            stem,
            List.of(
                "File mau cho bo seed - khong phai noi dung that.",
                "Khong tim thay quyen nay trong book-previews.json.")));
  }

  /**
   * PDF nhiều trang.
   *
   * <p><b>Tràn thì cắt, không đẩy sang trang mới</b>: số trang của file phải bằng đúng số phần tử
   * của {@code pages}, vì cột {@code t_books.preview_pages} bên bộ seed SQL được đặt bằng chính con
   * số ấy.
   */
  private byte[] documentPdf(List<BookPage> pages) {
    try (PDDocument doc = new PDDocument()) {
      PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
      float textWidth = PDRectangle.A4.getWidth() - 2 * MARGIN_X;
      for (BookPage page : pages) {
        PDPage pdPage = new PDPage(PDRectangle.A4);
        doc.addPage(pdPage);
        try (PDPageContentStream cs = new PDPageContentStream(doc, pdPage)) {
          float y = TOP_Y;
          String heading = asciiSafe(page.heading());
          if (!heading.isBlank()) {
            for (String line : wrap(heading, font, HEADING_SIZE, textWidth)) {
              write(cs, font, HEADING_SIZE, line, y);
              y -= HEADING_SIZE + 6;
            }
            y -= PARAGRAPH_GAP;
          }
          for (String paragraph : page.paragraphs()) {
            for (String line : wrap(asciiSafe(paragraph), font, BODY_SIZE, textWidth)) {
              if (y < BOTTOM_Y) {
                break;
              }
              write(cs, font, BODY_SIZE, line, y);
              y -= LINE_HEIGHT;
            }
            y -= PARAGRAPH_GAP;
          }
        }
      }
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      doc.save(out);
      return out.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private void write(PDPageContentStream cs, PDType1Font font, float size, String line, float y)
      throws IOException {
    cs.beginText();
    cs.setFont(font, size);
    cs.newLineAtOffset(MARGIN_X, y);
    cs.showText(line);
    cs.endText();
  }

  /** Ngắt dòng theo bề rộng ĐO ĐƯỢC của font, khác bên Python vốn phải ước lượng theo số ký tự. */
  private List<String> wrap(String text, PDType1Font font, float size, float width)
      throws IOException {
    List<String> lines = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    for (String word : text.split("\\s+")) {
      if (word.isEmpty()) {
        continue;
      }
      String candidate = current.isEmpty() ? word : current + " " + word;
      if (font.getStringWidth(candidate) / 1000 * size <= width) {
        current.setLength(0);
        current.append(candidate);
      } else {
        if (!current.isEmpty()) {
          lines.add(current.toString());
        }
        current.setLength(0);
        current.append(word);
      }
    }
    if (!current.isEmpty()) {
      lines.add(current.toString());
    }
    return lines.isEmpty() ? List.of("") : lines;
  }

  /** EPUB nhiều chương, mỗi trang một chương. */
  private byte[] documentEpub(String title, List<BookPage> pages) {
    try {
      Book book = new Book();
      book.getMetadata().addTitle(title);
      book.getMetadata().addIdentifier(new Identifier());
      int index = 0;
      for (BookPage page : pages) {
        index++;
        StringBuilder body = new StringBuilder();
        if (!page.heading().isBlank()) {
          body.append("<h1>").append(escapeXml(page.heading())).append("</h1>");
        }
        for (String paragraph : page.paragraphs()) {
          body.append("<p>").append(escapeXml(paragraph)).append("</p>");
        }
        String heading = page.heading().isBlank() ? title : page.heading();
        String html =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>"
                + escapeXml(heading)
                + "</title></head><body>"
                + body
                + "</body></html>\n";
        book.addSection(
            heading,
            new Resource(html.getBytes(StandardCharsets.UTF_8), "chapter" + index + ".html"));
      }
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

  /**
   * Bỏ dấu về ASCII. Helvetica chuẩn của PDF không có ký tự ngoài Latin-1, mà danh mục thì có
   * "Aurélien Géron" và mục lục thật có dấu nháy cong — bỏ dấu thì vẫn đọc được, để nguyên thì
   * PDFBox ném ngay ở {@code showText}. Bên {@code generate-seed-objects.py} làm cùng một việc.
   */
  private static String asciiSafe(String s) {
    String folded =
        Normalizer.normalize(s, Normalizer.Form.NFKD)
            .replaceAll("\\p{M}", "")
            .replace('\u2018', '\'')
            .replace('\u2019', '\'')
            .replace('\u201C', '"')
            .replace('\u201D', '"')
            .replace('\u2013', '-')
            .replace('\u2014', '-');
    return folded.replaceAll("[^\\x20-\\x7E]", "?");
  }

  private static String escapeXml(String s) {
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }
}
