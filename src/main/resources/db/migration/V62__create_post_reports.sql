-- =============================================================================================
-- Báo cáo bài viết do người dùng gửi.
--
-- Trước file này, hàng chờ kiểm duyệt chỉ được nạp bởi AI: người dùng có thể KHIẾU NẠI một quyết
-- định (t_moderation_appeals) nhưng không có đường nào để đẩy nội dung VÀO hàng chờ. Chiều
-- ngược lại của cơ chế kiểm duyệt vốn không tồn tại.
--
-- UNIQUE (post_id, reporter_id) là phần quan trọng nhất của bảng: nó biến "số lượt báo cáo"
-- thành "số NGƯỜI báo cáo". Không có ràng buộc này, một người bấm nút mười lần sẽ vượt ngưỡng
-- leo thang một mình, và việc gỡ bài khỏi feed trở thành thứ ai cũng làm được.
--
-- ON DELETE CASCADE ở cả hai khoá ngoại: báo cáo không có ý nghĩa khi bài hoặc người báo cáo
-- không còn: khác với t_comments (giữ lại vì là nội dung của người khác), đây là siêu dữ liệu
-- vận hành.
-- =============================================================================================

CREATE SEQUENCE IF NOT EXISTS q_post_reports_id INCREMENT BY 1 MINVALUE 1 NO MAXVALUE START WITH 1 CACHE 10 NO CYCLE;

CREATE TABLE IF NOT EXISTS socialapp.t_post_reports (
    id          INT DEFAULT nextval('q_post_reports_id') PRIMARY KEY,
    post_id     INT NOT NULL REFERENCES socialapp.t_posts(id) ON DELETE CASCADE,
    reporter_id INT NOT NULL REFERENCES socialapp.t_users(id) ON DELETE CASCADE,
    reason      VARCHAR(50) NOT NULL,
    details     TEXT,
    created_at  TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_post_reports_post_reporter UNIQUE (post_id, reporter_id)
);

-- Đếm số người đã báo cáo một bài — truy vấn chạy sau MỖI lần báo cáo, để quyết định có leo
-- thang lên hàng chờ hay không.
CREATE INDEX IF NOT EXISTS idx_post_reports_post_id ON socialapp.t_post_reports(post_id);

-- Hàng chờ của quản trị viên đọc theo thứ tự mới nhất trước.
CREATE INDEX IF NOT EXISTS idx_post_reports_created_at ON socialapp.t_post_reports(created_at DESC);
