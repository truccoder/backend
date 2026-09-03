package com.socialapp.db;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * Chứng minh hai hash ghi cứng trong bộ seed thực sự mở được bằng hai mật khẩu mà tài liệu công
 * bố — bằng CHÍNH bộ mã hoá mà đường đăng nhập dùng, chứ không phải bằng một thư viện khác.
 *
 * <p>Vì sao đáng một lớp test riêng: hash sai không làm migration đỏ, không làm bất kỳ test nào
 * khác đỏ, và không có gì trong ứng dụng đọc nó cho tới khi một con người gõ mật khẩu vào ô đăng
 * nhập. Nghĩa là chỗ phát hiện tự nhiên của lỗi này là buổi bảo vệ đồ án.
 *
 * <p>BCrypt có ba tiền tố ({@code $2a$}, {@code $2b$}, {@code $2y$}) và các thư viện sinh ra
 * những tiền tố khác nhau. Test này cũng là chỗ khoá lại rằng tiền tố đang dùng là tiền tố mà
 * Spring Security đọc được.
 */
class SeedPasswordHashTest {

  /** Mật khẩu của 498 tài khoản thường, đúng như README của db/seed công bố. */
  private static final String USER_PASSWORD = "12qwaszx";

  private static final String USER_HASH =
      "$2a$10$ttfkq0.2lDTzWYQnxTotx.m50oiP/bhWDZta9cx1EcjfRmFZREhoK";

  /** Mật khẩu của hai tài khoản ADMIN (9499, 9500). */
  private static final String ADMIN_PASSWORD = "1234qwer";

  private static final String ADMIN_HASH =
      "$2a$10$u3fdshcpY2MWYbq.ZeDsseBNhaH23uPUI72ZRmufV2e.ufb8oDjZe";

  private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

  @Test
  @DisplayName("hash của tài khoản thường mở được bằng mật khẩu đã công bố")
  void userHashMatchesDocumentedPassword() {
    assertThat(encoder.matches(USER_PASSWORD, USER_HASH)).isTrue();
  }

  @Test
  @DisplayName("hash của ADMIN mở được bằng mật khẩu đã công bố")
  void adminHashMatchesDocumentedPassword() {
    assertThat(encoder.matches(ADMIN_PASSWORD, ADMIN_HASH)).isTrue();
  }

  @Test
  @DisplayName("hai mật khẩu KHÔNG dùng lẫn được cho nhau")
  void theTwoPasswordsAreNotInterchangeable() {
    // Nếu hai hash lỡ được sinh từ cùng một mật khẩu thì hai test trên vẫn xanh trong khi tài
    // khoản ADMIN thực chất dùng chung mật khẩu với 498 tài khoản demo — đúng thứ mà việc tách
    // hai hash ra sinh ra để tránh.
    assertThat(encoder.matches(ADMIN_PASSWORD, USER_HASH)).isFalse();
    assertThat(encoder.matches(USER_PASSWORD, ADMIN_HASH)).isFalse();
  }
}
