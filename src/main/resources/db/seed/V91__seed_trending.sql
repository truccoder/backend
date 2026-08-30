-- =============================================================================================
-- 40 tin xu hướng.

-- FILE NÀY SINH TỰ ĐỘNG bởi scripts/seed/generate_seed.py — sửa tay sẽ bị ghi đè.
--
-- KHÔNG CÓ PHẦN GITHUB. Quyết định 25/08: không liên kết tài khoản nào với GitHub, nên t_github_stats
-- để trống hẳn. Hệ quả cần biết trước, không phải lỗi: SkillVerificationService.verifyViaExternalApi
-- tra bảng đó để tự xác minh kỹ năng, không có hàng nào thì nhánh này LUÔN TRẢ FALSE và mọi yêu cầu
-- xác minh kỹ năng rơi về duyệt tay. Với buổi demo đây lại là điều tốt — hàng đợi quản trị có việc
-- thật — nhưng phải ghi vào README, nếu không lần sau sẽ có người đi tìm lỗi trong hàm đó.
--
-- UNIQUE(source, source_id): source_id phải khác nhau từng dòng, nếu không chỉ chèn được một tin.
--
-- source RẢI QUA BA HẰNG CỦA TrendingSource (HACKER_NEWS, DEV_TO, GITHUB). Enum đó có đúng ba giá
-- trị — một hằng cho mỗi crawler còn sống — và KHÔNG có 'SEED'. Cột là varchar(50) không có CHECK
-- nên một nhãn tự chế vẫn chèn được, rồi nổ ở Hibernate lúc đọc và làm GET /v1/api/trending trả 500.
-- Thêm hằng 'SEED' vào enum thì rẻ hơn về phía seed nhưng đắt hơn nhiều ở chỗ khác: nó nới
-- TrendingItemDto.source trong hợp đồng OpenAPI, tức client sinh ra ở frontend phải sinh lại theo.
-- Bộ seed đi theo ứng dụng, không bắt ứng dụng đi theo mình.
--
-- URL vẫn trỏ tin-tuc.example.test dù nhãn nguồn là thật: .test là tên miền dành riêng, không định
-- tuyến được (RFC 2606). Đổi sang news.ycombinator.com hay dev.to sẽ cho ra những liên kết trông
-- thật rồi 404 khi bấm — tệ hơn một liên kết thấy ngay là dữ liệu mẫu.

-- =============================================================================================

INSERT INTO socialapp.t_trending_items
    (title, summary, url, image_url, source, source_id, category, tags, score, author,
     published_at, crawled_at) VALUES
    ('Java 25 phát hành bản hỗ trợ dài hạn (số 1)', 'Tóm tắt ngắn cho tin số 1, đủ để quyết định có mở ra đọc hay không.', 'https://tin-tuc.example.test/bai/1', NULL, 'HACKER_NEWS', 'seed-1', 'NEW_TECH', '["tin-tuc"]'::jsonb, 1809, NULL, now() - INTERVAL '58 days', now() - INTERVAL '58 days'),
    ('Postgres 18 cải thiện đáng kể tốc độ VACUUM (số 2)', 'Tóm tắt ngắn cho tin số 2, đủ để quyết định có mở ra đọc hay không.', 'https://tin-tuc.example.test/bai/2', NULL, 'DEV_TO', 'seed-2', 'NEW_TECH', '["tin-tuc"]'::jsonb, 8461, NULL, now() - INTERVAL '36 days', now() - INTERVAL '36 days'),
    ('Redis đổi giấy phép lần thứ hai trong hai năm (số 3)', 'Tóm tắt ngắn cho tin số 3, đủ để quyết định có mở ra đọc hay không.', 'https://tin-tuc.example.test/bai/3', NULL, 'GITHUB', 'seed-3', 'REGULATION', '["tin-tuc"]'::jsonb, 3593, NULL, now() - INTERVAL '49 days', now() - INTERVAL '49 days'),
    ('Kubernetes bỏ hỗ trợ một API đã đánh dấu lỗi thời (số 4)', 'Tóm tắt ngắn cho tin số 4, đủ để quyết định có mở ra đọc hay không.', 'https://tin-tuc.example.test/bai/4', NULL, 'HACKER_NEWS', 'seed-4', 'NEW_TECH', '["tin-tuc"]'::jsonb, 5122, NULL, now() - INTERVAL '54 days', now() - INTERVAL '54 days'),
    ('Báo cáo lương ngành phần mềm Việt Nam quý này (số 5)', 'Tóm tắt ngắn cho tin số 5, đủ để quyết định có mở ra đọc hay không.', 'https://tin-tuc.example.test/bai/5', NULL, 'DEV_TO', 'seed-5', 'CAREER', '["tin-tuc"]'::jsonb, 7919, NULL, now() - INTERVAL '3 days', now() - INTERVAL '3 days'),
    ('Xu hướng tuyển dụng nghiêng về kỹ sư đa năng (số 6)', 'Tóm tắt ngắn cho tin số 6, đủ để quyết định có mở ra đọc hay không.', 'https://tin-tuc.example.test/bai/6', NULL, 'GITHUB', 'seed-6', 'CAREER', '["tin-tuc"]'::jsonb, 2182, NULL, now() - INTERVAL '52 days', now() - INTERVAL '52 days'),
    ('Nhiều đội quay lại monolith sau vài năm microservices (số 7)', 'Tóm tắt ngắn cho tin số 7, đủ để quyết định có mở ra đọc hay không.', 'https://tin-tuc.example.test/bai/7', NULL, 'HACKER_NEWS', 'seed-7', 'MINDSET', '["tin-tuc"]'::jsonb, 4547, NULL, now() - INTERVAL '1 days', now() - INTERVAL '1 days'),
    ('Thảo luận: có nên viết test cho mã sắp bỏ (số 8)', 'Tóm tắt ngắn cho tin số 8, đủ để quyết định có mở ra đọc hay không.', 'https://tin-tuc.example.test/bai/8', NULL, 'DEV_TO', 'seed-8', 'MINDSET', '["tin-tuc"]'::jsonb, 4973, NULL, now() - INTERVAL '18 days', now() - INTERVAL '18 days'),
    ('Một thư viện phổ biến chuyển sang quỹ mã nguồn mở (số 9)', 'Tóm tắt ngắn cho tin số 9, đủ để quyết định có mở ra đọc hay không.', 'https://tin-tuc.example.test/bai/9', NULL, 'GITHUB', 'seed-9', 'OPENSOURCE', '["tin-tuc"]'::jsonb, 1864, NULL, now() - INTERVAL '43 days', now() - INTERVAL '43 days'),
    ('Hướng dẫn xoay vòng khoá bí mật mà không gián đoạn (số 10)', 'Tóm tắt ngắn cho tin số 10, đủ để quyết định có mở ra đọc hay không.', 'https://tin-tuc.example.test/bai/10', NULL, 'HACKER_NEWS', 'seed-10', 'TOOL', '["tin-tuc"]'::jsonb, 2382, NULL, now() - INTERVAL '25 days', now() - INTERVAL '25 days'),
    ('Hội thảo kỹ thuật thường niên mở đăng ký (số 11)', 'Tóm tắt ngắn cho tin số 11, đủ để quyết định có mở ra đọc hay không.', 'https://tin-tuc.example.test/bai/11', NULL, 'DEV_TO', 'seed-11', 'EVENT', '["tin-tuc"]'::jsonb, 6261, NULL, now() - INTERVAL '45 days', now() - INTERVAL '45 days'),
    ('Bộ công cụ dòng lệnh mới cho việc dò hiệu năng (số 12)', 'Tóm tắt ngắn cho tin số 12, đủ để quyết định có mở ra đọc hay không.', 'https://tin-tuc.example.test/bai/12', NULL, 'GITHUB', 'seed-12', 'TOOL', '["tin-tuc"]'::jsonb, 584, NULL, now() - INTERVAL '35 days', now() - INTERVAL '35 days'),
    ('Java 25 phát hành bản hỗ trợ dài hạn (số 13)', 'Tóm tắt ngắn cho tin số 13, đủ để quyết định có mở ra đọc hay không.', 'https://tin-tuc.example.test/bai/13', NULL, 'HACKER_NEWS', 'seed-13', 'NEW_TECH', '["tin-tuc"]'::jsonb, 4829, NULL, now() - INTERVAL '52 days', now() - INTERVAL '52 days'),
    ('Postgres 18 cải thiện đáng kể tốc độ VACUUM (số 14)', 'Tóm tắt ngắn cho tin số 14, đủ để quyết định có mở ra đọc hay không.', 'https://tin-tuc.example.test/bai/14', NULL, 'DEV_TO', 'seed-14', 'NEW_TECH', '["tin-tuc"]'::jsonb, 6815, NULL, now() - INTERVAL '23 days', now() - INTERVAL '23 days'),
    ('Redis đổi giấy phép lần thứ hai trong hai năm (số 15)', 'Tóm tắt ngắn cho tin số 15, đủ để quyết định có mở ra đọc hay không.', 'https://tin-tuc.example.test/bai/15', NULL, 'GITHUB', 'seed-15', 'REGULATION', '["tin-tuc"]'::jsonb, 7299, NULL, now() - INTERVAL '58 days', now() - INTERVAL '58 days'),
    ('Kubernetes bỏ hỗ trợ một API đã đánh dấu lỗi thời (số 16)', 'Tóm tắt ngắn cho tin số 16, đủ để quyết định có mở ra đọc hay không.', 'https://tin-tuc.example.test/bai/16', NULL, 'HACKER_NEWS', 'seed-16', 'NEW_TECH', '["tin-tuc"]'::jsonb, 6651, NULL, now() - INTERVAL '33 days', now() - INTERVAL '33 days'),
    ('Báo cáo lương ngành phần mềm Việt Nam quý này (số 17)', 'Tóm tắt ngắn cho tin số 17, đủ để quyết định có mở ra đọc hay không.', 'https://tin-tuc.example.test/bai/17', NULL, 'DEV_TO', 'seed-17', 'CAREER', '["tin-tuc"]'::jsonb, 7614, NULL, now() - INTERVAL '18 days', now() - INTERVAL '18 days'),
    ('Xu hướng tuyển dụng nghiêng về kỹ sư đa năng (số 18)', 'Tóm tắt ngắn cho tin số 18, đủ để quyết định có mở ra đọc hay không.', 'https://tin-tuc.example.test/bai/18', NULL, 'GITHUB', 'seed-18', 'CAREER', '["tin-tuc"]'::jsonb, 2641, NULL, now() - INTERVAL '16 days', now() - INTERVAL '16 days'),
    ('Nhiều đội quay lại monolith sau vài năm microservices (số 19)', 'Tóm tắt ngắn cho tin số 19, đủ để quyết định có mở ra đọc hay không.', 'https://tin-tuc.example.test/bai/19', NULL, 'HACKER_NEWS', 'seed-19', 'MINDSET', '["tin-tuc"]'::jsonb, 6971, NULL, now() - INTERVAL '57 days', now() - INTERVAL '57 days'),
    ('Thảo luận: có nên viết test cho mã sắp bỏ (số 20)', 'Tóm tắt ngắn cho tin số 20, đủ để quyết định có mở ra đọc hay không.', 'https://tin-tuc.example.test/bai/20', NULL, 'DEV_TO', 'seed-20', 'MINDSET', '["tin-tuc"]'::jsonb, 8342, NULL, now() - INTERVAL '25 days', now() - INTERVAL '25 days'),
    ('Một thư viện phổ biến chuyển sang quỹ mã nguồn mở (số 21)', 'Tóm tắt ngắn cho tin số 21, đủ để quyết định có mở ra đọc hay không.', 'https://tin-tuc.example.test/bai/21', NULL, 'GITHUB', 'seed-21', 'OPENSOURCE', '["tin-tuc"]'::jsonb, 4117, NULL, now() - INTERVAL '55 days', now() - INTERVAL '55 days'),
    ('Hướng dẫn xoay vòng khoá bí mật mà không gián đoạn (số 22)', 'Tóm tắt ngắn cho tin số 22, đủ để quyết định có mở ra đọc hay không.', 'https://tin-tuc.example.test/bai/22', NULL, 'HACKER_NEWS', 'seed-22', 'TOOL', '["tin-tuc"]'::jsonb, 3357, NULL, now() - INTERVAL '26 days', now() - INTERVAL '26 days'),
    ('Hội thảo kỹ thuật thường niên mở đăng ký (số 23)', 'Tóm tắt ngắn cho tin số 23, đủ để quyết định có mở ra đọc hay không.', 'https://tin-tuc.example.test/bai/23', NULL, 'DEV_TO', 'seed-23', 'EVENT', '["tin-tuc"]'::jsonb, 4222, NULL, now() - INTERVAL '19 days', now() - INTERVAL '19 days'),
    ('Bộ công cụ dòng lệnh mới cho việc dò hiệu năng (số 24)', 'Tóm tắt ngắn cho tin số 24, đủ để quyết định có mở ra đọc hay không.', 'https://tin-tuc.example.test/bai/24', NULL, 'GITHUB', 'seed-24', 'TOOL', '["tin-tuc"]'::jsonb, 4588, NULL, now() - INTERVAL '24 days', now() - INTERVAL '24 days'),
    ('Java 25 phát hành bản hỗ trợ dài hạn (số 25)', 'Tóm tắt ngắn cho tin số 25, đủ để quyết định có mở ra đọc hay không.', 'https://tin-tuc.example.test/bai/25', NULL, 'HACKER_NEWS', 'seed-25', 'NEW_TECH', '["tin-tuc"]'::jsonb, 5792, NULL, now() - INTERVAL '10 days', now() - INTERVAL '10 days'),
    ('Postgres 18 cải thiện đáng kể tốc độ VACUUM (số 26)', 'Tóm tắt ngắn cho tin số 26, đủ để quyết định có mở ra đọc hay không.', 'https://tin-tuc.example.test/bai/26', NULL, 'DEV_TO', 'seed-26', 'NEW_TECH', '["tin-tuc"]'::jsonb, 3458, NULL, now() - INTERVAL '38 days', now() - INTERVAL '38 days'),
    ('Redis đổi giấy phép lần thứ hai trong hai năm (số 27)', 'Tóm tắt ngắn cho tin số 27, đủ để quyết định có mở ra đọc hay không.', 'https://tin-tuc.example.test/bai/27', NULL, 'GITHUB', 'seed-27', 'REGULATION', '["tin-tuc"]'::jsonb, 7571, NULL, now() - INTERVAL '33 days', now() - INTERVAL '33 days'),
    ('Kubernetes bỏ hỗ trợ một API đã đánh dấu lỗi thời (số 28)', 'Tóm tắt ngắn cho tin số 28, đủ để quyết định có mở ra đọc hay không.', 'https://tin-tuc.example.test/bai/28', NULL, 'HACKER_NEWS', 'seed-28', 'NEW_TECH', '["tin-tuc"]'::jsonb, 8211, NULL, now() - INTERVAL '34 days', now() - INTERVAL '34 days'),
    ('Báo cáo lương ngành phần mềm Việt Nam quý này (số 29)', 'Tóm tắt ngắn cho tin số 29, đủ để quyết định có mở ra đọc hay không.', 'https://tin-tuc.example.test/bai/29', NULL, 'DEV_TO', 'seed-29', 'CAREER', '["tin-tuc"]'::jsonb, 4305, NULL, now() - INTERVAL '12 days', now() - INTERVAL '12 days'),
    ('Xu hướng tuyển dụng nghiêng về kỹ sư đa năng (số 30)', 'Tóm tắt ngắn cho tin số 30, đủ để quyết định có mở ra đọc hay không.', 'https://tin-tuc.example.test/bai/30', NULL, 'GITHUB', 'seed-30', 'CAREER', '["tin-tuc"]'::jsonb, 2015, NULL, now() - INTERVAL '18 days', now() - INTERVAL '18 days'),
    ('Nhiều đội quay lại monolith sau vài năm microservices (số 31)', 'Tóm tắt ngắn cho tin số 31, đủ để quyết định có mở ra đọc hay không.', 'https://tin-tuc.example.test/bai/31', NULL, 'HACKER_NEWS', 'seed-31', 'MINDSET', '["tin-tuc"]'::jsonb, 5620, NULL, now() - INTERVAL '38 days', now() - INTERVAL '38 days'),
    ('Thảo luận: có nên viết test cho mã sắp bỏ (số 32)', 'Tóm tắt ngắn cho tin số 32, đủ để quyết định có mở ra đọc hay không.', 'https://tin-tuc.example.test/bai/32', NULL, 'DEV_TO', 'seed-32', 'MINDSET', '["tin-tuc"]'::jsonb, 7257, NULL, now() - INTERVAL '7 days', now() - INTERVAL '7 days'),
    ('Một thư viện phổ biến chuyển sang quỹ mã nguồn mở (số 33)', 'Tóm tắt ngắn cho tin số 33, đủ để quyết định có mở ra đọc hay không.', 'https://tin-tuc.example.test/bai/33', NULL, 'GITHUB', 'seed-33', 'OPENSOURCE', '["tin-tuc"]'::jsonb, 7271, NULL, now() - INTERVAL '36 days', now() - INTERVAL '36 days'),
    ('Hướng dẫn xoay vòng khoá bí mật mà không gián đoạn (số 34)', 'Tóm tắt ngắn cho tin số 34, đủ để quyết định có mở ra đọc hay không.', 'https://tin-tuc.example.test/bai/34', NULL, 'HACKER_NEWS', 'seed-34', 'TOOL', '["tin-tuc"]'::jsonb, 2063, NULL, now() - INTERVAL '38 days', now() - INTERVAL '38 days'),
    ('Hội thảo kỹ thuật thường niên mở đăng ký (số 35)', 'Tóm tắt ngắn cho tin số 35, đủ để quyết định có mở ra đọc hay không.', 'https://tin-tuc.example.test/bai/35', NULL, 'DEV_TO', 'seed-35', 'EVENT', '["tin-tuc"]'::jsonb, 8572, NULL, now() - INTERVAL '37 days', now() - INTERVAL '37 days'),
    ('Bộ công cụ dòng lệnh mới cho việc dò hiệu năng (số 36)', 'Tóm tắt ngắn cho tin số 36, đủ để quyết định có mở ra đọc hay không.', 'https://tin-tuc.example.test/bai/36', NULL, 'GITHUB', 'seed-36', 'TOOL', '["tin-tuc"]'::jsonb, 7118, NULL, now() - INTERVAL '25 days', now() - INTERVAL '25 days'),
    ('Java 25 phát hành bản hỗ trợ dài hạn (số 37)', 'Tóm tắt ngắn cho tin số 37, đủ để quyết định có mở ra đọc hay không.', 'https://tin-tuc.example.test/bai/37', NULL, 'HACKER_NEWS', 'seed-37', 'NEW_TECH', '["tin-tuc"]'::jsonb, 1768, NULL, now() - INTERVAL '50 days', now() - INTERVAL '50 days'),
    ('Postgres 18 cải thiện đáng kể tốc độ VACUUM (số 38)', 'Tóm tắt ngắn cho tin số 38, đủ để quyết định có mở ra đọc hay không.', 'https://tin-tuc.example.test/bai/38', NULL, 'DEV_TO', 'seed-38', 'NEW_TECH', '["tin-tuc"]'::jsonb, 5465, NULL, now() - INTERVAL '5 days', now() - INTERVAL '5 days'),
    ('Redis đổi giấy phép lần thứ hai trong hai năm (số 39)', 'Tóm tắt ngắn cho tin số 39, đủ để quyết định có mở ra đọc hay không.', 'https://tin-tuc.example.test/bai/39', NULL, 'GITHUB', 'seed-39', 'REGULATION', '["tin-tuc"]'::jsonb, 4999, NULL, now() - INTERVAL '40 days', now() - INTERVAL '40 days'),
    ('Kubernetes bỏ hỗ trợ một API đã đánh dấu lỗi thời (số 40)', 'Tóm tắt ngắn cho tin số 40, đủ để quyết định có mở ra đọc hay không.', 'https://tin-tuc.example.test/bai/40', NULL, 'HACKER_NEWS', 'seed-40', 'NEW_TECH', '["tin-tuc"]'::jsonb, 1321, NULL, now() - INTERVAL '8 days', now() - INTERVAL '8 days');

SELECT setval('socialapp.q_trending_items_id',
              GREATEST((SELECT COALESCE(MAX(id), 0) FROM socialapp.t_trending_items), 1), true);
