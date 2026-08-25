package com.socialapp.security.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The fields a user may change about themselves in one request.
 *
 * @param fullName required, and replaced outright — an absent name is rejected rather than treated
 *     as "leave it alone", because a profile with no name has nothing to render.
 * @param coverImageUrl the banner image. <b>Null means leave the existing cover alone; an empty
 *     string means remove it.</b> Not the same thing, and the distinction is the whole reason this
 *     field is not simply copied over.
 *     <p>That rule is not a preference, it is the lesson from {@code B12}: {@code
 *     PostService.updatePost} used {@code BeanUtils.copyProperties}, which copies nulls, so every
 *     client that sent back less than it received silently destroyed the fields it had omitted.
 *     This endpoint predates the cover field and every existing caller sends {@code fullName}
 *     alone — under a copy-nulls rule, the first one of those to run would wipe a cover the user
 *     had just set.
 *     <p><b>A URL, not a file.</b> There is no {@code PUT /profile/cover} multipart endpoint,
 *     deliberately: {@code POST /v1/api/media} already stores loose images and hands back a URL,
 *     so a cover is uploaded there and its URL saved here. A second multipart endpoint would be a
 *     third copy of the same upload-and-validate code — {@code MediaService} owns the size and
 *     content-type rules for both.
 */
public record UpdateProfileRequest(
    @NotBlank String fullName,

    /*
     * Validated even though this backend produced the value it expects. The field is a plain
     * String on the wire, so what actually arrives is whatever the caller sends, and it is
     * rendered straight into an image source on every profile page — "javascript:..." and
     * "data:text/html,..." are both valid Strings and neither belongs in that attribute. Bound to
     * http/https, with the empty string admitted as the "remove my cover" signal described above,
     * and capped at the column width so an over-long value fails as a 422 rather than as a
     * database error.
     */
    @Size(max = 512, message = "coverImageUrl must be at most 512 characters")
        @Pattern(
            regexp = "^$|^https?://.+",
            message = "coverImageUrl must be an http or https URL, or empty to remove the cover")
        String coverImageUrl) {}
