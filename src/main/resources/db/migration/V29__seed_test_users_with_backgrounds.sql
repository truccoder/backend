-- Seed 25 test accounts with diverse (and deliberately overlapping) professional backgrounds,
-- for exercising GET /v1/api/friendships/suggestions (background-based ranking).
-- Password for all accounts below is: 12345678
-- BCrypt hash generated with the app's own BCryptPasswordEncoder (strength 10), same hash V20 uses.
--
-- IDs are reserved in the 9001-9025 range (unlikely to collide with real signups on a dev DB) so
-- the companion Neo4j friendship graph (docker/neo4j/seed/friend-graph.cypher) can reference the
-- exact same userIds. The sequence is bumped afterward so future organic signups continue past
-- this range without collision.
INSERT INTO socialapp.t_users (id, email, password, username, full_name)
VALUES (9001, 'nguyen.truc@test.com', '$2a$10$CgEPj7qoEq29zS464iIuYOlRm0IqNtk7gQ9KG5qKTC5T2F8eiJzSa',
        'backend_nguyen_truc', 'Backend Nguyễn Trúc'),
       (9002, 'tran.khoi@test.com', '$2a$10$CgEPj7qoEq29zS464iIuYOlRm0IqNtk7gQ9KG5qKTC5T2F8eiJzSa', 'backend_tran_khoi',
        'Backend Trần Khôi'),
       (9003, 'pham.minh@test.com', '$2a$10$CgEPj7qoEq29zS464iIuYOlRm0IqNtk7gQ9KG5qKTC5T2F8eiJzSa', 'backend_pham_minh',
        'Backend Phạm Minh'),
       (9004, 'vu.thao@test.com', '$2a$10$CgEPj7qoEq29zS464iIuYOlRm0IqNtk7gQ9KG5qKTC5T2F8eiJzSa', 'backend_vu_thao',
        'Backend Vũ Thảo'),
       (9005, 'phan.dung@test.com', '$2a$10$CgEPj7qoEq29zS464iIuYOlRm0IqNtk7gQ9KG5qKTC5T2F8eiJzSa', 'backend_phan_dung',
        'Backend Phan Dũng'),

       (9006, 'huynh.lan@test.com', '$2a$10$CgEPj7qoEq29zS464iIuYOlRm0IqNtk7gQ9KG5qKTC5T2F8eiJzSa',
        'frontend_huynh_lan', 'Frontend Huỳnh Lan'),
       (9007, 'le.ha@test.com', '$2a$10$CgEPj7qoEq29zS464iIuYOlRm0IqNtk7gQ9KG5qKTC5T2F8eiJzSa', 'frontend_le_ha',
        'Frontend Lê Hà'),
       (9008, 'hoang.linh@test.com', '$2a$10$CgEPj7qoEq29zS464iIuYOlRm0IqNtk7gQ9KG5qKTC5T2F8eiJzSa',
        'frontend_hoang_linh', 'Frontend Hoàng Linh'),
       (9009, 'vo.quan@test.com', '$2a$10$CgEPj7qoEq29zS464iIuYOlRm0IqNtk7gQ9KG5qKTC5T2F8eiJzSa', 'frontend_vo_quan',
        'Frontend Võ Quân'),
       (9010, 'dang.hung@test.com', '$2a$10$CgEPj7qoEq29zS464iIuYOlRm0IqNtk7gQ9KG5qKTC5T2F8eiJzSa',
        'frontend_dang_hung', 'Frontend Đặng Hùng'),

       (9011, 'bui.mai@test.com', '$2a$10$CgEPj7qoEq29zS464iIuYOlRm0IqNtk7gQ9KG5qKTC5T2F8eiJzSa', 'devops_bui_mai',
        'DevOps Bùi Mai'),
       (9012, 'do.chi@test.com', '$2a$10$CgEPj7qoEq29zS464iIuYOlRm0IqNtk7gQ9KG5qKTC5T2F8eiJzSa', 'devops_do_chi',
        'DevOps Đỗ Chi'),
       (9013, 'ho.tam@test.com', '$2a$10$CgEPj7qoEq29zS464iIuYOlRm0IqNtk7gQ9KG5qKTC5T2F8eiJzSa', 'devops_ho_tam',
        'DevOps Hồ Tâm'),
       (9014, 'ngo.phuc@test.com', '$2a$10$CgEPj7qoEq29zS464iIuYOlRm0IqNtk7gQ9KG5qKTC5T2F8eiJzSa', 'devops_ngo_phuc',
        'DevOps Ngô Phúc'),

       (9015, 'duong.long@test.com', '$2a$10$CgEPj7qoEq29zS464iIuYOlRm0IqNtk7gQ9KG5qKTC5T2F8eiJzSa', 'data_duong_long',
        'Data Dương Long'),
       (9016, 'ly.kien@test.com', '$2a$10$CgEPj7qoEq29zS464iIuYOlRm0IqNtk7gQ9KG5qKTC5T2F8eiJzSa', 'data_ly_kien',
        'Data Lý Kiên'),
       (9017, 'dinh.binh@test.com', '$2a$10$CgEPj7qoEq29zS464iIuYOlRm0IqNtk7gQ9KG5qKTC5T2F8eiJzSa', 'data_dinh_binh',
        'Data Đinh Bình'),
       (9018, 'trinh.nhung@test.com', '$2a$10$CgEPj7qoEq29zS464iIuYOlRm0IqNtk7gQ9KG5qKTC5T2F8eiJzSa',
        'data_trinh_nhung', 'Data Trịnh Nhung'),

       (9019, 'to.trang@test.com', '$2a$10$CgEPj7qoEq29zS464iIuYOlRm0IqNtk7gQ9KG5qKTC5T2F8eiJzSa', 'mobile_to_trang',
        'Mobile Tô Trang'),
       (9020, 'mai.hieu@test.com', '$2a$10$CgEPj7qoEq29zS464iIuYOlRm0IqNtk7gQ9KG5qKTC5T2F8eiJzSa', 'mobile_mai_hieu',
        'Mobile Mai Hiếu'),
       (9021, 'vuong.vy@test.com', '$2a$10$CgEPj7qoEq29zS464iIuYOlRm0IqNtk7gQ9KG5qKTC5T2F8eiJzSa', 'mobile_vuong_vy',
        'Mobile Vương Vy'),
       (9022, 'cao.dat@test.com', '$2a$10$CgEPj7qoEq29zS464iIuYOlRm0IqNtk7gQ9KG5qKTC5T2F8eiJzSa', 'mobile_cao_dat',
        'Mobile Cao Đạt'),

       (9023, 'luong.son@test.com', '$2a$10$CgEPj7qoEq29zS464iIuYOlRm0IqNtk7gQ9KG5qKTC5T2F8eiJzSa', 'qa_luong_son',
        'QA Lương Sơn'),
       (9024, 'doan.giang@test.com', '$2a$10$CgEPj7qoEq29zS464iIuYOlRm0IqNtk7gQ9KG5qKTC5T2F8eiJzSa', 'qa_doan_giang',
        'QA Đoàn Giang'),
       (9025, 'tang.an@test.com', '$2a$10$CgEPj7qoEq29zS464iIuYOlRm0IqNtk7gQ9KG5qKTC5T2F8eiJzSa', 'qa_tang_an',
        'QA Tăng An')
ON CONFLICT (id) DO NOTHING;

SELECT setval('q_users_id', (SELECT MAX(id) FROM socialapp.t_users));

INSERT INTO socialapp.t_user_professional_profiles (user_id, job_title, seniority_level, years_of_experience,
                                                    primary_role, known_tech_stack)
VALUES (9001, 'Backend Developer', 'MID', 3, 'BACKEND', '[
  "Java",
  "Spring Boot",
  "PostgreSQL",
  "Docker"
]'::jsonb),
       (9002, 'Backend Developer', 'SENIOR', 6, 'BACKEND', '[
         "Java",
         "Spring Boot",
         "PostgreSQL",
         "Docker"
       ]'::jsonb),
       (9003, 'Backend Developer', 'JUNIOR', 1, 'BACKEND', '[
         "Java",
         "Spring Boot",
         "PostgreSQL",
         "Docker"
       ]'::jsonb),
       (9004, 'Backend Developer', 'LEAD', 8, 'BACKEND', '[
         "Java",
         "Spring Boot",
         "PostgreSQL",
         "Docker"
       ]'::jsonb),
       (9005, 'Backend Developer', 'MID', 4, 'BACKEND', '[
         "Java",
         "Spring Boot",
         "PostgreSQL",
         "Docker"
       ]'::jsonb),

       (9006, 'Frontend Developer', 'MID', 3, 'FRONTEND', '[
         "React",
         "TypeScript",
         "TailwindCSS",
         "Vite"
       ]'::jsonb),
       (9007, 'Frontend Developer', 'SENIOR', 5, 'FRONTEND', '[
         "React",
         "TypeScript",
         "TailwindCSS",
         "Vite"
       ]'::jsonb),
       (9008, 'Frontend Developer', 'JUNIOR', 2, 'FRONTEND', '[
         "React",
         "TypeScript",
         "TailwindCSS",
         "Vite"
       ]'::jsonb),
       (9009, 'Frontend Developer', 'LEAD', 7, 'FRONTEND', '[
         "React",
         "TypeScript",
         "TailwindCSS",
         "Vite"
       ]'::jsonb),
       (9010, 'Frontend Developer', 'MID', 4, 'FRONTEND', '[
         "React",
         "TypeScript",
         "TailwindCSS",
         "Vite"
       ]'::jsonb),

       (9011, 'DevOps Engineer', 'SENIOR', 5, 'DEVOPS', '[
         "Kubernetes",
         "Terraform",
         "AWS",
         "Jenkins"
       ]'::jsonb),
       (9012, 'DevOps Engineer', 'MID', 3, 'DEVOPS', '[
         "Kubernetes",
         "Terraform",
         "AWS",
         "Jenkins"
       ]'::jsonb),
       (9013, 'DevOps Engineer', 'JUNIOR', 1, 'DEVOPS', '[
         "Kubernetes",
         "Terraform",
         "AWS",
         "Jenkins"
       ]'::jsonb),
       (9014, 'DevOps Engineer', 'LEAD', 9, 'DEVOPS', '[
         "Kubernetes",
         "Terraform",
         "AWS",
         "Jenkins"
       ]'::jsonb),

       (9015, 'Data Scientist', 'SENIOR', 6, 'DATA_ML', '[
         "Python",
         "TensorFlow",
         "Pandas",
         "SQL"
       ]'::jsonb),
       (9016, 'Data Scientist', 'MID', 3, 'DATA_ML', '[
         "Python",
         "TensorFlow",
         "Pandas",
         "SQL"
       ]'::jsonb),
       (9017, 'Data Scientist', 'JUNIOR', 2, 'DATA_ML', '[
         "Python",
         "TensorFlow",
         "Pandas",
         "SQL"
       ]'::jsonb),
       (9018, 'Data Scientist', 'PRINCIPAL', 10, 'DATA_ML', '[
         "Python",
         "TensorFlow",
         "Pandas",
         "SQL"
       ]'::jsonb),

       (9019, 'Mobile Developer', 'MID', 3, 'MOBILE', '[
         "Kotlin",
         "Swift",
         "Flutter",
         "Firebase"
       ]'::jsonb),
       (9020, 'Mobile Developer', 'SENIOR', 5, 'MOBILE', '[
         "Kotlin",
         "Swift",
         "Flutter",
         "Firebase"
       ]'::jsonb),
       (9021, 'Mobile Developer', 'JUNIOR', 1, 'MOBILE', '[
         "Kotlin",
         "Swift",
         "Flutter",
         "Firebase"
       ]'::jsonb),
       (9022, 'Mobile Developer', 'MID', 4, 'MOBILE', '[
         "Kotlin",
         "Swift",
         "Flutter",
         "Firebase"
       ]'::jsonb),

       (9023, 'QA Engineer', 'MID', 3, 'QA', '[
         "Selenium",
         "Cypress",
         "Postman",
         "JIRA"
       ]'::jsonb),
       (9024, 'QA Engineer', 'SENIOR', 5, 'QA', '[
         "Selenium",
         "Cypress",
         "Postman",
         "JIRA"
       ]'::jsonb),
       (9025, 'QA Engineer', 'JUNIOR', 1, 'QA', '[
         "Selenium",
         "Cypress",
         "Postman",
         "JIRA"
       ]'::jsonb)
ON CONFLICT (user_id) DO NOTHING;
