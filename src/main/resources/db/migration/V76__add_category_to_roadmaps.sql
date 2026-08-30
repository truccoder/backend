-- =============================================================================================
-- Chủ đề của một lộ trình, để FE dựng tab phân loại trên màn hình Lộ trình.
--
-- Giá trị lấy từ com.socialapp.common.enums.LearningCategory, dùng chung với sách (V78) và bản
-- giải thích AI (V77) — xem ghi chú trong enum đó về lý do một bảng chủ đề duy nhất cho cả ba.
--
-- ── Vì sao NOT NULL DEFAULT 'OTHER' chứ không để NULL ──────────────────────────────────────
-- Danh sách này do admin tạo và FE gom nhóm phía client (GET /v1/api/roadmaps trả về toàn bộ,
-- không phân trang). Một cột cho phép NULL buộc mọi chỗ gom nhóm phải xử lý thêm nhánh "không
-- có chủ đề" bên cạnh nhánh OTHER — hai cách viết cho cùng một ý. NOT NULL khiến chỉ còn một.
--
-- Khác với t_projects.tags ở V74, nơi NULL là bắt buộc vì đó là mảng jsonb nhiều giá trị do
-- người dùng nhập; ở đây là một giá trị đóng và luôn có câu trả lời mặc định đúng.
--
-- ── Vì sao không có index ──────────────────────────────────────────────────────────────────
-- Bảng có 5 hàng ở seed và sẽ ở lại quy mô hàng chục. Không có endpoint nào lọc theo cột này —
-- FE nhận cả danh sách rồi tự chia tab. Index ở đây chỉ tốn chi phí ghi. Sách thì ngược lại,
-- vì danh sách sách bị cắt trang ở server; xem V78.
-- =============================================================================================

ALTER TABLE socialapp.t_roadmaps
    ADD COLUMN category VARCHAR(32) NOT NULL DEFAULT 'OTHER';
