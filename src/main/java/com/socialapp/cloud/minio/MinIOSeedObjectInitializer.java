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
 * <p>Manifest ({@code db/seed/seed-manifest.tsv}) vì vậy nằm trong {@code src/main/resources} —
 * cùng chỗ với các file SQL mà nó phải khớp, sinh cùng một lần chạy bởi
 * {@code scripts/seed/generate_seed.py}, và được đóng vào jar nên không cần mount gì. Đây là port
 * Java của {@code generate-seed-objects.py}: đọc manifest, thử tải ảnh thật ở URL nguồn, mất mạng
 * hay 404 thì rơi về bộ sinh PNG/JPEG/PDF/EPUB bên dưới.
 *
 * <p><b>Mặc định TẮT</b>, cùng lý do với hai initializer kia: chỉ bật ở môi trường thực sự muốn
 * nạp seed. {@code application-prod.yml} bật nó; máy dev để {@code docker-compose} lo nên cờ vẫn
 * tắt. Cờ là điều kiện DUY NHẤT.
 *
 * <p><b>Chạy nền, không chặn khởi động.</b> Lần đầu trên một MinIO trắng phải tải ~980 ảnh — vài
 * phút, và mọi listener của {@code ApplicationReadyEvent} chạy TRƯỚC khi Spring đánh dấu
 * "sẵn sàng nhận traffic". Chặn ở đó nghĩa là healthcheck báo đỏ suốt ngần ấy phút. Vì vậy listener
 * chỉ khởi một luồng nền rồi trả về ngay. Các lần khởi động sau chỉ là ~1.100 lượt {@code statObject}
 * (đã có thì bỏ qua) — vẫn chạy, vẫn nền, vài chục giây.
 *
 * <p><b>Idempotent.</b> Mỗi object kiểm {@code MinIOService.objectExists} trước; đã có thì bỏ qua.
 * Hai key {@code khong-ton-tai} (fixture "ảnh vỡ" / "kho hỏng" mà frontend đã dịch sẵn thông điệp)
 * cố ý KHÔNG nằm trong manifest nên không bao giờ được tạo.
 *
 * <p><b>Không bao giờ làm hỏng khởi động.</b> Mọi lỗi được ghi log rồi bỏ qua; một ảnh hỏng chỉ
 * rơi về bản dự phòng.
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
   * Ảnh tải về nhỏ hơn ngưỡng này bị coi là hỏng. Open Library trả HTTP 200 kèm một ảnh 1x1 khi
   * không có bìa cho ISBN đó, nên chỉ kiểm mã trạng thái là không đủ.
   */
  private static final int MIN_REAL_IMAGE_BYTES = 2000;

  private static final Duration FETCH_TIMEOUT = Duration.ofSeconds(8);

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
   * và tất định; production để {@code true}.
   */
  @Value("${minio.seed-objects-fetch-remote:true}")
  private boolean fetchRemote;

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

    AtomicInteger uploaded = new AtomicInteger();
    AtomicInteger real = new AtomicInteger();
    AtomicInteger generated = new AtomicInteger();
    AtomicInteger skipped = new AtomicInteger();
    AtomicInteger failed = new AtomicInteger();
    AtomicInteger unknownPrefix = new AtomicInteger();

    HttpClient http =
        HttpClient.newBuilder()
            .connectTimeout(FETCH_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    log.info(
        "minio.seed-objects-on-start: {} object trong manifest{}",
        rows.size(),
        fetchRemote ? "" : " (chế độ chỉ-placeholder)");

    ExecutorService pool = Executors.newFixedThreadPool(FETCH_WORKERS);
    try {
      List<Future<?>> futures = new ArrayList<>(rows.size());
      for (int i = 0; i < rows.size(); i++) {
        int index = i;
        ManifestRow row = rows.get(i);
        futures.add(
            pool.submit(
                () ->
                    processRow(
                        index,
                        row,
                        http,
                        uploaded,
                        real,
                        generated,
                        skipped,
                        failed,
                        unknownPrefix)));
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
        "minio.seed-objects-on-start: xong — tải lên {} ({} ảnh thật, {} ảnh dự phòng), bỏ qua {}"
            + " đã có, {} lỗi",
        uploaded.get(),
        real.get(),
        generated.get(),
        skipped.get(),
        failed.get());
    if (unknownPrefix.get() > 0) {
      log.error(
          "minio.seed-objects-on-start: {} object có prefix chưa khai trong BUCKET_OF_PREFIX —"
              + " những ảnh đó vẫn 404.",
          unknownPrefix.get());
    }
  }

  private void processRow(
      int index,
      ManifestRow row,
      HttpClient http,
      AtomicInteger uploaded,
      AtomicInteger real,
      AtomicInteger generated,
      AtomicInteger skipped,
      AtomicInteger failed,
      AtomicInteger unknownPrefix) {
    String prefix = row.key().contains("/") ? row.key().substring(0, row.key().indexOf('/')) : "";
    String bucket = BUCKET_OF_PREFIX.get(prefix);
    if (bucket == null) {
      unknownPrefix.incrementAndGet();
      log.error("minio.seed-objects-on-start: prefix '{}' chưa map, bỏ qua {}", prefix, row.key());
      return;
    }

    try {
      if (minIOService.objectExists(bucket, row.key())) {
        skipped.incrementAndGet();
        return;
      }

      byte[] data = null;
      if (fetchRemote && row.sourceUrl() != null) {
        data = fetch(http, row.sourceUrl());
      }
      boolean wasReal = data != null;
      if (!wasReal) {
        data = placeholder(row.key(), index);
      }

      minIOService.uploadBytes(bucket, row.key(), data, contentTypeOf(row.key()));
      uploaded.incrementAndGet();
      (wasReal ? real : generated).incrementAndGet();
    } catch (RuntimeException e) {
      failed.incrementAndGet();
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

  // ── Tải ảnh thật ────────────────────────────────────────────────────────────────────────────

  /** Tải một ảnh, trả bytes hoặc {@code null}. Không bao giờ ném ra ngoài. */
  private byte[] fetch(HttpClient http, String url) {
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
