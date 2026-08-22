-- =============================================================================================
-- Ghép nhóm làm dự án: dự án, các vị trí đang tuyển, và đơn ứng tuyển.
--
-- Id tường minh: dự án 4001-4012, vị trí 4101+. Đơn ứng tuyển để sequence tự cấp vì không file
-- nào tham chiếu tới một đơn cụ thể.
--
-- banner_url để NULL ở mọi dự án: cột này trỏ tới ảnh mà backend không có endpoint nào upload
-- (MinIO chỉ có bucket profile-pictures, books, book-covers), nên điền vào chỉ tạo ảnh vỡ.
-- =============================================================================================

INSERT INTO socialapp.t_projects (id, author_id, title, description, banner_url, status, created_at, updated_at) VALUES
    (4001, 9005, 'Nền tảng chia sẻ kiến thức nội bộ',
     'Xây công cụ để các đội tự viết và tìm lại tài liệu kỹ thuật. Dự án phi lợi nhuận, làm ngoài giờ, ưu tiên người muốn học kiến trúc.',
     NULL, 'OPEN', now() - INTERVAL '40 days', now() - INTERVAL '5 days'),
    (4002, 9018, 'Thư viện component tiếng Việt',
     'Bộ component React có sẵn xử lý tiếng Việt: sắp xếp có dấu, nhập liệu địa chỉ, định dạng tiền tệ.',
     NULL, 'OPEN', now() - INTERVAL '35 days', now() - INTERVAL '3 days'),
    (4003, 9033, 'Bộ công cụ theo dõi chi phí hạ tầng',
     'Gom hoá đơn từ nhiều nhà cung cấp về một chỗ và cảnh báo khi chi phí tăng bất thường.',
     NULL, 'OPEN', now() - INTERVAL '30 days', now() - INTERVAL '2 days'),
    (4004, 9039, 'Hệ thống gợi ý bài viết cho cộng đồng',
     'Thử nghiệm vài hướng gợi ý nội dung trên dữ liệu thật, so sánh kết quả một cách nghiêm túc.',
     NULL, 'OPEN', now() - INTERVAL '28 days', now() - INTERVAL '6 days'),
    (4005, 9026, 'Ứng dụng ghi chú offline-first',
     'Ghi chú hoạt động hoàn toàn khi mất mạng, đồng bộ lại khi có mạng mà không mất dữ liệu.',
     NULL, 'OPEN', now() - INTERVAL '25 days', now() - INTERVAL '4 days'),
    (4006, 9045, 'Công cụ quét cấu hình bảo mật',
     'Rà soát cấu hình hạ tầng theo một bộ quy tắc, xuất báo cáo nêu rõ rủi ro và cách khắc phục.',
     NULL, 'OPEN', now() - INTERVAL '22 days', now() - INTERVAL '7 days'),
    (4007, 9049, 'Khung kiểm thử tự động dùng chung',
     'Chuẩn hoá cách viết test end-to-end để các đội không mỗi nơi một kiểu.',
     NULL, 'OPEN', now() - INTERVAL '20 days', now() - INTERVAL '1 day'),
    (4008, 9021, 'Bảng điều khiển sức khoẻ dịch vụ',
     'Một trang duy nhất trả lời được câu hỏi: hệ thống đang ổn hay không, và nếu không thì chỗ nào.',
     NULL, 'OPEN', now() - INTERVAL '18 days', now() - INTERVAL '2 days'),
    (4009, 9010, 'Bộ sinh tài liệu API từ mã nguồn',
     'Sinh tài liệu và ví dụ gọi thử trực tiếp từ định nghĩa OpenAPI, luôn khớp với code đang chạy.',
     NULL, 'OPEN', now() - INTERVAL '15 days', now() - INTERVAL '3 days'),
    (4010, 9053, 'Ứng dụng quản lý mục tiêu cá nhân',
     'Theo dõi mục tiêu theo quý, nhắc nhở nhẹ nhàng thay vì gây áp lực.',
     NULL, 'OPEN', now() - INTERVAL '12 days', now() - INTERVAL '1 day'),
    -- Hai dự án đã đóng: đã tuyển đủ người và kết thúc tuyển dụng.
    (4011, 9001, 'Bộ chuyển đổi dữ liệu cũ sang hệ thống mới',
     'Đã hoàn thành đợt chuyển đổi. Giữ lại để tham khảo cách tổ chức công việc.',
     NULL, 'CLOSED', now() - INTERVAL '90 days', now() - INTERVAL '30 days'),
    (4012, 9035, 'Thư viện đo lường hiệu năng',
     'Dự án đã đạt mục tiêu ban đầu, hiện chỉ bảo trì, không tuyển thêm.',
     NULL, 'CLOSED', now() - INTERVAL '80 days', now() - INTERVAL '25 days');

-- ── Vị trí tuyển ───────────────────────────────────────────────────────────────────────────
-- Cột `version` là khoá lạc quan (V40). Ứng tuyển đồng thời vào cùng một vị trí sẽ tranh nhau
-- qua cột này, nên nó phải bắt đầu từ 0 chứ không được để NULL.
-- PositionStatus: OPEN, FILLED, CLOSED.
INSERT INTO socialapp.t_project_positions
    (id, project_id, title, description, required_skills, quantity, status, version, created_at, updated_at) VALUES
    (4101, 4001, 'Backend Developer', 'Thiết kế API và mô hình dữ liệu cho phần tài liệu.',
     '["Java","Spring Boot","PostgreSQL"]'::jsonb, 2, 'OPEN', 0, now() - INTERVAL '40 days', now() - INTERVAL '40 days'),
    (4102, 4001, 'Frontend Developer', 'Dựng giao diện soạn thảo và tìm kiếm tài liệu.',
     '["TypeScript","React","TailwindCSS"]'::jsonb, 1, 'OPEN', 0, now() - INTERVAL '40 days', now() - INTERVAL '40 days'),
    (4103, 4002, 'Frontend Developer', 'Viết component và tài liệu sử dụng kèm ví dụ.',
     '["TypeScript","React","Storybook"]'::jsonb, 3, 'OPEN', 0, now() - INTERVAL '35 days', now() - INTERVAL '35 days'),
    (4104, 4002, 'Product Designer', 'Thiết kế bộ giao diện nhất quán và tài liệu hướng dẫn dùng.',
     '["Figma","Design System"]'::jsonb, 1, 'FILLED', 0, now() - INTERVAL '35 days', now() - INTERVAL '10 days'),
    (4105, 4003, 'DevOps Engineer', 'Thu thập dữ liệu chi phí từ API của các nhà cung cấp.',
     '["Terraform","AWS","Python"]'::jsonb, 2, 'OPEN', 0, now() - INTERVAL '30 days', now() - INTERVAL '30 days'),
    (4106, 4004, 'Data Scientist', 'Thiết kế thí nghiệm và đánh giá chất lượng gợi ý.',
     '["Python","PyTorch","Pandas"]'::jsonb, 2, 'OPEN', 0, now() - INTERVAL '28 days', now() - INTERVAL '28 days'),
    (4107, 4004, 'Backend Developer', 'Dựng pipeline phục vụ mô hình theo thời gian thực.',
     '["Java","Kafka","Redis"]'::jsonb, 1, 'OPEN', 0, now() - INTERVAL '28 days', now() - INTERVAL '28 days'),
    (4108, 4005, 'Mobile Developer', 'Cài đặt lớp đồng bộ và xử lý xung đột dữ liệu.',
     '["Kotlin","Flutter","SQLite"]'::jsonb, 2, 'OPEN', 0, now() - INTERVAL '25 days', now() - INTERVAL '25 days'),
    (4109, 4006, 'Security Engineer', 'Viết bộ quy tắc rà soát và phân loại mức rủi ro.',
     '["Python","Semgrep","OWASP"]'::jsonb, 1, 'OPEN', 0, now() - INTERVAL '22 days', now() - INTERVAL '22 days'),
    (4110, 4007, 'Automation Engineer', 'Dựng khung test và tích hợp vào pipeline CI.',
     '["Playwright","TypeScript","GitHub Actions"]'::jsonb, 2, 'OPEN', 0, now() - INTERVAL '20 days', now() - INTERVAL '20 days'),
    (4111, 4008, 'Fullstack Developer', 'Làm cả phần thu thập chỉ số lẫn giao diện hiển thị.',
     '["TypeScript","Node.js","Grafana"]'::jsonb, 2, 'OPEN', 0, now() - INTERVAL '18 days', now() - INTERVAL '18 days'),
    (4112, 4009, 'Backend Developer', 'Sinh tài liệu từ định nghĩa OpenAPI và giữ đồng bộ với code.',
     '["Java","OpenAPI","Gradle"]'::jsonb, 1, 'OPEN', 0, now() - INTERVAL '15 days', now() - INTERVAL '15 days'),
    (4113, 4010, 'Mobile Developer', 'Dựng ứng dụng di động và cơ chế nhắc nhở.',
     '["Flutter","Dart","Firebase"]'::jsonb, 1, 'OPEN', 0, now() - INTERVAL '12 days', now() - INTERVAL '12 days'),
    (4114, 4010, 'Technical Writer', 'Viết tài liệu hướng dẫn và nội dung trong ứng dụng.',
     '["Viết kỹ thuật","Tiếng Anh"]'::jsonb, 1, 'OPEN', 0, now() - INTERVAL '12 days', now() - INTERVAL '12 days'),
    -- Vị trí thuộc dự án đã đóng.
    (4115, 4011, 'Backend Developer', 'Đã tuyển đủ, dự án kết thúc.',
     '["Java","PostgreSQL"]'::jsonb, 2, 'CLOSED', 0, now() - INTERVAL '90 days', now() - INTERVAL '30 days'),
    (4116, 4012, 'SRE', 'Đã hoàn thành, không tuyển thêm.',
     '["Prometheus","Go"]'::jsonb, 1, 'CLOSED', 0, now() - INTERVAL '80 days', now() - INTERVAL '25 days');

-- ── Đơn ứng tuyển ──────────────────────────────────────────────────────────────────────────
-- Ứng viên chỉ được chọn trong nhóm có vai trò khớp với vị trí: một người QA nộp đơn vào vị trí
-- Data Scientist là dữ liệu vô nghĩa, và sẽ làm hỏng mọi thử nghiệm về xếp hạng ứng viên.
--
-- ApplicationStatus: PENDING, ACCEPTED, REJECTED. Không ai nộp đơn vào dự án của chính mình.
INSERT INTO socialapp.t_project_applications
    (project_id, position_id, applicant_id, message, status, created_at, updated_at)
SELECT pos.project_id,
       pos.id,
       u.id,
       (ARRAY[
           'Mình quan tâm tới dự án và có kinh nghiệm ở đúng phần này. Rất mong được tham gia.',
           'Chào bạn, mình đọc mô tả thấy khớp với thứ mình đang làm hằng ngày. Mình gửi đơn ứng tuyển.',
           'Mình có thể dành khoảng 8 tiếng mỗi tuần cho dự án. Mong được trao đổi thêm.',
           'Mình từng làm phần tương tự ở công ty cũ và muốn tiếp tục với bài toán này.'
       ])[1 + ((u.id + pos.id) % 4)],
       (ARRAY['PENDING','PENDING','PENDING','ACCEPTED','REJECTED'])[1 + ((u.id * 3 + pos.id * 5) % 5)],
       pos.created_at + ((2 + (u.id % 15)) * INTERVAL '1 day'),
       pos.created_at + ((2 + (u.id % 15)) * INTERVAL '1 day')
  FROM socialapp.t_project_positions pos
  JOIN socialapp.t_projects pr ON pr.id = pos.project_id
  JOIN socialapp.t_user_professional_profiles prof
    ON prof.primary_role = CASE
           WHEN pos.title LIKE 'Backend%'    THEN 'BACKEND'
           WHEN pos.title LIKE 'Frontend%'   THEN 'FRONTEND'
           WHEN pos.title LIKE 'Fullstack%'  THEN 'FULLSTACK'
           WHEN pos.title LIKE 'Mobile%'     THEN 'MOBILE'
           WHEN pos.title IN ('DevOps Engineer', 'SRE') THEN 'DEVOPS'
           WHEN pos.title = 'Data Scientist' THEN 'DATA_ML'
           WHEN pos.title = 'Security Engineer' THEN 'SECURITY'
           WHEN pos.title = 'Automation Engineer' THEN 'QA'
           ELSE 'OTHER'
       END
  JOIN socialapp.t_users u ON u.id = prof.user_id
 WHERE pos.status = 'OPEN'
   AND pr.status = 'OPEN'
   AND u.id <> pr.author_id
   AND ((u.id * 7 + pos.id * 11) % 3) < 1;

-- Vị trí nào đã nhận đủ số người bằng `quantity` thì phải chuyển sang FILLED, nếu không giao
-- diện hiện "còn tuyển 2 người" trong khi cả hai suất đã có người nhận.
UPDATE socialapp.t_project_positions pos
   SET status = 'FILLED', updated_at = now()
  FROM (SELECT position_id, count(*) AS n
          FROM socialapp.t_project_applications
         WHERE status = 'ACCEPTED'
         GROUP BY position_id) a
 WHERE pos.id = a.position_id
   AND pos.status = 'OPEN'
   AND a.n >= pos.quantity;
