-- =============================================================================================
-- Lộ trình kỹ năng và tiến độ xác minh của người dùng.
--
-- Id tường minh: lộ trình 2001-2005, nút 2101+. V60 tính điểm uy tín dựa trên các nút đã được
-- xác minh ở đây, nên hai file phải khớp id.
--
-- Cây nút dùng parent_node_id tự tham chiếu: nút gốc là chủ đề lớn (parent NULL), nút con là kỹ
-- năng cụ thể. order_index quyết định thứ tự hiển thị trong cùng một cấp.
-- =============================================================================================

INSERT INTO socialapp.t_roadmaps (id, name, description, created_at, updated_at) VALUES
    (2001, 'Backend Developer', 'Lộ trình từ người mới tới kỹ sư backend làm được việc trong đội sản phẩm.', now() - INTERVAL '200 days', now() - INTERVAL '200 days'),
    (2002, 'Frontend Developer', 'Lộ trình frontend hiện đại, tập trung vào React và hệ sinh thái xung quanh.', now() - INTERVAL '195 days', now() - INTERVAL '195 days'),
    (2003, 'DevOps Engineer', 'Từ đóng gói ứng dụng tới vận hành cụm và quan sát hệ thống.', now() - INTERVAL '190 days', now() - INTERVAL '190 days'),
    (2004, 'Data / ML Engineer', 'Đường đi từ xử lý dữ liệu tới đưa mô hình lên chạy thật.', now() - INTERVAL '185 days', now() - INTERVAL '185 days'),
    (2005, 'Security Engineer', 'Bảo mật ứng dụng nhìn từ phía người xây dựng hệ thống.', now() - INTERVAL '180 days', now() - INTERVAL '180 days');

-- ── Nút gốc (chủ đề lớn) ───────────────────────────────────────────────────────────────────
INSERT INTO socialapp.t_roadmap_nodes (id, roadmap_id, name, description, parent_node_id, order_index, created_at, updated_at) VALUES
    (2101, 2001, 'Nền tảng ngôn ngữ', 'Nắm chắc một ngôn ngữ và bộ công cụ đi kèm.', NULL, 0, now() - INTERVAL '200 days', now() - INTERVAL '200 days'),
    (2102, 2001, 'Cơ sở dữ liệu', 'Thiết kế bảng, viết truy vấn, và hiểu chi phí của chúng.', NULL, 1, now() - INTERVAL '200 days', now() - INTERVAL '200 days'),
    (2103, 2001, 'Thiết kế API', 'Định nghĩa giao diện mà người khác dùng được lâu dài.', NULL, 2, now() - INTERVAL '200 days', now() - INTERVAL '200 days'),
    (2104, 2001, 'Vận hành dịch vụ', 'Đưa dịch vụ lên chạy và giữ cho nó chạy được.', NULL, 3, now() - INTERVAL '200 days', now() - INTERVAL '200 days'),
    (2105, 2002, 'HTML, CSS, JavaScript', 'Ba nền tảng bắt buộc trước khi đụng tới framework.', NULL, 0, now() - INTERVAL '195 days', now() - INTERVAL '195 days'),
    (2106, 2002, 'React', 'Mô hình component, state, và vòng đời render.', NULL, 1, now() - INTERVAL '195 days', now() - INTERVAL '195 days'),
    (2107, 2002, 'Hiệu năng web', 'Đo và cải thiện trải nghiệm tải trang.', NULL, 2, now() - INTERVAL '195 days', now() - INTERVAL '195 days'),
    (2108, 2003, 'Đóng gói và triển khai', 'Container hoá ứng dụng và đưa lên môi trường chạy.', NULL, 0, now() - INTERVAL '190 days', now() - INTERVAL '190 days'),
    (2109, 2003, 'Hạ tầng bằng mã', 'Mô tả hạ tầng bằng file, không bằng thao tác tay.', NULL, 1, now() - INTERVAL '190 days', now() - INTERVAL '190 days'),
    (2110, 2003, 'Quan sát hệ thống', 'Log, chỉ số, và dấu vết phân tán.', NULL, 2, now() - INTERVAL '190 days', now() - INTERVAL '190 days'),
    (2111, 2004, 'Xử lý dữ liệu', 'Làm sạch và biến đổi dữ liệu ở quy mô lớn.', NULL, 0, now() - INTERVAL '185 days', now() - INTERVAL '185 days'),
    (2112, 2004, 'Mô hình hoá', 'Chọn và huấn luyện mô hình phù hợp bài toán.', NULL, 1, now() - INTERVAL '185 days', now() - INTERVAL '185 days'),
    (2113, 2004, 'Đưa mô hình lên production', 'Phục vụ mô hình và theo dõi chất lượng theo thời gian.', NULL, 2, now() - INTERVAL '185 days', now() - INTERVAL '185 days'),
    (2114, 2005, 'Nền tảng bảo mật', 'Mô hình đe doạ và các lớp phòng thủ cơ bản.', NULL, 0, now() - INTERVAL '180 days', now() - INTERVAL '180 days'),
    (2115, 2005, 'Bảo mật ứng dụng web', 'Các lỗ hổng phổ biến và cách phòng ngừa ngay trong code.', NULL, 1, now() - INTERVAL '180 days', now() - INTERVAL '180 days');

-- ── Nút lá (kỹ năng cụ thể) ────────────────────────────────────────────────────────────────
-- Đây mới là nơi người dùng khai nhận kỹ năng; nút gốc chỉ để nhóm lại cho dễ đọc.
INSERT INTO socialapp.t_roadmap_nodes (id, roadmap_id, name, description, parent_node_id, order_index, created_at, updated_at) VALUES
    (2201, 2001, 'Java Core', 'Collection, generic, luồng, và mô hình bộ nhớ.', 2101, 0, now() - INTERVAL '200 days', now() - INTERVAL '200 days'),
    (2202, 2001, 'Spring Boot', 'Dependency injection, cấu hình, và vòng đời ứng dụng.', 2101, 1, now() - INTERVAL '200 days', now() - INTERVAL '200 days'),
    (2203, 2001, 'Thiết kế schema quan hệ', 'Chuẩn hoá, khoá ngoại, và khi nào nên phá chuẩn.', 2102, 0, now() - INTERVAL '200 days', now() - INTERVAL '200 days'),
    (2204, 2001, 'Tối ưu truy vấn SQL', 'Đọc kế hoạch thực thi và đánh index đúng chỗ.', 2102, 1, now() - INTERVAL '200 days', now() - INTERVAL '200 days'),
    (2205, 2001, 'Giao dịch và mức cô lập', 'Hiểu điều gì xảy ra khi nhiều giao dịch chạy song song.', 2102, 2, now() - INTERVAL '200 days', now() - INTERVAL '200 days'),
    (2206, 2001, 'REST và quy ước đặt tên', 'Thiết kế tài nguyên và mã trạng thái cho nhất quán.', 2103, 0, now() - INTERVAL '200 days', now() - INTERVAL '200 days'),
    (2207, 2001, 'Xác thực và phân quyền', 'JWT, phiên đăng nhập, và kiểm soát truy cập theo vai trò.', 2103, 1, now() - INTERVAL '200 days', now() - INTERVAL '200 days'),
    (2208, 2001, 'Caching', 'Chọn chiến lược cache và xử lý việc làm mới dữ liệu.', 2104, 0, now() - INTERVAL '200 days', now() - INTERVAL '200 days'),
    (2209, 2001, 'Hàng đợi và xử lý bất đồng bộ', 'Tách việc nặng ra khỏi đường request.', 2104, 1, now() - INTERVAL '200 days', now() - INTERVAL '200 days'),
    (2210, 2002, 'TypeScript', 'Kiểu tĩnh cho JavaScript và cách dùng cho có ích.', 2105, 0, now() - INTERVAL '195 days', now() - INTERVAL '195 days'),
    (2211, 2002, 'CSS hiện đại', 'Flexbox, grid, và biến CSS.', 2105, 1, now() - INTERVAL '195 days', now() - INTERVAL '195 days'),
    (2212, 2002, 'React Hooks', 'Quản lý state và hiệu ứng phụ trong component hàm.', 2106, 0, now() - INTERVAL '195 days', now() - INTERVAL '195 days'),
    (2213, 2002, 'Quản lý state', 'Chọn giữa state cục bộ, context, và thư viện ngoài.', 2106, 1, now() - INTERVAL '195 days', now() - INTERVAL '195 days'),
    (2214, 2002, 'Server Components', 'Ranh giới server và client trong React hiện đại.', 2106, 2, now() - INTERVAL '195 days', now() - INTERVAL '195 days'),
    (2215, 2002, 'Core Web Vitals', 'Đo LCP, CLS, INP và cải thiện chúng.', 2107, 0, now() - INTERVAL '195 days', now() - INTERVAL '195 days'),
    (2216, 2003, 'Docker', 'Viết Dockerfile gọn và hiểu cơ chế lớp.', 2108, 0, now() - INTERVAL '190 days', now() - INTERVAL '190 days'),
    (2217, 2003, 'Kubernetes', 'Pod, service, ingress, và quản lý tài nguyên.', 2108, 1, now() - INTERVAL '190 days', now() - INTERVAL '190 days'),
    (2218, 2003, 'Terraform', 'Quản lý state và tổ chức module dùng chung.', 2109, 0, now() - INTERVAL '190 days', now() - INTERVAL '190 days'),
    (2219, 2003, 'CI/CD', 'Dựng pipeline chạy nhanh và đáng tin.', 2109, 1, now() - INTERVAL '190 days', now() - INTERVAL '190 days'),
    (2220, 2003, 'Prometheus và Grafana', 'Thu thập chỉ số và dựng cảnh báo có ích.', 2110, 0, now() - INTERVAL '190 days', now() - INTERVAL '190 days'),
    (2221, 2004, 'SQL cho phân tích', 'Truy vấn tổng hợp và hàm cửa sổ.', 2111, 0, now() - INTERVAL '185 days', now() - INTERVAL '185 days'),
    (2222, 2004, 'Pandas', 'Biến đổi dữ liệu dạng bảng trong Python.', 2111, 1, now() - INTERVAL '185 days', now() - INTERVAL '185 days'),
    (2223, 2004, 'Airflow', 'Điều phối pipeline chạy theo lịch.', 2111, 2, now() - INTERVAL '185 days', now() - INTERVAL '185 days'),
    (2224, 2004, 'Học có giám sát', 'Hồi quy, phân loại, và cách đánh giá cho đúng.', 2112, 0, now() - INTERVAL '185 days', now() - INTERVAL '185 days'),
    (2225, 2004, 'Theo dõi trôi dữ liệu', 'Phát hiện khi phân phối đầu vào đổi so với lúc huấn luyện.', 2113, 0, now() - INTERVAL '185 days', now() - INTERVAL '185 days'),
    (2226, 2005, 'Mô hình đe doạ', 'Xác định tài sản cần bảo vệ và đường tấn công.', 2114, 0, now() - INTERVAL '180 days', now() - INTERVAL '180 days'),
    (2227, 2005, 'Mật mã học ứng dụng', 'Băm, mã hoá, và ký số dùng đúng chỗ.', 2114, 1, now() - INTERVAL '180 days', now() - INTERVAL '180 days'),
    (2228, 2005, 'OWASP Top 10', 'Mười rủi ro phổ biến nhất và cách phòng trong code.', 2115, 0, now() - INTERVAL '180 days', now() - INTERVAL '180 days'),
    (2229, 2005, 'Bảo mật chuỗi cung ứng', 'Kiểm soát phụ thuộc và dựng lại được bản build.', 2115, 1, now() - INTERVAL '180 days', now() - INTERVAL '180 days');

-- ── Tiến độ xác minh kỹ năng ───────────────────────────────────────────────────────────────
-- UNIQUE (user_id, node_id): mỗi người một bản ghi cho một kỹ năng.
--
-- VerificationTier: SELF_VERIFIED (tự khai), MOD_VERIFIED (quản trị viên duyệt),
--                   QUIZ_VERIFIED (qua bài kiểm tra), AUTO_CERTIFIED (hệ thống tự cấp).
-- VerificationStatus: PENDING_APPROVAL, VERIFIED, REJECTED.
--
-- Ba ràng buộc logic được giữ đúng ở đây, vì dữ liệu mâu thuẫn sẽ làm hỏng cả màn hình hồ sơ
-- công khai lẫn hàng chờ duyệt của quản trị viên:
--   1. Chỉ dòng VERIFIED mới có verifier_id và verified_at.
--   2. Chỉ tier MOD_VERIFIED mới cần người duyệt; các tier khác không có verifier.
--   3. Người khai chỉ khai kỹ năng thuộc lộ trình khớp vai trò nghề nghiệp của họ.
--
-- proof_image_key để NULL: đó là object trong MinIO mà SQL không tạo được. Bằng chứng dùng
-- proof_url trỏ tới liên kết công khai, đúng như đa số người dùng thật sẽ nộp.
INSERT INTO socialapp.t_user_roadmap_progress
    (user_id, node_id, tier, status, proof_url, proof_image_key, verifier_id, verified_at, created_at, updated_at)
SELECT u.id,
       n.id,
       t.tier,
       t.status,
       CASE WHEN t.tier <> 'SELF_VERIFIED'
            THEN 'https://github.com/' || u.username || '/portfolio/blob/main/' ||
                 lower(replace(replace(n.name, ' ', '-'), '/', '-')) || '.md' END,
       NULL,
       CASE WHEN t.status = 'VERIFIED' AND t.tier = 'MOD_VERIFIED'
            THEN 9059 + (u.id % 2) END,
       CASE WHEN t.status = 'VERIFIED'
            THEN now() - ((u.id % 60) * INTERVAL '1 day') END,
       now() - ((u.id % 90 + 30) * INTERVAL '1 day'),
       now() - ((u.id % 60) * INTERVAL '1 day')
  FROM socialapp.t_user_professional_profiles prof
  JOIN socialapp.t_users u ON u.id = prof.user_id
  JOIN socialapp.t_roadmaps r
    ON r.id = CASE prof.primary_role
                  WHEN 'BACKEND'   THEN 2001
                  WHEN 'FULLSTACK' THEN 2001
                  WHEN 'FRONTEND'  THEN 2002
                  WHEN 'MOBILE'    THEN 2002
                  WHEN 'DEVOPS'    THEN 2003
                  WHEN 'QA'        THEN 2003
                  WHEN 'DATA_ML'   THEN 2004
                  WHEN 'SECURITY'  THEN 2005
                  ELSE NULL
              END
  JOIN socialapp.t_roadmap_nodes n
    ON n.roadmap_id = r.id
   AND n.parent_node_id IS NOT NULL
   -- Người nhiều kinh nghiệm khai nhiều kỹ năng hơn.
   AND ((u.id * 3 + n.id * 7) % 10) < LEAST(7, 2 + prof.years_of_experience / 2)
 CROSS JOIN LATERAL (
     SELECT (ARRAY['SELF_VERIFIED','SELF_VERIFIED','MOD_VERIFIED','MOD_VERIFIED',
                   'QUIZ_VERIFIED','AUTO_CERTIFIED','MOD_VERIFIED'])[1 + ((u.id + n.id * 5) % 7)] AS tier,
            (ARRAY['VERIFIED','VERIFIED','VERIFIED','VERIFIED',
                   'PENDING_APPROVAL','PENDING_APPROVAL','REJECTED'])[1 + ((u.id * 5 + n.id) % 7)] AS status
 ) t;

-- Ràng buộc 1 và 2 nói ở trên được áp lại một lần nữa ở đây thay vì chỉ tin vào biểu thức CASE
-- phía trên: hai mảng tier và status được chọn độc lập nhau, nên vẫn sinh ra được tổ hợp
-- "SELF_VERIFIED nhưng lại có người duyệt". Dọn lại cho sạch.
UPDATE socialapp.t_user_roadmap_progress
   SET verifier_id = NULL
 WHERE tier <> 'MOD_VERIFIED' OR status <> 'VERIFIED';

UPDATE socialapp.t_user_roadmap_progress
   SET verified_at = NULL
 WHERE status <> 'VERIFIED';

-- Dòng MOD_VERIFIED đã VERIFIED thì bắt buộc phải có người duyệt — không có thì hồ sơ hiện
-- "đã được kiểm chứng" mà không nói được ai kiểm chứng.
UPDATE socialapp.t_user_roadmap_progress
   SET verifier_id = 9059
 WHERE tier = 'MOD_VERIFIED' AND status = 'VERIFIED' AND verifier_id IS NULL;
