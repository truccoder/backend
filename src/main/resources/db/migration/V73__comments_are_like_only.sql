-- =============================================================================================
-- Bình luận chỉ được "thích".
--
-- Luật do chủ dự án chốt ngày 25/08, và trước file này nó chỉ tồn tại ở phía client: nút cảm xúc
-- của bình luận không có khay bảy glyph như bài viết, nó chỉ gửi LIKE. Nhưng đó là kỷ luật của
-- MỘT client — API vẫn nhận đủ bảy giá trị, nên bất kỳ ai gọi thẳng endpoint cũng ghi được CLAP
-- vào một bình luận. CommentReactionService.upsertReaction giờ trả 400 cho sáu giá trị còn lại;
-- file này dọn những hàng đã kịp ghi trước đó.
--
-- ── Vì sao UPDATE chứ không DELETE ─────────────────────────────────────────────────────────
-- db/seed/V65 đổ 11 hàng vào t_comment_reactions, trong đó 7 hàng không phải LIKE, và số lượt cố
-- ý lệch nhau (5 / 3 / 1 / 1 / 1) vì đó là thứ duy nhất để kiểm "hai bình luận nổi nhất" — xếp
-- hạng bằng một cột mà mọi hàng bằng nhau thì không kiểm được gì. Xoá 7 hàng kia là san phẳng
-- phân bố đó về 1/1/1/1/1 và giết SeedMigrationTest.shouldGiveCommentsUnequalReactionCounts.
-- Đổi loại thì giữ nguyên cả 11 hàng lẫn phân bố.
--
-- Khoá chính là (user_id, comment_id) — xem V64 — nên một người chỉ có một hàng trên một bình
-- luận và UPDATE không thể tạo trùng: 11 hàng vào, 11 hàng ra.
--
-- ── Vì sao không sửa thẳng V65 ─────────────────────────────────────────────────────────────
-- db/seed chạy cả trên production từ 21/08, application-prod.yml bật validate-on-migrate, và
-- Flyway tính checksum trên toàn bộ nội dung file — thêm 5 dòng ghi chú vào V61 (commit d6f6dd1)
-- đã từng làm production không khởi động được. Quy ước ở db/seed/README.md: buộc phải sửa nội
-- dung thì thêm file version mới. V73 đứng sau V65 trong dãy version dùng chung, nên một lần
-- seed lại từ đầu vẫn kết thúc ở trạng thái sạch.
--
-- ── Vì sao không thêm CHECK constraint ─────────────────────────────────────────────────────
-- Cột reaction_type dùng chung enum với bài viết, nơi cả bảy giá trị vẫn hợp lệ. Ghim
-- CHECK (reaction_type = 'LIKE') sẽ khoá luật ở tầng thấp nhất, nhưng cũng làm mọi truy vấn lọc
-- theo loại của bảng này mất hết ý nghĩa và buộc phải viết lại các bài kiểm thử repository vốn
-- đang kiểm đúng hành vi của cột. Luật được chặn ở đúng một cửa vào — tầng service — và cửa đó
-- có bài kiểm thử riêng.
-- =============================================================================================

UPDATE socialapp.t_comment_reactions
   SET reaction_type = 'LIKE'
 WHERE reaction_type <> 'LIKE'
    OR reaction_type IS NULL;
