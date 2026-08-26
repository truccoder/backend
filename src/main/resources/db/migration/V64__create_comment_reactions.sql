-- =============================================================================================
-- Cảm xúc cho bình luận.
--
-- Trước file này, cảm xúc chỉ tồn tại ở cấp bài viết (t_post_reactions). Người dùng thả được cảm
-- xúc cho một bài nhưng không thả được cho câu trả lời hay nhất bên dưới nó — một bất đối xứng
-- nhìn thấy ngay trên màn hình. Hệ quả thứ hai kín hơn: không có gì để xếp hạng, nên phần xem
-- trước bình luận chỉ lấy được hai bình luận MỚI NHẤT chứ không phải hai bình luận NỔI NHẤT như
-- yêu cầu ban đầu.
--
-- Cấu trúc sao đúng t_post_reactions (V10): khoá chính ghép (user_id, comment_id) là thứ bảo đảm
-- một người chỉ có một cảm xúc trên một bình luận, nên đổi LIKE sang INSIGHT là UPDATE chứ không
-- phải hàng thứ hai. reaction_type dùng chung enum ReactionType với bài viết — không có bộ giá
-- trị riêng cho bình luận.
--
-- ON DELETE CASCADE ở cả hai khoá ngoại, và ở comment_id thì bắt buộc: t_comments tự tham chiếu
-- CASCADE nên xoá một bình luận gốc kéo theo cả nhánh trả lời; cảm xúc phải đi cùng, nếu không
-- khoá ngoại sẽ chặn đúng thao tác xoá đó.
-- =============================================================================================

CREATE TABLE IF NOT EXISTS socialapp.t_comment_reactions (
    user_id       INT REFERENCES socialapp.t_users(id) ON DELETE CASCADE,
    comment_id    INT REFERENCES socialapp.t_comments(id) ON DELETE CASCADE,
    reaction_type VARCHAR,
    created_at    TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, comment_id)
);

-- Đếm cảm xúc cho cả một luồng bình luận trong MỘT truy vấn (countByCommentIds). Không có index
-- này thì mỗi lần mở bình luận của một bài là một lần quét bảng.
CREATE INDEX IF NOT EXISTS idx_comment_reactions_comment_id
    ON socialapp.t_comment_reactions(comment_id);
