-- =============================================================================================
-- Mở t_hashtags ra cho client đọc — B31 trong docs/backend-plan.md.
--
-- Bảng có từ V41, tới giờ chỉ được PostService.processHashtags ghi và SkillTagResolver đọc (tab
-- SKILLS của feed). Ba đường đọc mới:
--   GET /v1/api/hashtags/suggest   — khớp tiền tố tên, bỏ tag usage_count=0, xếp theo usage_count
--   GET /v1/api/hashtags/trending  — đếm bài PUBLIC/APPROVED trong cửa sổ thời gian, theo tag
--   GET /v1/api/posts/public?hashtag=  — lọc discovery feed theo một tag
--
-- Không có cột nào thêm; chỉ index cho ba truy vấn đó. IF NOT EXISTS để chạy lại không lỗi.
--
-- idx_hashtags_name_prefix: text_pattern_ops, không phải btree thường. LIKE 'abc%' chỉ dùng được
--   btree khi collation là C hoặc opclass là *_pattern_ops; nếu không, mỗi keystroke là seqscan
--   t_hashtags. Cùng lý do với các index trigram của V48, chỉ khác đây là tiền tố nên pattern_ops
--   đủ, không cần GIN.
--
-- idx_hashtags_usage_count: phục vụ vế lọc usage_count > 0 + ORDER BY usage_count DESC của suggest
--   khi một tiền tố ngắn khớp nhiều tag.
--
-- idx_post_hashtags_hashtag: khoá chính (post_id, hashtag_id) không phục vụ tra theo hashtag_id
--   một mình — cần cho GROUP BY của /trending và cho EXISTS của ?hashtag=.
--
-- idx_posts_created_at: /trending lọc t_posts theo created_at >= :since trước khi GROUP BY.
-- =============================================================================================

CREATE INDEX IF NOT EXISTS idx_hashtags_name_prefix
  ON socialapp.t_hashtags (name text_pattern_ops);

CREATE INDEX IF NOT EXISTS idx_hashtags_usage_count
  ON socialapp.t_hashtags (usage_count DESC);

CREATE INDEX IF NOT EXISTS idx_post_hashtags_hashtag
  ON socialapp.t_post_hashtags (hashtag_id);

CREATE INDEX IF NOT EXISTS idx_posts_created_at
  ON socialapp.t_posts (created_at DESC);
