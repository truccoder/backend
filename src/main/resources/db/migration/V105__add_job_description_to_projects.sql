-- =============================================================================================
-- Job description có cấu trúc cho từng vị trí tuyển.
--
-- Trước đây một vị trí chỉ có `title` + `description` văn bản tự do, nên "đăng dự án" và "đăng
-- tuyển" là cùng một ô nhập. Hệ quả nằm ở matchmaking: MatchmakingService chỉ có
-- `required_skills` để chấm, còn mức kinh nghiệm và phạm vi công việc thì nằm lẫn trong một đoạn
-- văn không ai đọc được bằng máy — người đủ trình lẫn người chưa đủ đều rơi vào cùng một danh
-- sách gợi ý.
--
-- Chia đôi theo đúng ranh giới của một JD thật:
--
--   t_projects            company_overview / company_culture  — nói về NƠI làm, viết một lần cho
--                                                               cả dự án, mọi vai dùng chung.
--   t_project_positions   role_summary / responsibilities /   — nói về VIỆC làm, mỗi vai một bản
--                         requirements / nice_to_have           riêng, không dùng chung được.
--
-- `min_years_experience` và `seniority_level` là hai cột duy nhất ở đây được matchmaking dùng làm
-- ĐIỀU KIỆN LOẠI chứ không phải điểm cộng (xem MatchmakingService): một vai ghi rõ cần 5 năm thì
-- gợi ý người 1 năm là sai, không phải là "khớp yếu".
--
-- jd_object_key + jd_rendered_at là bản PDF đã dựng của JD, nằm trong MinIO bucket
-- `job-descriptions` và phục vụ qua presigned URL — cùng đường đi với file xem thử của sách.
-- `jd_rendered_at` so với `updated_at` của cả vị trí lẫn dự án để biết bản PDF đã cũ chưa;
-- thiếu nó thì sửa JD xong người xem vẫn tải về bản in của tuần trước.
--
-- TẤT CẢ đều nullable và KHÔNG backfill. Ràng buộc "phải điền" nằm ở tầng DTO cho các vị trí tạo
-- mới / sửa lại (ProjectPositionRequestDTO), chứ không ở đây: 135 vị trí seed và mọi vị trí đã
-- tạo trước đây đều không có JD, và NOT NULL sẽ làm hỏng migrate thay vì làm chặt dữ liệu mới.
-- =============================================================================================

ALTER TABLE socialapp.t_projects
  ADD COLUMN IF NOT EXISTS company_overview TEXT,
  ADD COLUMN IF NOT EXISTS company_culture  TEXT;

ALTER TABLE socialapp.t_project_positions
  ADD COLUMN IF NOT EXISTS role_summary         TEXT,
  ADD COLUMN IF NOT EXISTS responsibilities     JSONB,
  ADD COLUMN IF NOT EXISTS requirements         JSONB,
  ADD COLUMN IF NOT EXISTS nice_to_have         JSONB,
  ADD COLUMN IF NOT EXISTS min_years_experience INTEGER,
  ADD COLUMN IF NOT EXISTS seniority_level      VARCHAR(50),
  ADD COLUMN IF NOT EXISTS jd_object_key        VARCHAR(500),
  ADD COLUMN IF NOT EXISTS jd_rendered_at       TIMESTAMP WITH TIME ZONE;

-- Số năm âm không có nghĩa, và 60 năm kinh nghiệm thì đã là lỗi nhập liệu chứ không phải yêu cầu.
-- Kiểm ở DB chứ không chỉ ở @Min/@Max của DTO vì cột này là ĐIỀU KIỆN LOẠI của matchmaking: một
-- hàng rác lọt vào bằng đường khác (seed, sửa tay) sẽ lặng lẽ làm rỗng danh sách ứng viên.
ALTER TABLE socialapp.t_project_positions
  ADD CONSTRAINT chk_project_positions_min_years
  CHECK (min_years_experience IS NULL OR (min_years_experience >= 0 AND min_years_experience <= 50));
