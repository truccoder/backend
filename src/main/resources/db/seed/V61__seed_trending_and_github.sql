-- =============================================================================================
-- Tin xu hướng (do trình thu thập tạo ra) và thống kê GitHub đã đồng bộ.
--
-- Hai bảng cuối cùng của bộ seed. Chúng không phụ thuộc vào nhau, cũng không bảng nào tham
-- chiếu tới chúng.
--
-- BA BẢNG CỐ Ý KHÔNG SEED — quyết định, không phải bỏ sót:
--
--   t_refresh_tokens, t_password_reset_tokens, t_magic_link_tokens,
--   t_email_verification_tokens
--     Đây là vật phẩm tạm của luồng xác thực, sống vài phút tới vài ngày. Một refresh token
--     nằm sẵn trong file SQL là một credential dùng được, commit vào repo — hệt vấn đề mà bộ
--     seed thế hệ trước mắc phải với mật khẩu admin. Muốn có token thì đăng nhập, mất hai giây.
--
--   t_google_calendar_tokens
--     Chứa access token và refresh token OAuth của Google. Không thể bịa ra giá trị dùng được;
--     điền giá trị giả thì tài khoản hiện trạng thái "đã kết nối Google Calendar" nhưng mọi lần
--     đồng bộ đều thất bại, và người thử tính năng sẽ đi tìm lỗi trong code. Để trống thì luồng
--     kết nối chạy đúng từ đầu.
-- =============================================================================================

-- ── Tin xu hướng ───────────────────────────────────────────────────────────────────────────
-- Do TrendingCrawlScheduler thu thập từ Hacker News, dev.to và GitHub.
-- UNIQUE (source, source_id) là cơ chế chống trùng khi thu thập lại, nên source_id phải là định
-- danh bên nguồn chứ không phải một số tăng dần tự đặt.
--
-- TrendingSource: HACKER_NEWS, DEV_TO, GITHUB.
-- TrendingCategory: OPENSOURCE, EVENT, NEW_TECH, REGULATION, MINDSET, TOOL, CAREER, OTHER.
-- image_url để NULL: ảnh nằm ở máy chủ bên ngoài, để lâu là link chết.
INSERT INTO socialapp.t_trending_items
    (title, summary, url, image_url, source, source_id, category, tags, score, author, published_at, crawled_at) VALUES
    ('Postgres 17 cải thiện đáng kể tốc độ VACUUM',
     'Bản phát hành mới giảm bộ nhớ mà quá trình dọn dẹp cần, và rút ngắn thời gian chạy trên bảng lớn.',
     'https://www.postgresql.org/about/news/', NULL, 'HACKER_NEWS', 'hn-40851234', 'NEW_TECH',
     '["postgres","database","performance"]'::jsonb, 842, 'pgsql-announce', now() - INTERVAL '2 days', now() - INTERVAL '2 days'),
    ('Virtual threads sau một năm dùng thật',
     'Tổng kết những chỗ virtual threads giúp ích rõ rệt và những chỗ vẫn nên dùng luồng nền tảng.',
     'https://openjdk.org/jeps/444', NULL, 'HACKER_NEWS', 'hn-40799871', 'NEW_TECH',
     '["java","concurrency"]'::jsonb, 617, 'jdk-dev', now() - INTERVAL '4 days', now() - INTERVAL '4 days'),
    ('Vì sao đội chúng tôi quay lại monolith',
     'Câu chuyện một đội mười người rời microservices và giảm được phần lớn chi phí vận hành.',
     'https://dev.to/', NULL, 'DEV_TO', 'devto-1839221', 'MINDSET',
     '["architecture","microservices"]'::jsonb, 394, 'anna_dev', now() - INTERVAL '5 days', now() - INTERVAL '5 days'),
    ('Bộ công cụ quan sát mã nguồn mở đáng thử năm nay',
     'So sánh vài lựa chọn thay thế cho bộ công cụ thương mại, kèm chi phí vận hành ước tính.',
     'https://github.com/topics/observability', NULL, 'GITHUB', 'gh-obs-2026', 'TOOL',
     '["observability","opensource"]'::jsonb, 528, 'oss-weekly', now() - INTERVAL '6 days', now() - INTERVAL '6 days'),
    ('Quy định mới về bảo vệ dữ liệu cá nhân có hiệu lực',
     'Doanh nghiệp xử lý dữ liệu người dùng cần rà soát lại quy trình thu thập và lưu trữ.',
     'https://gdpr.eu/', NULL, 'HACKER_NEWS', 'hn-40712009', 'REGULATION',
     '["privacy","compliance"]'::jsonb, 271, 'policy-watch', now() - INTERVAL '8 days', now() - INTERVAL '8 days'),
    ('Hội nghị kỹ thuật thường niên mở đăng ký',
     'Sự kiện hai ngày với các chủ đề về hạ tầng, dữ liệu và trải nghiệm lập trình viên.',
     'https://dev.to/', NULL, 'DEV_TO', 'devto-1841007', 'EVENT',
     '["conference","community"]'::jsonb, 188, 'conf_team', now() - INTERVAL '9 days', now() - INTERVAL '9 days'),
    ('Thư viện kiểm thử mới đạt 20 nghìn sao',
     'Cách tiếp cận khác biệt ở phần cô lập môi trường giúp test bớt đỏ ngẫu nhiên.',
     'https://github.com/topics/testing', NULL, 'GITHUB', 'gh-test-8821', 'OPENSOURCE',
     '["testing","opensource"]'::jsonb, 733, 'gh-trending', now() - INTERVAL '10 days', now() - INTERVAL '10 days'),
    ('Lộ trình chuyển từ lập trình viên sang quản lý kỹ thuật',
     'Ghi chép thực tế về những kỹ năng phải học lại từ đầu khi đổi vai trò.',
     'https://dev.to/', NULL, 'DEV_TO', 'devto-1836554', 'CAREER',
     '["career","leadership"]'::jsonb, 452, 'minh_le', now() - INTERVAL '12 days', now() - INTERVAL '12 days'),
    ('Trình biên dịch mới giảm một nửa thời gian build',
     'Kết quả đo trên vài dự án lớn, kèm hướng dẫn thử trên dự án của bạn.',
     'https://github.com/topics/compiler', NULL, 'GITHUB', 'gh-compiler-551', 'TOOL',
     '["build","tooling"]'::jsonb, 611, 'gh-trending', now() - INTERVAL '14 days', now() - INTERVAL '14 days'),
    ('Bàn về việc đo năng suất lập trình viên',
     'Vì sao đếm số dòng code hay số commit đều dẫn tới hành vi không mong muốn.',
     'https://dev.to/', NULL, 'DEV_TO', 'devto-1829903', 'MINDSET',
     '["productivity","management"]'::jsonb, 366, 'thu_nguyen', now() - INTERVAL '16 days', now() - INTERVAL '16 days'),
    ('Chuẩn mới cho xác thực không mật khẩu được áp dụng rộng',
     'Passkey dần thay thế mật khẩu ở các dịch vụ lớn, kèm hướng dẫn tích hợp.',
     'https://fidoalliance.org/passkeys/', NULL, 'HACKER_NEWS', 'hn-40688142', 'NEW_TECH',
     '["security","authentication"]'::jsonb, 925, 'sec-daily', now() - INTERVAL '18 days', now() - INTERVAL '18 days'),
    ('Bộ dữ liệu mở tiếng Việt cho xử lý ngôn ngữ',
     'Tập dữ liệu được gán nhãn thủ công, phát hành theo giấy phép cho phép dùng thương mại.',
     'https://github.com/topics/vietnamese', NULL, 'GITHUB', 'gh-vi-nlp-77', 'OPENSOURCE',
     '["nlp","vietnamese","dataset"]'::jsonb, 489, 'vn-ai-group', now() - INTERVAL '20 days', now() - INTERVAL '20 days');

-- ── Thống kê GitHub đã đồng bộ ─────────────────────────────────────────────────────────────
-- Chỉ tạo cho người đăng nhập bằng GITHUB, vì bản ghi này sinh ra từ chính luồng OAuth đó.
-- UNIQUE (user_id) — mỗi tài khoản liên kết một tài khoản GitHub.
--
-- access_token để NULL, có chủ đích: GithubSyncScheduler chạy mỗi 60 giây và gọi API GitHub bằng
-- token này. Một token bịa ra sẽ khiến scheduler gọi thật, nhận 401, rồi ghi lỗi vào log mỗi
-- phút cho tới khi ai đó tắt ứng dụng. Để NULL thì bản ghi vẫn hiển thị được trên hồ sơ nhưng
-- không kích hoạt lần gọi mạng nào.
--
-- Việc để NULL chỉ chặn được lần gọi mạng, không chặn được scheduler *chọn* bản ghi: sau 24 giờ
-- last_synced_at bên dưới thành cũ, scheduler lấy đúng các hàng seed này rồi ghi ERROR "no stored
-- access token" mỗi phút. Vì vậy GithubStatsRepository.findUsersToSync đã lọc thêm
-- access_token IS NOT NULL — đừng bỏ điều kiện đó nếu vẫn giữ cách seed này.
--
-- pinned_repos_json và contribution_graph_json là JsonNode tự do — app không đọc từng trường mà
-- chuyển thẳng ra ngoài. Hình dạng dưới đây sao chép đúng phản hồi GraphQL của GitHub mà
-- GithubApiClient lấy về (nodes của pinnedItems, và contributionCalendar), để giao diện dựng cho
-- dữ liệu thật cũng hiển thị được dữ liệu seed.
INSERT INTO socialapp.t_user_github_stats
    (user_id, github_username, access_token, public_repos_count, followers_count,
     pinned_repos_json, contribution_graph_json, last_synced_at, created_at, updated_at)
SELECT u.id,
       replace(u.username, '_', '-'),
       NULL,
       12 + (u.id % 40),
       30 + (u.id % 250),
       jsonb_build_array(
           jsonb_build_object(
               'name', 'awesome-' || split_part(u.username, '_', 1),
               'description', 'Tập hợp tài nguyên và ví dụ mà tôi dùng hằng ngày.',
               'url', 'https://github.com/' || replace(u.username, '_', '-') || '/awesome-' || split_part(u.username, '_', 1),
               'stargazerCount', 40 + (u.id % 900),
               'forkCount', 5 + (u.id % 120),
               'primaryLanguage', jsonb_build_object('name', 'Java', 'color', '#b07219')),
           jsonb_build_object(
               'name', 'seed-toolkit',
               'description', 'Bộ script nhỏ tự động hoá công việc lặp lại.',
               'url', 'https://github.com/' || replace(u.username, '_', '-') || '/seed-toolkit',
               'stargazerCount', 10 + (u.id % 300),
               'forkCount', 2 + (u.id % 40),
               'primaryLanguage', jsonb_build_object('name', 'TypeScript', 'color', '#3178c6'))
       ),
       -- contributionCalendar: totalContributions + weeks[].contributionDays[]. Dựng 12 tuần gần
       -- nhất, mỗi tuần 7 ngày, để biểu đồ đóng góp có hình dạng thật thay vì một ô trống.
       (SELECT jsonb_build_object(
                   'totalContributions', sum(d.cnt),
                   'weeks', jsonb_agg(d.week ORDER BY d.wk))
          FROM (SELECT wk,
                       sum((day ->> 'contributionCount')::int) AS cnt,
                       jsonb_build_object('contributionDays', jsonb_agg(day ORDER BY dy)) AS week
                  FROM generate_series(0, 11) AS wk
                 CROSS JOIN LATERAL generate_series(0, 6) AS dy
                 CROSS JOIN LATERAL (
                     SELECT jsonb_build_object(
                                'date', to_char((now() - ((11 - wk) * 7 + (6 - dy)) * INTERVAL '1 day')::date, 'YYYY-MM-DD'),
                                'contributionCount', c.n,
                                'color', CASE
                                    WHEN c.n = 0 THEN '#ebedf0'
                                    WHEN c.n < 3 THEN '#9be9a8'
                                    WHEN c.n < 6 THEN '#40c463'
                                    WHEN c.n < 9 THEN '#30a14e'
                                    ELSE '#216e39' END) AS day
                       FROM (SELECT ((u.id * 7 + wk * 13 + dy * 3) % 11) AS n) c
                 ) AS built
                 GROUP BY wk) d),
       now() - ((u.id % 5) * INTERVAL '1 hour'),
       now() - INTERVAL '120 days',
       now() - ((u.id % 5) * INTERVAL '1 hour')
  FROM socialapp.t_users u
 WHERE u.auth_provider = 'GITHUB';

SELECT setval('socialapp.q_trending_items_id',
              (SELECT COALESCE(MAX(id), 1) FROM socialapp.t_trending_items), true);
SELECT setval('socialapp.q_user_github_stats_id',
              (SELECT COALESCE(MAX(id), 1) FROM socialapp.t_user_github_stats), true);
