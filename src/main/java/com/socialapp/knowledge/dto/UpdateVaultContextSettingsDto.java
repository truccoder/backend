package com.socialapp.knowledge.dto;

import java.util.List;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Body for {@code PUT /v1/api/knowledge/vault/settings}.
 *
 * <p>A full replace, like the professional profile's PUT: the client sends both lists and gets both
 * lists back. Null on either side is read as an empty list rather than "leave it alone", so a
 * client cannot half-update a privacy control by omission.
 *
 * <p>Capped at 50 tags a side. A vault's distinct tag count is user-supplied and unbounded, and
 * these lists are read on every {@code /explain} call.
 */
@Data
public class UpdateVaultContextSettingsDto {

  @Size(max = 50, message = "At most 50 include tags")
  private List<String> includeTags;

  @Size(max = 50, message = "At most 50 exclude tags")
  private List<String> excludeTags;
}
