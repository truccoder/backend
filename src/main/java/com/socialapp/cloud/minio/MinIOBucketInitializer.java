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
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MinIOBucketInitializer {

  /**
   * Buckets served straight to browsers with no credentials: profile pictures and the images
   * attached to posts.
   *
   * <p>The book buckets are deliberately absent — book files and covers are private and reached
   * through presigned URLs, see {@code BookStorageService}.
   */
  private static final List<String> PUBLIC_READ_BUCKETS = List.of("profile-pictures", "post-media");

  private final MinIOService minIOService;

  @EventListener(ApplicationReadyEvent.class)
  public void prepareBuckets() {
    for (String bucket : PUBLIC_READ_BUCKETS) {
      try {
        minIOService.ensureBucketExists(bucket);
        minIOService.ensurePublicReadPolicy(bucket);
        log.info("MinIO bucket '{}' is ready and world-readable", bucket);
      } catch (Exception e) {
        log.error(
            "Could not prepare MinIO bucket '{}'. Uploads will still create it, but objects may"
                + " come back 403 until the public-read policy is applied.",
            bucket,
            e);
      }
    }
  }
}
