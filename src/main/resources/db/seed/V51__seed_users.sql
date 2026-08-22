-- =============================================================================================
-- 60 tài khoản dev, id 9001-9060, kèm hồ sơ nghề nghiệp và tuỳ chọn thông báo.
--
-- MẬT KHẨU CHUNG: 12345678   (hash BCrypt strength 10, sinh bằng chính BCryptPasswordEncoder
--                             của app — cùng giá trị đã dùng và kiểm chứng ở seed thế hệ trước)
-- Riêng 9059/9060 là ADMIN, cùng mật khẩu.
--
-- Email dùng TLD `.test` — RFC 2606 dành riêng cho mục đích thử nghiệm, bảo đảm không định
-- tuyến được và không ai đăng ký được. Thế hệ seed trước dùng `@test.com` và `@socialapp.com`,
-- cả hai đều là tên miền CÓ THẬT do người khác sở hữu: ai kiểm soát hòm thư ở đó có thể bấm
-- "quên mật khẩu" và chiếm tài khoản nếu bộ seed lọt lên môi trường thật.
--
-- Dải id 9001-9060 cố định và được tham chiếu trực tiếp bởi:
--   - docker/neo4j/seed/friend-graph.cypher (đồ thị bạn bè — Neo4j mới là nguồn sự thật)
--   - mọi file V52-V61 trong thư mục này
-- Đổi dải id ở đây là phải đổi đồng bộ những chỗ trên.
--
-- Cụm vai trò (khớp enum PrimaryRole, và khớp phân cụm trong friend-graph.cypher):
--   BACKEND  9001-9010    FRONTEND 9011-9018    FULLSTACK 9019-9024
--   MOBILE   9025-9030    DEVOPS   9031-9036    DATA_ML   9037-9042
--   SECURITY 9043-9046    QA       9047-9052    OTHER     9053-9058
--   ADMIN    9059-9060
-- =============================================================================================

-- Bảng tạm giữ toàn bộ hồ sơ một người ở đúng MỘT chỗ, rồi rót ra ba bảng thật. Nếu tách thành
-- ba danh sách VALUES riêng thì tên và id sẽ bị lặp ba lần và chỉ cần lệch một dòng là hồ sơ
-- nghề nghiệp gắn nhầm người — kiểu sai rất khó nhìn ra khi đọc review.
CREATE TEMPORARY TABLE tmp_seed_people (
    id             INT PRIMARY KEY,
    username       TEXT NOT NULL,
    full_name      TEXT NOT NULL,
    user_role      TEXT NOT NULL,
    primary_role   TEXT,
    job_title      TEXT,
    seniority      TEXT,
    years_exp      INT,
    email_verified BOOLEAN NOT NULL,
    auth_provider  TEXT NOT NULL,
    expl_style     TEXT
);

INSERT INTO tmp_seed_people VALUES
    -- ── BACKEND ────────────────────────────────────────────────────────────────────────────
    (9001, 'backend_truc_anh',      'Nguyễn Trúc Anh',    'USER', 'BACKEND',  'Backend Developer',     'SENIOR',    6, TRUE,  'LOCAL',  'DETAILED'),
    (9002, 'backend_khoi_nguyen',   'Trần Khôi Nguyên',   'USER', 'BACKEND',  'Backend Developer',     'MID',       3, TRUE,  'LOCAL',  'CONCISE'),
    (9003, 'backend_minh_duc',      'Phạm Minh Đức',      'USER', 'BACKEND',  'Backend Developer',     'SENIOR',    7, TRUE,  'GITHUB', 'CODE_HEAVY'),
    (9004, 'backend_thao_vy',       'Vũ Thảo Vy',         'USER', 'BACKEND',  'Backend Developer',     'JUNIOR',    1, TRUE,  'LOCAL',  'ANALOGY_HEAVY'),
    (9005, 'backend_dung_nhan',     'Phan Dũng Nhân',     'USER', 'BACKEND',  'Tech Lead',             'LEAD',     10, TRUE,  'LOCAL',  'DETAILED'),
    (9006, 'backend_hai_son',       'Đặng Hải Sơn',       'USER', 'BACKEND',  'Backend Developer',     'MID',       4, TRUE,  'LOCAL',  'CONCISE'),
    (9007, 'backend_ngoc_quan',     'Bùi Ngọc Quân',      'USER', 'BACKEND',  'Backend Developer',     'JUNIOR',    2, FALSE, 'LOCAL',  'CODE_HEAVY'),
    (9008, 'backend_tuan_kiet',     'Hoàng Tuấn Kiệt',    'USER', 'BACKEND',  'Backend Developer',     'SENIOR',    8, TRUE,  'GOOGLE', 'DETAILED'),
    (9009, 'backend_bao_chau',      'Lâm Bảo Châu',       'USER', 'BACKEND',  'Backend Developer',     'MID',       4, TRUE,  'LOCAL',  'CONCISE'),
    (9010, 'backend_truong_giang',  'Đỗ Trường Giang',    'USER', 'BACKEND',  'Principal Engineer',    'PRINCIPAL',14, TRUE,  'LOCAL',  'DETAILED'),
    -- ── FRONTEND ───────────────────────────────────────────────────────────────────────────
    (9011, 'frontend_lan_chi',      'Huỳnh Lan Chi',      'USER', 'FRONTEND', 'Frontend Developer',    'SENIOR',    6, TRUE,  'LOCAL',  'ANALOGY_HEAVY'),
    (9012, 'frontend_ha_my',        'Lê Hà My',           'USER', 'FRONTEND', 'Frontend Developer',    'MID',       3, TRUE,  'LOCAL',  'CONCISE'),
    (9013, 'frontend_gia_bao',      'Trịnh Gia Bảo',      'USER', 'FRONTEND', 'Frontend Developer',    'JUNIOR',    1, FALSE, 'LOCAL',  'DETAILED'),
    (9014, 'frontend_thanh_truc',   'Ngô Thanh Trúc',     'USER', 'FRONTEND', 'Frontend Developer',    'MID',       4, TRUE,  'GITHUB', 'CODE_HEAVY'),
    (9015, 'frontend_minh_thu',     'Cao Minh Thư',       'USER', 'FRONTEND', 'Frontend Developer',    'SENIOR',    7, TRUE,  'LOCAL',  'CONCISE'),
    (9016, 'frontend_nhat_hao',     'Dương Nhật Hào',     'USER', 'FRONTEND', 'Frontend Developer',    'JUNIOR',    2, TRUE,  'LOCAL',  'ANALOGY_HEAVY'),
    (9017, 'frontend_quynh_nhu',    'Tạ Quỳnh Như',       'USER', 'FRONTEND', 'Frontend Developer',    'MID',       3, TRUE,  'LOCAL',  'DETAILED'),
    (9018, 'frontend_dang_khoa',    'Lý Đăng Khoa',       'USER', 'FRONTEND', 'Frontend Lead',         'LEAD',      9, TRUE,  'LOCAL',  'CODE_HEAVY'),
    -- ── FULLSTACK ──────────────────────────────────────────────────────────────────────────
    (9019, 'fullstack_hoang_long',  'Mai Hoàng Long',     'USER', 'FULLSTACK','Fullstack Developer',   'SENIOR',    6, TRUE,  'LOCAL',  'DETAILED'),
    (9020, 'fullstack_diem_quynh',  'Tô Diễm Quỳnh',      'USER', 'FULLSTACK','Fullstack Developer',   'MID',       4, TRUE,  'LOCAL',  'CONCISE'),
    (9021, 'fullstack_anh_tuan',    'Chu Anh Tuấn',       'USER', 'FULLSTACK','Engineering Manager',   'LEAD',     11, TRUE,  'GOOGLE', 'DETAILED'),
    (9022, 'fullstack_khanh_van',   'Hồ Khánh Vân',       'USER', 'FULLSTACK','Fullstack Developer',   'JUNIOR',    1, TRUE,  'LOCAL',  'ANALOGY_HEAVY'),
    (9023, 'fullstack_duc_thinh',   'Lương Đức Thịnh',    'USER', 'FULLSTACK','Fullstack Developer',   'MID',       3, TRUE,  'LOCAL',  'CODE_HEAVY'),
    (9024, 'fullstack_thu_huong',   'Đoàn Thu Hương',     'USER', 'FULLSTACK','Fullstack Developer',   'SENIOR',    8, TRUE,  'LOCAL',  'CONCISE'),
    -- ── MOBILE ─────────────────────────────────────────────────────────────────────────────
    (9025, 'mobile_gia_han',        'Trương Gia Hân',     'USER', 'MOBILE',   'Mobile Developer',      'MID',       4, TRUE,  'LOCAL',  'CONCISE'),
    (9026, 'mobile_phuc_thinh',     'Nguyễn Phúc Thịnh',  'USER', 'MOBILE',   'Mobile Developer',      'SENIOR',    7, TRUE,  'LOCAL',  'DETAILED'),
    (9027, 'mobile_bich_ngoc',      'Vương Bích Ngọc',    'USER', 'MOBILE',   'Mobile Developer',      'JUNIOR',    2, TRUE,  'LOCAL',  'ANALOGY_HEAVY'),
    (9028, 'mobile_huu_nghia',      'Thái Hữu Nghĩa',     'USER', 'MOBILE',   'Mobile Developer',      'MID',       3, FALSE, 'LOCAL',  'CODE_HEAVY'),
    (9029, 'mobile_kieu_trang',     'Đinh Kiều Trang',    'USER', 'MOBILE',   'Mobile Lead',           'SENIOR',    9, TRUE,  'GITHUB', 'DETAILED'),
    (9030, 'mobile_quoc_viet',      'Hà Quốc Việt',       'USER', 'MOBILE',   'Mobile Developer',      'JUNIOR',    1, TRUE,  'LOCAL',  'CONCISE'),
    -- ── DEVOPS ─────────────────────────────────────────────────────────────────────────────
    (9031, 'devops_mai_linh',       'Bùi Mai Linh',       'USER', 'DEVOPS',   'DevOps Engineer',       'SENIOR',    6, TRUE,  'LOCAL',  'DETAILED'),
    (9032, 'devops_chi_bao',        'Đỗ Chi Bảo',         'USER', 'DEVOPS',   'DevOps Engineer',       'MID',       4, TRUE,  'LOCAL',  'CONCISE'),
    (9033, 'devops_hoai_nam',       'Nguyễn Hoài Nam',    'USER', 'DEVOPS',   'Platform Lead',         'LEAD',     12, TRUE,  'LOCAL',  'DETAILED'),
    (9034, 'devops_yen_nhi',        'Trần Yến Nhi',       'USER', 'DEVOPS',   'DevOps Engineer',       'JUNIOR',    2, TRUE,  'LOCAL',  'ANALOGY_HEAVY'),
    (9035, 'devops_cong_danh',      'Phạm Công Danh',     'USER', 'DEVOPS',   'SRE',                   'MID',       5, TRUE,  'LOCAL',  'CODE_HEAVY'),
    (9036, 'devops_thuy_hang',      'Lê Thuý Hằng',       'USER', 'DEVOPS',   'SRE',                   'SENIOR',    8, TRUE,  'LOCAL',  'CONCISE'),
    -- ── DATA / ML ──────────────────────────────────────────────────────────────────────────
    (9037, 'data_long_vu',          'Dương Long Vũ',      'USER', 'DATA_ML',  'Data Engineer',         'SENIOR',    6, TRUE,  'LOCAL',  'DETAILED'),
    (9038, 'data_kien_cuong',       'Lý Kiên Cường',      'USER', 'DATA_ML',  'Data Scientist',        'MID',       4, TRUE,  'LOCAL',  'ANALOGY_HEAVY'),
    (9039, 'data_anh_tuyet',        'Võ Ánh Tuyết',       'USER', 'DATA_ML',  'ML Engineer',           'PRINCIPAL',13, TRUE,  'GOOGLE', 'DETAILED'),
    (9040, 'data_duy_khang',        'Nguyễn Duy Khang',   'USER', 'DATA_ML',  'Data Analyst',          'JUNIOR',    1, TRUE,  'LOCAL',  'CONCISE'),
    (9041, 'data_bao_tran',         'Trần Bảo Trân',      'USER', 'DATA_ML',  'Data Scientist',        'MID',       3, TRUE,  'LOCAL',  'CODE_HEAVY'),
    (9042, 'data_hai_dang',         'Phùng Hải Đăng',     'USER', 'DATA_ML',  'ML Engineer',           'SENIOR',    7, TRUE,  'LOCAL',  'DETAILED'),
    -- ── SECURITY ───────────────────────────────────────────────────────────────────────────
    (9043, 'security_quang_huy',    'Đặng Quang Huy',     'USER', 'SECURITY', 'Security Engineer',     'SENIOR',    7, TRUE,  'LOCAL',  'DETAILED'),
    (9044, 'security_tuyet_mai',    'Nguyễn Tuyết Mai',   'USER', 'SECURITY', 'Security Engineer',     'MID',       4, TRUE,  'LOCAL',  'CONCISE'),
    (9045, 'security_vinh_phuc',    'Hoàng Vĩnh Phúc',    'USER', 'SECURITY', 'Security Lead',         'LEAD',     10, TRUE,  'LOCAL',  'DETAILED'),
    (9046, 'security_ngoc_diep',    'Trần Ngọc Diệp',     'USER', 'SECURITY', 'Security Analyst',      'JUNIOR',    2, TRUE,  'LOCAL',  'ANALOGY_HEAVY'),
    -- ── QA ─────────────────────────────────────────────────────────────────────────────────
    (9047, 'qa_son_tung',           'Lương Sơn Tùng',     'USER', 'QA',       'QA Engineer',           'MID',       4, TRUE,  'LOCAL',  'CONCISE'),
    (9048, 'qa_giang_thanh',        'Đoàn Giang Thanh',   'USER', 'QA',       'QA Engineer',           'JUNIOR',    1, TRUE,  'LOCAL',  'ANALOGY_HEAVY'),
    (9049, 'qa_le_quyen',           'Nguyễn Lệ Quyên',    'USER', 'QA',       'QA Lead',               'SENIOR',    8, TRUE,  'LOCAL',  'DETAILED'),
    (9050, 'qa_tan_loc',            'Phạm Tấn Lộc',       'USER', 'QA',       'Automation Engineer',   'MID',       5, TRUE,  'LOCAL',  'CODE_HEAVY'),
    (9051, 'qa_hong_nhung',         'Vũ Hồng Nhung',      'USER', 'QA',       'QA Engineer',           'JUNIOR',    2, FALSE, 'LOCAL',  'CONCISE'),
    (9052, 'qa_ba_dat',             'Trịnh Bá Đạt',       'USER', 'QA',       'Automation Engineer',   'SENIOR',    6, TRUE,  'LOCAL',  'CODE_HEAVY'),
    -- ── OTHER (product / design / content) ─────────────────────────────────────────────────
    (9053, 'pm_thu_trang',          'Nguyễn Thu Trang',   'USER', 'OTHER',    'Product Manager',       'LEAD',      9, TRUE,  'LOCAL',  'ANALOGY_HEAVY'),
    (9054, 'designer_minh_quang',   'Lê Minh Quang',      'USER', 'OTHER',    'Product Designer',      'MID',       4, TRUE,  'LOCAL',  'ANALOGY_HEAVY'),
    (9055, 'designer_kim_ngan',     'Trần Kim Ngân',      'USER', 'OTHER',    'UX Researcher',         'SENIOR',    7, TRUE,  'LOCAL',  'DETAILED'),
    (9056, 'writer_anh_thu',        'Phạm Anh Thư',       'USER', 'OTHER',    'Technical Writer',      'MID',       3, TRUE,  'LOCAL',  'DETAILED'),
    (9057, 'pm_nhat_minh',          'Hoàng Nhật Minh',    'USER', 'OTHER',    'Product Manager',       'SENIOR',    6, TRUE,  'LOCAL',  'CONCISE'),
    (9058, 'devrel_thanh_binh',     'Đỗ Thanh Bình',      'USER', 'OTHER',    'Developer Advocate',    'MID',       5, TRUE,  'GITHUB', 'ANALOGY_HEAVY'),
    -- ── ADMIN ──────────────────────────────────────────────────────────────────────────────
    -- Không có hồ sơ nghề nghiệp: đây là tài khoản vận hành, dùng cho /v1/api/admin/** và hàng
    -- chờ kiểm duyệt, không phải người dùng của sản phẩm.
    (9059, 'admin_one',             'Quản Trị Viên Một',  'ADMIN', NULL, NULL, NULL, NULL, TRUE, 'LOCAL', NULL),
    (9060, 'admin_two',             'Quản Trị Viên Hai',  'ADMIN', NULL, NULL, NULL, NULL, TRUE, 'LOCAL', NULL);

-- ── t_users ───────────────────────────────────────────────────────────────────────────────
-- profile_picture_url để NULL cho tất cả, có chủ đích. Cột này chứa URL trỏ thẳng vào MinIO
-- (ProfileService ghép giá trị cấu hình minio.url với /profile-pictures/<key>), mà SQL thì không
-- tạo được object trong MinIO — điền vào đây là được một loạt ảnh vỡ. NULL thì frontend hiển thị
-- avatar mặc định. Muốn có ảnh thật thì upload qua chính API đổi ảnh đại diện.
--
-- Tên thuộc tính ở trên cố ý viết ra chữ. Flyway thay placeholder ở tầng đọc file, TRƯỚC khi
-- parse SQL, nên cú pháp placeholder (dấu đô-la kèm ngoặc nhọn) nằm trong một comment cũng đủ
-- làm cả migration chết vì "No value provided for placeholder" — đã xảy ra đúng ở dòng này.
INSERT INTO socialapp.t_users
    (id, email, password, username, full_name, profile_picture_url,
     email_verified, role, auth_provider, provider_id, elite_score, created_at, updated_at)
SELECT p.id,
       p.username || '@seed.test',
       -- Hash ghi cứng, và bộ seed này chạy CẢ Ở PRODUCTION — nên hai chuỗi dưới đây là mật khẩu
       -- thật của môi trường thật, nằm công khai trong repo. Đây là đánh đổi đã được cân nhắc và
       -- chấp nhận (2026-08-21) để khỏi phải quản lý thêm một secret; KHÔNG phải sơ suất.
       --
       -- Hai hash khác nhau, có chủ đích:
       --   - Tài khoản thường: "12345678". Ai đọc repo cũng biết, nhưng đó là tài khoản demo
       --     không có quyền gì đặc biệt.
       --   - Hai tài khoản ADMIN (9059, 9060): "SocialApp@Admin2026". Tách ra vì "12345678" là
       --     chuỗi ĐẦU TIÊN mọi bot dò mật khẩu thử mà không cần đọc repo — để admin dùng chung
       --     chuỗi đó nghĩa là trao quyền quản trị cho lượt quét tự động đầu tiên đi ngang qua.
       --
       -- Việc tách này KHÔNG bảo vệ được trước người đọc repo; họ vào được cả hai. Muốn đóng hẳn
       -- thì đổi mật khẩu admin qua API sau khi seed xong.
       CASE WHEN p.user_role = 'ADMIN'
            THEN '$2y$10$d0Z1hUauB/Tqy3gkpvwW1uOF7bn4HCPq6gWdngClY9bwL8cBRXK3S'  -- SocialApp@Admin2026
            ELSE '$2a$10$CgEPj7qoEq29zS464iIuYOlRm0IqNtk7gQ9KG5qKTC5T2F8eiJzSa'  -- 12345678
       END,
       p.username,
       p.full_name,
       NULL,
       p.email_verified,
       p.user_role,
       p.auth_provider,
       -- provider_id chỉ có nghĩa với tài khoản đăng nhập qua bên thứ ba.
       CASE WHEN p.auth_provider = 'LOCAL' THEN NULL ELSE 'seed-' || p.id END,
       -- elite_score được tính lại từ t_reputation_events ở V60; để 0 làm mốc xuất phát.
       0,
       -- Ngày tạo trải đều 60 tuần về trước, id nhỏ = tham gia sớm hơn.
       now() - ((9061 - p.id) * INTERVAL '7 days'),
       now() - ((9061 - p.id) * INTERVAL '7 days')
  FROM tmp_seed_people p;

-- ── t_user_professional_profiles ───────────────────────────────────────────────────────────
-- Nguồn dữ liệu cho gợi ý kết bạn theo nền tảng nghề nghiệp và cho matchmaking.
-- known_tech_stack / interested_domains / work_history là jsonb (List<String> và
-- List<WorkExperience>{company,domain,role,durationMonths} — xem UserProfessionalProfileEntity).
INSERT INTO socialapp.t_user_professional_profiles
    (user_id, job_title, seniority_level, years_of_experience, primary_role, explanation_style,
     known_tech_stack, work_history, interested_domains, created_at, updated_at)
SELECT p.id,
       p.job_title,
       p.seniority,
       p.years_exp,
       p.primary_role,
       p.expl_style,
       CASE p.primary_role
           WHEN 'BACKEND'   THEN '["Java","Spring Boot","PostgreSQL","Redis","Docker","Kafka"]'
           WHEN 'FRONTEND'  THEN '["TypeScript","React","Next.js","TailwindCSS","Vite","Zustand"]'
           WHEN 'FULLSTACK' THEN '["TypeScript","Node.js","React","PostgreSQL","Docker","GraphQL"]'
           WHEN 'MOBILE'    THEN '["Kotlin","Swift","Flutter","Dart","Firebase","Jetpack Compose"]'
           WHEN 'DEVOPS'    THEN '["Kubernetes","Terraform","AWS","Docker","Prometheus","GitHub Actions"]'
           WHEN 'DATA_ML'   THEN '["Python","PyTorch","Pandas","Spark","Airflow","DuckDB"]'
           WHEN 'SECURITY'  THEN '["Burp Suite","OWASP ZAP","Wireshark","Python","Nmap","Semgrep"]'
           WHEN 'QA'        THEN '["Playwright","Selenium","JUnit","Postman","k6","Cypress"]'
           ELSE                  '["Figma","Notion","Jira","Amplitude","Miro"]'
       END::jsonb,
       -- Một chỗ làm gần nhất cho mỗi người; độ dài suy ra từ số năm kinh nghiệm để hai trường
       -- không mâu thuẫn nhau khi hiển thị cạnh nhau trên hồ sơ.
       jsonb_build_array(
           jsonb_build_object(
               'company', CASE (p.id % 6)
                              WHEN 0 THEN 'FPT Software'
                              WHEN 1 THEN 'VNG Corporation'
                              WHEN 2 THEN 'Tiki'
                              WHEN 3 THEN 'MoMo'
                              WHEN 4 THEN 'Shopee Việt Nam'
                              ELSE        'Viettel Digital'
                          END,
               'domain',  CASE (p.id % 5)
                              WHEN 0 THEN 'E-commerce'
                              WHEN 1 THEN 'Fintech'
                              WHEN 2 THEN 'Logistics'
                              WHEN 3 THEN 'EdTech'
                              ELSE        'Social'
                          END,
               'role',    p.job_title,
               'durationMonths', GREATEST(p.years_exp * 12 - 6, 6)
           )
       ),
       CASE p.primary_role
           WHEN 'BACKEND'   THEN '["Distributed Systems","API Design","Database Internals"]'
           WHEN 'FRONTEND'  THEN '["Design Systems","Web Performance","Accessibility"]'
           WHEN 'FULLSTACK' THEN '["Developer Experience","API Design","Web Performance"]'
           WHEN 'MOBILE'    THEN '["Mobile UX","Offline First","Cross-platform"]'
           WHEN 'DEVOPS'    THEN '["Observability","Infrastructure as Code","Cost Optimization"]'
           WHEN 'DATA_ML'   THEN '["MLOps","Recommendation Systems","Data Quality"]'
           WHEN 'SECURITY'  THEN '["AppSec","Threat Modeling","Supply Chain Security"]'
           WHEN 'QA'        THEN '["Test Automation","Performance Testing","Quality Culture"]'
           ELSE                  '["Product Discovery","User Research","Growth"]'
       END::jsonb,
       now() - ((9061 - p.id) * INTERVAL '7 days'),
       now() - ((9061 - p.id) * INTERVAL '7 days')
  FROM tmp_seed_people p
 WHERE p.primary_role IS NOT NULL;

-- ── t_notification_preferences ─────────────────────────────────────────────────────────────
-- Mỗi user một dòng. onesignal_player_id để NULL: đó là id thiết bị thật do SDK OneSignal cấp,
-- bịa ra chỉ khiến push đi vào hư không và log đầy lỗi.
-- email_frequency chỉ nhận 'INSTANT' hoặc 'NONE' (CHECK constraint từ V45).
INSERT INTO socialapp.t_notification_preferences
    (user_id, push_enabled, email_enabled, onesignal_player_id, email_frequency, muted_types, updated_at)
SELECT p.id,
       TRUE,
       -- Cứ 7 người thì 1 người tắt email, để lọc theo email_enabled có dữ liệu hai phía.
       (p.id % 7) <> 0,
       NULL,
       CASE WHEN (p.id % 7) = 0 THEN 'NONE' ELSE 'INSTANT' END,
       -- muted_types là List<String> của NotificationType. Một vài người tắt bớt loại ồn nhất.
       CASE
           WHEN (p.id % 11) = 0 THEN '["POST_LIKED","EVENT_REMINDER"]'
           WHEN (p.id % 13) = 0 THEN '["POST_TAGGED"]'
           ELSE                      '[]'
       END::jsonb,
       now()
  FROM tmp_seed_people p;

-- Bảng tạm chỉ phục vụ file này. Drop tường minh thay vì ON COMMIT DROP: Flyway bọc mỗi
-- migration trong một transaction nên ON COMMIT DROP cũng chạy, nhưng khi nạp file bằng psql ở
-- chế độ autocommit thì bảng biến mất ngay sau lệnh CREATE và mọi INSERT phía dưới sẽ hỏng.
DROP TABLE tmp_seed_people;
