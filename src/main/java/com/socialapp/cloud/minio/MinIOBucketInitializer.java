package com.socialapp.cloud.minio;

import java.util.List;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Creates the buckets and sets their access policy once, at startup.
 *
 * <p>Both used to happen on every upload. {@code ensureBucketExists} is cheap enough to hide, but
 * {@code ensurePublicReadPolicy} is a bucket-administration round trip, and {@code MediaService}
 * called it once per file — ten of them for a ten-image post, each writing the identical policy
 * that was already in place, all inside the request. The avatar path did the same inside a database
 * transaction, holding a connection open for it.
 *
 * <p>A policy is a property of the bucket, not of an upload. Doing it here means the cost is paid
 * once per process rather than once per file, and the fact that two buckets are deliberately world
 * readable is stated in one place instead of being a side effect of an upload method.
 *
 * <p><b>Failure is logged, not fatal.</b> MinIO may legitimately not be up yet when this runs, and
 * an application that cannot boot because object storage is slow to start is worse than one whose
 * first upload creates the bucket itself — {@code MinIOService.uploadFile} still calls
 * {@code ensureBucketExists}, so the system repairs itself. What is lost on failure is the public
 * policy, which is why this logs at ERROR: images would upload and then 403 from inside an
 * {@code <img>} tag, which is confusing enough to be worth a loud line.
 *
 * <p><b>But it retries first.</b> "MinIO slow to start" is a transient, common state; giving up on
 * the very first attempt is what left {@code post-media} private on a production boot and every
 * seeded image 403'd until someone restarted the backend by hand. A few spaced attempts turn that
 * race into a non-event. The connection now goes to {@code minio.internal-url} (the Docker-network
 * address in prod), so this no longer waits on the public reverse proxy at all.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MinIOBucketInitializer {

  /**
   * Buckets served straight to browsers with no credentials: profile pictures and the images
   * attached to posts. These get a public-read policy on top of merely existing.
   */
  private static final List<String> PUBLIC_READ_BUCKETS = List.of("profile-pictures", "post-media");

  /**
   * Buckets that must EXIST but must stay private: book files and covers are reached through
   * presigned URLs, see {@code BookStorageService}.
   *
   * <p><b>Vì sao chúng phải nằm ở đây, dù không cần policy nào.</b> Trước đây lớp này chỉ chuẩn bị
   * hai bucket công khai, với lý lẽ rằng file sách là riêng tư. Lý lẽ ấy nói về CHÍNH SÁCH của
   * bucket và không nói gì về việc nó CÓ TỒN TẠI hay không — hai bucket sách được để cho
   * {@code MinIOService.uploadFile} tạo lười ở lần tải lên đầu tiên.
   *
   * <p>Hệ quả trên một môi trường chưa ai tải sách lên: {@code GET /v1/api/books} trả <b>503 ngay
   * ở hàng đầu tiên</b>. Ký một URL cần bucket tồn tại dù không cần object —
   * {@code BookStorageService.getPresignedUrl} hỏi region của bucket trước khi ký, lượt hỏi đó trả
   * về "The specified bucket does not exist", và {@code StorageException} làm hỏng cả trang thay vì
   * một quyển. ({@code getCoverUrl} bắt đúng ngoại lệ này và trả null; {@code getPreviewUrl} và
   * {@code getDownloadUrl} thì không.)
   *
   * <p>Ở máy dev, service {@code minio-init} trong docker-compose.yml đã tạo sẵn cả bốn bucket nên
   * lỗi này không bao giờ hiện ra. Production không có service đó — nó chạy compose của repo
   * DATN-infra, nơi không có bước tạo bucket nào — nên gian sách hỏng ở đúng nơi không ai thấy.
   * Tạo bucket ở đây là chỗ duy nhất đúng cho cả hai môi trường.
   *
   * <p>{@code job-descriptions} (V105) nằm cùng nhóm và vì đúng lý do đó: JD của một vị trí
   * được dựng thành PDF rồi phục vụ qua presigned URL, nên thiếu bucket thì mỗi lượt bấm
   * "xem mô tả công việc" trả 503 chứ không phải một tài liệu trống.
   */
  private static final List<String> PRIVATE_BUCKETS =
      List.of("books", "book-covers", "job-descriptions");

  /** How many times to re-try the whole bucket-preparation pass before logging the loud line. */
  private static final int MAX_ATTEMPTS = 6;

  /** Spacing between attempts. 6 × 5s covers a MinIO container that is up but not yet answering. */
  private static final long RETRY_DELAY_MILLIS = 5_000;

  private final MinIOService minIOService;

  /**
   * Spawns the preparation on a background thread. The retry loop below sleeps between attempts,
   * and every other {@code ApplicationReadyEvent} listener — seed-object upload, Neo4j, newsfeed —
   * would queue behind that if it ran on the publishing thread.
   */
  @EventListener(ApplicationReadyEvent.class)
  public void onApplicationReady() {
    Thread worker = new Thread(this::prepareBuckets, "minio-bucket-init");
    worker.setDaemon(true);
    worker.start();
  }

  /** Package-private so tests can drive it synchronously. */
  void prepareBuckets() {
    for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
      if (tryPrepare(attempt == MAX_ATTEMPTS)) {
        return;
      }
      try {
        Thread.sleep(RETRY_DELAY_MILLIS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }

  /**
   * One full pass. Returns {@code true} when every bucket is ready and every public policy is set.
   * On the last attempt ({@code loud}) a remaining failure is logged at ERROR; earlier failures are
   * logged at DEBUG because a retry is coming.
   */
  private boolean tryPrepare(boolean loud) {
    boolean allGood = true;

    for (String bucket : PUBLIC_READ_BUCKETS) {
      try {
        minIOService.ensureBucketExists(bucket);
        minIOService.ensurePublicReadPolicy(bucket);
        log.info("MinIO bucket '{}' is ready and world-readable", bucket);
      } catch (Exception e) {
        allGood = false;
        if (loud) {
          log.error(
              "Could not prepare MinIO bucket '{}'. Uploads will still create it, but objects may"
                  + " come back 403 until the public-read policy is applied.",
              bucket,
              e);
        } else {
          log.debug("MinIO bucket '{}' not ready yet, will retry: {}", bucket, e.toString());
        }
      }
    }

    for (String bucket : PRIVATE_BUCKETS) {
      try {
        minIOService.ensureBucketExists(bucket);
        log.info("MinIO bucket '{}' is ready (private, served through presigned URLs)", bucket);
      } catch (Exception e) {
        allGood = false;
        if (loud) {
          log.error(
              "Could not prepare MinIO bucket '{}'. GET /v1/api/books will return 503 until it"
                  + " exists, because signing a URL needs the bucket even when the object is absent.",
              bucket,
              e);
        } else {
          log.debug("MinIO bucket '{}' not ready yet, will retry: {}", bucket, e.toString());
        }
      }
    }

    return allGood;
  }
}
