-- =============================================================================================
-- Chủ đề của một bản giải thích AI, để FE dựng tab phân loại trên màn hình Kho lưu trữ.
--
-- ── Ai điền cột này ────────────────────────────────────────────────────────────────────────
-- Gemini, ngay trong lần sinh giải thích. Prompt của ExplanationService vốn đã yêu cầu trả về
-- một JSON có "concepts"/"prerequisites"/"complexityScore"/"externalLinks"; thêm một khoá
-- "category" vào đúng JSON đó không tốn thêm một lời gọi model nào.
--
-- Thứ duy nhất đã có để phân loại trước cột này là "concepts" — mảng chuỗi tự do model tự đặt
-- tên. Suy chủ đề từ đó ở phía FE nghĩa là mỗi lần giải thích lại một bài có thể rơi vào một tab
-- khác, vì hai lần chạy model không hứa dùng cùng một từ. Nhãn đóng, lưu một lần lúc sinh, thì
-- không có chuyện đó.
--
-- ── NOT NULL DEFAULT 'OTHER' ───────────────────────────────────────────────────────────────
-- Cùng lý lẽ với V76. Riêng ở đây còn một lý do nữa: parseGeminiResponse có nhánh dự phòng trả
-- về nguyên văn phản hồi khi JSON hỏng, và nhánh đó cũng phải sinh ra được một hàng hợp lệ —
-- OTHER là câu trả lời của nó.
--
-- Các bản giải thích đã lưu (seed V56, V67 và mọi bản người dùng đã bấm lưu) không được phân
-- loại ngược: nội dung của chúng do SQL sinh ra chứ không phải model, gọi Gemini để dán nhãn cho
-- dữ liệu demo là trả tiền thật cho một việc V79 làm được bằng một câu UPDATE.
-- =============================================================================================

ALTER TABLE socialapp.t_explanations
    ADD COLUMN category VARCHAR(32) NOT NULL DEFAULT 'OTHER';

-- Kho lưu trữ là danh sách của MỘT người, nên mọi truy vấn đều đã lọc user_id trước; index đặt
-- theo cặp để lần lọc theo tab sau này (nếu /my-library phải phân trang) không phải quét lại
-- toàn bộ kho của người đó.
CREATE INDEX idx_explanations_user_category
    ON socialapp.t_explanations (user_id, category);
