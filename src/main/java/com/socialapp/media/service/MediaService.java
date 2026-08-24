package com.socialapp.media.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.socialapp.cloud.minio.MinIOConfig;
import com.socialapp.cloud.minio.MinIOService;
import com.socialapp.common.exception.StorageException;
import com.socialapp.common.exception.ValidationException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Stores loose images and hands back URLs.
 *
 * <p>{@code CreatePostRequest}, {@code UpdatePostRequest} and {@code FeedPostDataDto} have all
 * carried {@code images} — a list of URLs — from the beginning, so a post with pictures needed
 * nothing new on the write path. What was missing was any way to <em>obtain</em> such a URL: the
 * only three multipart endpoints in the API were registration, book upload and the profile
 * picture, each writing to a field of its own. A poster could therefore only paste a link to an
 * image already hosted somewhere else, which is what the composer's hint strings admitted to.
 *
 * <p>Its own bucket rather than a folder inside {@code profile-pictures}: a bucket is the unit a
 * lifecycle or retention policy applies to here, and post images and avatars do not have the same
 * one — deleting a user's avatars should not reach into the pictures inside their posts.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MediaService {

  private static final String MEDIA_BUCKET = "post-media";

  /**
   * Content types accepted, and the extension each is stored under.
   *
   * <p>An allow-list, not a block-list, and the extension comes from <em>this table</em> rather
   * than from the uploaded filename. The bucket is served public-read, so the name an object is
   * stored under decides what a browser does when someone opens it directly; taking the suffix
   * from {@code file.getOriginalFilename()} the way the avatar path does would let a caller store
   * an object called {@code x.html}. GIF is here and absent from the avatar list on purpose — an
   * animated avatar is a distraction, an animated image inside a post is ordinary.
   */
  private static final Map<String, String> ALLOWED_TYPES =
      Map.of(
          "image/jpeg", "jpg",
          "image/png", "png",
          "image/webp", "webp",
          "image/gif", "gif");

  /**
   * How many files one request may carry.
   *
   * <p>The byte ceilings are left to {@code spring.servlet.multipart} (20MB per file, 25MB per
   * request), which is already configured and already enforced before this method runs — a second
   * size check here would be a second number to keep in step with it. A count limit is not
   * covered by those settings, though: 25MB of one-kilobyte files is thousands of round trips to
   * MinIO inside one request.
   */
  private static final int MAX_FILES = 10;

  private final MinIOService minIOService;
  private final MinIOConfig minIOConfig;

  /**
   * Validates every file before storing any of them, then uploads and returns their public URLs.
   *
   * <p>Validation runs as its own pass, deliberately. Doing it per file inside the upload loop
   * would leave the earlier files of a rejected request already written and unreferenced by
   * anything — orphans nothing will ever clean up, produced by a request that came back 400.
   */
  public List<String> upload(Integer userId, List<MultipartFile> files) {
    if (Objects.isNull(files) || files.isEmpty()) {
      throw new ValidationException("No files were uploaded");
    }
    if (files.size() > MAX_FILES) {
      throw new ValidationException("At most " + MAX_FILES + " files may be uploaded at once");
    }
    files.forEach(this::validate);

    List<String> urls = new ArrayList<>(files.size());
    for (MultipartFile file : files) {
      urls.add(store(userId, file));
    }
    return urls;
  }

  private String store(Integer userId, MultipartFile file) {
    String extension = ALLOWED_TYPES.get(normalisedContentType(file));
    String objectKey = "posts/" + userId + "/" + UUID.randomUUID() + "." + extension;

    try {
      minIOService.uploadFile(MEDIA_BUCKET, objectKey, file);
      // Same call the avatar path makes, and for the same reason: the URL below is handed to a
      // browser that presents no credentials, so an object in a default-private bucket would come
      // back 403 from inside an <img> tag with nothing to explain it.
      minIOService.ensurePublicReadPolicy(MEDIA_BUCKET);
    } catch (Exception e) {
      throw new StorageException("Failed to upload image", e);
    }

    return minIOConfig.getUrl() + "/" + MEDIA_BUCKET + "/" + objectKey;
  }

  private void validate(MultipartFile file) {
    if (Objects.isNull(file) || file.isEmpty()) {
      throw new ValidationException("File is empty");
    }
    if (!ALLOWED_TYPES.containsKey(normalisedContentType(file))) {
      throw new ValidationException("Only JPEG, PNG, WEBP or GIF images are allowed");
    }
  }

  /**
   * The declared content type, lower-cased and stripped of any {@code ;charset=…} parameter.
   *
   * <p>Declared, not detected — this is what the client said, and a client can say anything. It is
   * not load-bearing for safety on its own: what actually decides how the stored object is served
   * is the extension, which {@link #ALLOWED_TYPES} maps from this value, so the worst a lying
   * caller achieves is a PNG-named object that is not a PNG.
   */
  private String normalisedContentType(MultipartFile file) {
    String contentType = file.getContentType();
    if (Objects.isNull(contentType)) {
      return "";
    }
    int parameterStart = contentType.indexOf(';');
    return (parameterStart < 0 ? contentType : contentType.substring(0, parameterStart))
        .trim()
        .toLowerCase();
  }
}
