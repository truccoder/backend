-- =============================================================================================
-- Tách "người dùng đã sửa bài" ra khỏi @UpdateTimestamp — B28 trong docs/backend-plan.md.
--
-- updated_at bị Hibernate ghi lại bất cứ khi nào hàng t_posts đổi, kể cả những lần đổi không
-- phải do tác giả bấm Lưu: ModerationEventListener (async, sau khi Gemini/Cloud Vision trả kết
-- quả, ~1-2s sau INSERT) ghi đè moderation_status lên đúng hàng đó. FeedPostDataMapper từng dùng
-- một ngưỡng 1 giây để đoán "cách xa created_at đủ thì tính là sửa" — đoán sai với bài nào
-- moderation chạy chậm hơn 1 giây, và tự nó không thể đúng trong mọi trường hợp vì đang suy một
-- sự kiện nghiệp vụ (người dùng sửa) từ một tác dụng phụ hạ tầng (row bị UPDATE).
--
-- edited_at chỉ được PostService.updatePost ghi, không nơi nào khác. NULL nghĩa là chưa từng có
-- ai sửa nội dung — kể cả khi moderation đã ghi đè updated_at nhiều lần.
-- =============================================================================================

ALTER TABLE socialapp.t_posts ADD COLUMN IF NOT EXISTS edited_at TIMESTAMPTZ;
