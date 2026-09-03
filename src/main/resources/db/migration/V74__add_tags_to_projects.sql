-- Chủ đề của dự án, để gợi ý dự án theo `interested_domains` trong hồ sơ nghề nghiệp.
--
-- Trước cột này, thứ duy nhất mô tả nội dung một dự án là title/description dạng văn bản tự do.
-- Khớp `interested_domains` vào đó bằng ILIKE thì "AI" trúng mọi chỗ và "Growth" không trúng chỗ
-- nào — nên chủ đề được tách thành dữ liệu riêng, cùng kiểu jsonb với `required_skills` của
-- t_project_positions và `known_tech_stack` của hồ sơ, để ba bên so nhau bằng cùng một phép giao.
--
-- Cho phép NULL: mọi dự án đã tạo trước đây đều không có tags, và bắt buộc điền sẽ chặn cả
-- POST /v1/api/projects hiện tại lẫn 12 dự án seed. Điểm chủ đề của dự án không tags đơn giản là 0.
ALTER TABLE t_projects ADD COLUMN tags JSONB;

-- GIN là loại index dùng được cho toán tử chứa (@>) của jsonb; B-tree thì không.
-- Hiện phép giao chạy trong Java, nhưng nếu sau này lọc theo tag ngay trong SQL thì đây là thứ
-- giữ nó khỏi quét toàn bảng.
CREATE INDEX idx_projects_tags ON t_projects USING GIN (tags);
