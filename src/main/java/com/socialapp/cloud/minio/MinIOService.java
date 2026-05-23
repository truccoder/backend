package com.socialapp.cloud.minio;

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
import io.minio.messages.Item;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class MinIOService {

  private final MinioClient minioClient;

  public String uploadFile(String bucketName, String objectName, MultipartFile file)
      throws Exception {

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
