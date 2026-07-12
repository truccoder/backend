package com.socialapp.cloud.minio;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import io.minio.BucketExistsArgs;
import io.minio.ListObjectsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.Result;
import io.minio.SetBucketPolicyArgs;
import io.minio.messages.Item;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class MinIOService {

  private final MinioClient minioClient;

  public String uploadFile(String bucketName, String objectName, MultipartFile file)
      throws Exception {

    if (file == null) {
      throw new IllegalArgumentException("file must not be null");
    }

    ensureBucketExists(bucketName);

    try (InputStream inputStream = file.getInputStream()) {

      minioClient.putObject(
          PutObjectArgs.builder().bucket(bucketName).object(objectName).stream(
                  inputStream, file.getSize(), -1)
              .contentType(file.getContentType())
              .build());
    }

    return objectName;
  }

  public String uploadBytes(String bucketName, String objectName, byte[] data, String contentType)
      throws Exception {

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
    }

    return objectName;
  }

  public void ensurePublicReadPolicy(String bucketName) throws Exception {
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

    minioClient.setBucketPolicy(
        SetBucketPolicyArgs.builder().bucket(bucketName).config(policy).build());
  }

  public List<String> listAllFiles(String bucketName) throws Exception {

    List<String> files = new ArrayList<>();

    Iterable<Result<Item>> results =
        minioClient.listObjects(
            ListObjectsArgs.builder().bucket(bucketName).recursive(true).build());

    for (Result<Item> result : results) {

      Item item = result.get();

      files.add(item.objectName());
    }

    return files;
  }

  private void ensureBucketExists(String bucketName) throws Exception {

    boolean exists =
        minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucketName).build());

    if (!exists) {

      minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucketName).build());
    }
  }
}
