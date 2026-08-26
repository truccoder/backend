-- =============================================================================================
-- Chủ đề cho 12 dự án seed (V74 thêm cột t_projects.tags).
--
-- UPDATE chứ không sửa V57__seed_projects.sql: file đó đã được apply ở mọi database hiện có, và
-- đổi nội dung một migration đã chạy là vỡ checksum (validate-on-migrate: true ở production).
--
-- Bộ từ vựng lấy ĐÚNG từ interested_domains trong V51__seed_users.sql, không bịa thêm từ mới.
-- Hai bên phải giao được nhau thì GET /v1/api/projects/suggested mới trả ra gì trên máy demo:
--
--   BACKEND   Distributed Systems / API Design / Database Internals
--   FRONTEND  Design Systems / Web Performance / Accessibility
--   FULLSTACK Developer Experience / API Design / Web Performance
--   MOBILE    Mobile UX / Offline First / Cross-platform
--   DEVOPS    Observability / Infrastructure as Code / Cost Optimization
--   DATA_ML   MLOps / Recommendation Systems / Data Quality
--   SECURITY  AppSec / Threat Modeling / Supply Chain Security
--   QA        Test Automation / Performance Testing / Quality Culture
--   (còn lại) Product Discovery / User Research / Growth
--
-- Mỗi dự án 2-3 chủ đề, chọn theo đúng mô tả và các vị trí đang tuyển của nó, chứ không rải đều:
-- một dự án gắn cả 9 chủ đề sẽ khớp với tất cả mọi người và biến gợi ý thành danh sách thường.
-- =============================================================================================

UPDATE socialapp.t_projects SET tags = v.tags
  FROM (VALUES
      -- Nền tảng chia sẻ kiến thức nội bộ — tuyển Backend + Frontend, dựng công cụ nội bộ.
      (4001, '["API Design","Developer Experience","Design Systems"]'::jsonb),
      -- Thư viện component tiếng Việt — Frontend + Product Designer.
      (4002, '["Design Systems","Accessibility","Web Performance"]'::jsonb),
      -- Bộ công cụ theo dõi chi phí hạ tầng — DevOps.
      (4003, '["Cost Optimization","Observability","Infrastructure as Code"]'::jsonb),
      -- Hệ thống gợi ý bài viết — Data Scientist + Backend.
      (4004, '["Recommendation Systems","MLOps","Data Quality"]'::jsonb),
      -- Ứng dụng ghi chú offline-first — Mobile.
      (4005, '["Offline First","Mobile UX","Cross-platform"]'::jsonb),
      -- Công cụ quét cấu hình bảo mật — Security.
      (4006, '["AppSec","Threat Modeling","Supply Chain Security"]'::jsonb),
      -- Khung kiểm thử tự động dùng chung — Automation Engineer (QA).
      (4007, '["Test Automation","Quality Culture","Developer Experience"]'::jsonb),
      -- Bảng điều khiển sức khoẻ dịch vụ — Fullstack.
      (4008, '["Observability","Web Performance","Developer Experience"]'::jsonb),
      -- Bộ sinh tài liệu API từ mã nguồn — Backend.
      (4009, '["API Design","Developer Experience","Distributed Systems"]'::jsonb),
      -- Ứng dụng quản lý mục tiêu cá nhân — Mobile + Technical Writer.
      (4010, '["Mobile UX","Product Discovery","User Research"]'::jsonb),
      -- Hai dự án CLOSED: vẫn gắn tags để dữ liệu đầy đủ, nhưng endpoint gợi ý lọc chúng ra
      -- theo status nên chúng không bao giờ xuất hiện — đó chính là điều cần kiểm chứng.
      (4011, '["Data Quality","Database Internals"]'::jsonb),
      (4012, '["Performance Testing","Observability"]'::jsonb)
  ) AS v(id, tags)
 WHERE socialapp.t_projects.id = v.id;
