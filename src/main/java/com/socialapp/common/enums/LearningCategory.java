package com.socialapp.common.enums;

/**
 * Chủ đề của một nội dung học: sách trong Thư viện, lộ trình trong Lộ trình, và bản giải thích AI
 * trong Kho lưu trữ.
 *
 * <p><b>Vì sao một enum dùng chung thay vì ba enum riêng.</b> Ba màn hình này chứa cùng một loại
 * thứ — tài liệu để học một mảng nghề — và người dùng lọc chúng bằng cùng một câu hỏi ("phần
 * backend có gì?"). Ba enum riêng sẽ tạo ba danh sách nhãn tiếng Việt phải dịch ba lần ở FE, và
 * khoá luôn khả năng nối chéo ("sách liên quan tới lộ trình này") vì hai bên không so được với
 * nhau.
 *
 * <p><b>Vì sao tên trùng với {@code PrimaryRole}.</b> Bảy giá trị đầu là đúng bảy giá trị của
 * {@code knowledge.entity.enums.PrimaryRole} — vai trò người dùng tự khai trong hồ sơ nghề
 * nghiệp. Giữ tên khớp nhau để "gợi ý nội dung theo vai trò" sau này là một phép so tên, không
 * phải một bảng ánh xạ phải bảo trì. {@code FULLSTACK} của {@code PrimaryRole} không có ở đây:
 * một người có thể là fullstack, nhưng một cuốn sách thì nói về front hoặc về back, và một mục
 * "fullstack" trong bộ lọc chỉ tạo ra một tab không ai biết trong đó có gì.
 *
 * <p><b>Vì sao là enum chứ không phải bảng {@code t_categories}.</b> Không có màn hình quản trị
 * danh mục, và sẽ không có trong phạm vi đồ án. Một bảng chỉ để chứa chín hằng số đổi mỗi năm một
 * lần là thêm một join vào mọi truy vấn lọc để mua lấy một khả năng không ai dùng. Cùng lựa chọn
 * với {@code TrendingCategory}, vốn đã chạy như vậy từ V12.
 *
 * <p>Lưu ở DB dạng {@code VARCHAR} + {@code @Enumerated(EnumType.STRING)}: thêm giá trị mới không
 * cần migration, và một hàng đọc lên vẫn tự nói nó là gì.
 */
public enum LearningCategory {
  BACKEND,
  FRONTEND,
  MOBILE,
  DEVOPS,
  DATA_ML,
  SECURITY,
  QA,

  /** Kỹ năng nghề nghiệp không gắn với một mảng kỹ thuật: phỏng vấn, dẫn dắt đội, giao tiếp. */
  CAREER,

  /**
   * Giá trị mặc định, không phải giá trị lỗi. Mọi hàng có sẵn trước khi cột này tồn tại đều mang
   * {@code OTHER}, và mọi nhãn không đọc được từ model cũng rơi về đây — nên FE phải coi đây là
   * một tab thật có nội dung, không phải một trạng thái cần ẩn đi.
   */
  OTHER
}
