package com.socialapp.roadmap.dto;

import com.socialapp.common.enums.LearningCategory;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class RoadmapDto {
  private Integer id;

  @NotBlank(message = "Name is required")
  private String name;

  private String description;

  /**
   * Bỏ trống khi tạo thì lộ trình nhận {@code OTHER} — không phải lỗi 400.
   *
   * <p>Không đặt {@code @NotNull}: endpoint tạo lộ trình chỉ admin gọi được và đã có 5 lộ trình
   * tạo trước khi cột này tồn tại, nên bắt buộc điền sẽ biến một trường mới thành một thay đổi
   * phá vỡ hợp đồng cũ mà không đổi lại được gì. Một giá trị sai chính tả thì vẫn 400, do Jackson
   * từ chối chuỗi không khớp hằng số nào của {@link LearningCategory}.
   */
  private LearningCategory category;
}
