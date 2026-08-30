-- Bearer secrets are stored as SHA-256 hashes from now on (see TokenHasher).
--
-- Vì sao phải xoá thay vì chuyển đổi: giá trị đang nằm trong bảng là bản THÔ, còn cột từ nay giữ
-- HASH. Không thể băm ngược, và cũng không thể băm xuôi rồi giữ nguyên ý nghĩa — nếu băm các dòng
-- cũ thì client vẫn cầm bản thô, gửi lên sẽ được băm lần nữa và không khớp. Dù làm cách nào thì
-- token cũ cũng ngừng hoạt động, nên xoá hẳn là cách trung thực nhất: không để lại dòng chết trong
-- bảng, và không để lại bí mật dùng được trong bản backup nào chụp sau thời điểm này.
--
-- Hệ quả với người dùng, nói rõ để không ai bất ngờ:
--   * access token (JWT) KHÔNG bị ảnh hưởng — nó không nằm trong CSDL, nên phiên đang mở vẫn chạy
--     cho tới khi hết hạn (15 phút).
--   * refresh token bị vô hiệu → mọi người phải đăng nhập lại một lần.
--   * link đặt lại mật khẩu / xác minh email / magic link đã gửi mà chưa dùng sẽ hỏng; người dùng
--     yêu cầu lại là có link mới.
--
-- Cột vẫn là VARCHAR(255), thừa sức chứa 64 ký tự hex, nên không cần đổi kiểu.

DELETE FROM socialapp.t_refresh_tokens;
DELETE FROM socialapp.t_password_reset_tokens;
DELETE FROM socialapp.t_email_verification_tokens;
DELETE FROM socialapp.t_magic_link_tokens;
