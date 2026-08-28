-- =============================================================================================
-- Địa chỉ để bấm sang, cho những thông báo trỏ tới một BÌNH LUẬN.
--
-- t_notifications định vị đối tượng bằng một cặp (reference_type, reference_id). Với 'POST' thì
-- cặp đó đủ: client có route /posts/{id}. Với 'COMMENT' thì không — không route nào của frontend
-- nhận id bình luận, và một bình luận không tự nói nó nằm ở bài nào. Nên hai loại thông báo
-- USER_MENTIONED và COMMENT_LIKED đọc được, đánh dấu đã đọc được, và bấm vào thì đứng yên.
--
-- Với nhắc tên thì đó là mất gần hết công dụng: cả lý do tồn tại của loại thông báo ấy là "có
-- người gọi tên bạn Ở CHỖ KIA", mà chỗ kia không mở được.
--
-- ── Vì sao là một cột chứ không phải tra lúc đọc ────────────────────────────────────────────
-- NotificationService.getNotifications map cả một TRANG qua toDto và KHÔNG có @Transactional.
-- Tra bảng bình luận theo từng hàng ở đó là N+1 mở N transaction (open-in-view đang tắt), còn
-- gom lại thành một truy vấn batch thì bắt module notifications lần đầu tiên phải phụ thuộc vào
-- module posts. Cả hai chỗ PHÁT thông báo đều đã cầm sẵn id bài — CommentService nhận nguyên
-- PostEntity, CommentReactionService có comment.getPostId() là cột vô hướng — nên ghi lúc tạo
-- không tốn thêm một truy vấn nào ở bất kỳ đâu.
--
-- ── Nullable, không khoá ngoại ──────────────────────────────────────────────────────────────
-- Giữ đúng hình dạng của reference_id nằm ngay cạnh: cột INT trần. reference_type là chuỗi tự do
-- trỏ tới năm loại đối tượng khác nhau ('POST', 'COMMENT', 'FRIEND_REQUEST', 'BOOK',
-- 'ROADMAP_NODE'), nên không có bảng nào để một khoá ngoại chung trỏ tới. NULL là trạng thái
-- đúng của mọi thông báo không nói về một bài viết, không phải dữ liệu thiếu.
-- =============================================================================================

ALTER TABLE socialapp.t_notifications ADD COLUMN IF NOT EXISTS post_id INT;

-- Vá những thông báo đã nằm sẵn trong bảng. Không có bước này thì tính năng chỉ đúng với thông
-- báo sinh ra SAU lần deploy, còn mọi dòng cũ — gồm cả các hàng USER_MENTIONED do
-- db/seed/V70__seed_comment_mentions.sql đổ vào, thứ mà người đi demo sẽ bấm vào đầu tiên — vẫn
-- đứng yên đúng như trước. V72 > V70 nên thứ tự này chắc chắn.
UPDATE socialapp.t_notifications n
   SET post_id = c.post_id
  FROM socialapp.t_comments c
 WHERE n.reference_type = 'COMMENT'
   AND n.reference_id = c.id
   AND n.post_id IS NULL;
