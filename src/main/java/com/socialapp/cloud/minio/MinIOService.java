package com.socialapp.cloud.minio;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.socialapp.common.exception.StorageException;

import io.minio.BucketExistsArgs;
import io.minio.ListObjectsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.Result;
import io.minio.SetBucketPolicyArgs;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.Item;
import lombok.RequiredArgsConstructor;

/**
 * Object storage, with MinIO's checked exceptions kept inside.
 *
 * <p><b>No method here declares {@code throws Exception}, and none should.</b> Every one of them
 * used to, which forced each caller into {@code catch (Exception e)} — the only clause wide enough
 * — and that is exactly what three of them did. A {@code catch} that wide swallows
 * {@code NullPointerException} and every other programming error alongside the storage failure it
 * meant to handle, reporting a bug in our own code to the user as "storage is having trouble".
 *
 * <p>The MinIO SDK throws eight or so checked types ({@code MinioException} and its subclasses,
 * plus {@code IOException}, {@code NoSuchAlgorithmException}, {@code InvalidKeyException}) and none
 * of them is separately actionable here: whichever arrives, the object was not written and the
 * caller's request cannot proceed. They are therefore caught once, at the boundary, and re-thrown
 * as {@link StorageException} — which {@code GlobalExceptionHandler} already maps to 503 with the
 * "downstream is having trouble, retry" meaning that is actually true.
 *
 * <p>Callers get an unchecked exception they can ignore, and their own {@code catch} blocks go
 * away rather than being widened.
 */
@Service
@RequiredArgsConstructor
public class MinIOService {

  private final MinioClient minioClient;

  /**
   * Stores an uploaded file under the content type the client declared.
   *
   * <p>Prefer {@link #uploadFile(String, String, MultipartFile, String)} for anything served
   * publicly: the declared type is whatever the caller put in the multipart part, and it becomes
   * the {@code Content-Type} the object is later served with.
   */
  public String uploadFile(String bucketName, String objectName, MultipartFile file) {
    return uploadFile(bucketName, objectName, file, file == null ? null : file.getContentType());
  }

  /**
   * Stores an uploaded file under a content type the caller has decided on.
   *
   * <p>For the public-read buckets, where the stored type is what a browser acts on. Passing a
   * value validated against an allow-list — rather than echoing the upload's own header back —
   * means a caller cannot choose how their object will later be served.
   */
  public String uploadFile(
      String bucketName, String objectName, MultipartFile file, String contentType) {
    if (file == null) {
      throw new IllegalArgumentException("file must not be null");
    }

    ensureBucketExists(bucketName);

    try (InputStream inputStream = file.getInputStream()) {
      minioClient.putObject(
          PutObjectArgs.builder().bucket(bucketName).object(objectName).stream(
                  inputStream, file.getSize(), -1)
              .contentType(contentType)
              .build());
    } catch (Exception e) {
      throw new StorageException("Could not store " + objectName, e);
    }

    return objectName;
  }

  /**
   * Stores bytes already in memory — for content this application generated rather than received,
   * such as a book preview sliced out of a PDF.
   *
   * <p>{@code contentType} is a parameter here rather than read off an upload, so the caller is
   * responsible for it being the truth about the bytes.
   */
  public String uploadBytes(String bucketName, String objectName, byte[] data, String contentType) {
    if (data == null) {
      throw new IllegalArgumentException("data must not be null");
    }

    ensureBucketExists(bucketName);

    try (InputStream inputStream = new ByteArrayInputStream(data)) {
      minioClient.putObject(
          PutObjectArgs.builder().bucket(bucketName).object(objectName).stream(
                  inputStream, data.length, -1)
              .contentType(contentType)
              .build());
    } catch (Exception e) {
      throw new StorageException("Could not store " + objectName, e);
    }

    return objectName;
  }

  /**
   * Makes every object in a bucket readable without credentials.
   *
   * <p>Called once per bucket at startup (see {@code MinIOBucketInitializer}), not per upload: it
   * is a bucket-administration call that writes the same policy every time, and it used to sit on
   * the hot path of both avatar and post-image uploads — ten times per request in the latter case.
   */
  public void ensurePublicReadPolicy(String bucketName) {
    String policy =
        """
                        {
                          "Version": "2012-10-17",
                          "Statement": [
                            {
                              "Effect": "Allow",
                              "Principal": "*",
                              "Action": ["s3:GetObject"],
                              "Resource": ["arn:aws:s3:::%s/*"]
                            }
                          ]
                        }
                        """
            .formatted(bucketName);

    try {
      minioClient.setBucketPolicy(
          SetBucketPolicyArgs.builder().bucket(bucketName).config(policy).build());
    } catch (Exception e) {
      throw new StorageException("Could not set the public-read policy on " + bucketName, e);
    }
  }

  public List<String> listAllFiles(String bucketName) {
    List<String> files = new ArrayList<>();

    try {
      Iterable<Result<Item>> results =
          minioClient.listObjects(
              ListObjectsArgs.builder().bucket(bucketName).recursive(true).build());

      for (Result<Item> result : results) {
        files.add(result.get().objectName());
      }
    } catch (Exception e) {
      throw new StorageException("Could not list the contents of " + bucketName, e);
    }

    return files;
  }

  /**
   * Whether an object is already present in a bucket.
   *
   * <p>For idempotent seeding: {@code MinIOSeedObjectInitializer} runs on every boot and must not
   * re-upload the ~1,100 seed objects that are already there. "Absent" is a normal answer, not a
   * failure — MinIO signals it with an {@link ErrorResponseException} whose code is
   * {@code NoSuchKey} (or a 404 on the HEAD), and that alone maps to {@code false}. Anything else
   * — the bucket unreachable, credentials wrong, a 5xx — is a real storage failure and becomes a
   * {@link StorageException} like every other method here.
   */
  public boolean objectExists(String bucketName, String objectName) {
    try {
      minioClient.statObject(
          StatObjectArgs.builder().bucket(bucketName).object(objectName).build());
      return true;
    } catch (ErrorResponseException e) {
      String code = e.errorResponse() == null ? null : e.errorResponse().code();
      boolean notFound =
          "NoSuchKey".equals(code)
              || "NoSuchObject".equals(code)
              || "NoSuchBucket".equals(code)
              || (e.response() != null && e.response().code() == 404);
      if (notFound) {
        return false;
      }
      throw new StorageException("Could not stat " + objectName + " in " + bucketName, e);
    } catch (Exception e) {
      throw new StorageException("Could not stat " + objectName + " in " + bucketName, e);
    }
  }

  /** Creates the bucket if it is not there yet. Safe to call repeatedly. */
  public void ensureBucketExists(String bucketName) {
    try {
      boolean exists =
          minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucketName).build());

      if (!exists) {
        minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucketName).build());
      }
    } catch (Exception e) {
      throw new StorageException("Could not create or reach the bucket " + bucketName, e);
    }
  }
}
