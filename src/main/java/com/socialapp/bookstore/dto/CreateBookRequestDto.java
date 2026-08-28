package com.socialapp.bookstore.dto;

import com.socialapp.common.enums.LearningCategory;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CreateBookRequestDto {
  @NotBlank private String title;

  private String description;

  @Min(0)
  private Long price;

  private Integer previewPages;

  /**
   * Bỏ trống thì sách vào {@code OTHER}.
   *
   * <p>Không {@code @NotNull}: DTO này là một phần của {@code CreatePostRequestDto} gửi dạng
   * multipart, và bắt buộc điền sẽ làm mọi client cũ không đăng được sách nữa — cái giá quá đắt
   * cho một trường mà giá trị mặc định đã đúng.
   */
  private LearningCategory category;
}
