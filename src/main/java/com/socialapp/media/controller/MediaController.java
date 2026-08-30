package com.socialapp.media.controller;

import java.util.List;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.socialapp.media.dto.MediaUploadResponseDto;
import com.socialapp.media.service.MediaService;
import com.socialapp.security.util.SecurityUtils;

import lombok.RequiredArgsConstructor;

/**
 * Uploading images that are not attached to anything yet.
 *
 * <p>The one general entrance to storage, as opposed to the three that existed before it — each of
 * which stores into a specific field of a specific record ({@code /auth/register}, {@code
 * /posts/books}, {@code /profile/picture}). The composer needs the opposite: a URL first, and the
 * post it belongs to written afterwards, because the post does not exist while the author is still
 * choosing pictures for it.
 *
 * <p>Signed-in only, by the {@code .authenticated()} default in {@code SecurityConfig}. An
 * anonymous upload endpoint is free hosting for whatever anyone wants to host, attributable to
 * nobody — the {@code userId} in the object key is what makes an upload traceable back to an
 * account.
 */
@RestController
@RequestMapping("/v1/api/media")
@RequiredArgsConstructor
public class MediaController {

  private final MediaService mediaService;

  /**
   * @param files one or more images, all under the part name {@code files} — repeated rather than
   *     numbered, so a single-image upload and a batch are the same request shape.
   */
  @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public MediaUploadResponseDto upload(@RequestPart("files") List<MultipartFile> files) {
    return new MediaUploadResponseDto(mediaService.upload(SecurityUtils.getCurrentUserId(), files));
  }
}
