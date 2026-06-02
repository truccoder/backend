package com.socialapp.knowledge.dto;

import java.util.List;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

@Data
public class VaultPushRequestDto {
  @NotEmpty private List<VaultNoteDto> notes;
}
