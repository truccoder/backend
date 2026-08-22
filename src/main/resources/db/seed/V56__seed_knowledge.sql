-- =============================================================================================
-- Phần tri thức cá nhân: giải thích bài viết do AI sinh, kho ghi chú (vault), và personal access
-- token dùng để đồng bộ vault.
-- =============================================================================================

-- ── Giải thích bài viết (t_explanations) ───────────────────────────────────────────────────
-- Kết quả Gemini sinh ra khi người dùng bấm "giải thích bài này theo cách của tôi". Nội dung
-- được diễn đạt lại theo explanation_style trong hồ sơ nghề nghiệp của chính người đọc.
--
-- UNIQUE (post_id, user_id, version): cùng một người xin giải thích lại cùng một bài thì tạo
-- bản version mới chứ không ghi đè. Bên dưới có 4 cặp tồn tại cả version 1 lẫn version 2, để
-- màn hình lịch sử phiên bản có dữ liệu thật.
--
-- concepts / prerequisites là List<String>; external_links là
-- List<ExternalLink{title,url,reason}> — xem ExplanationResponseDto.ExternalLink.
INSERT INTO socialapp.t_explanations
    (post_id, user_id, original_content, explanation_content, concepts, prerequisites,
     external_links, complexity_score, feedback_note, version, created_at, updated_at)
SELECT p.id,
       u.id,
       p.content,
       CASE prof.explanation_style
           WHEN 'CONCISE' THEN
               'Tóm tắt ngắn: ' || left(p.content, 60) ||
               '... Ý chính là đánh đổi giữa tốc độ và độ phức tạp. Nếu chưa đo được vấn đề thì chưa cần tối ưu.'
           WHEN 'CODE_HEAVY' THEN
               'Giải thích qua ví dụ: đoạn code liên quan thường có dạng gọi một phương thức trong vòng lặp, ' ||
               'mỗi lần gọi lại phát sinh một truy vấn riêng. Cách sửa là gom lại thành một lời gọi theo lô.'
           WHEN 'ANALOGY_HEAVY' THEN
               'Hình dung thế này: giống như đi chợ mua mười món mà chạy về nhà sau mỗi món. ' ||
               'Vấn đề không nằm ở việc mua, nằm ở số lần đi lại. Gom vào một chuyến là xong.'
           ELSE
               'Giải thích chi tiết: vấn đề bắt nguồn từ cách hệ thống nạp dữ liệu theo nhu cầu thay vì nạp trước. ' ||
               'Khi số bản ghi còn ít thì không ai nhận ra, nhưng chi phí tăng tuyến tính theo số phần tử, ' ||
               'nên tới một ngưỡng nhất định là độ trễ tăng vọt. Hướng xử lý là nạp trước phần dữ liệu chắc chắn sẽ dùng.'
       END,
       (ARRAY[
           '["N+1 query","Lazy loading","Fetch join"]',
           '["Cache invalidation","TTL","Cache stampede"]',
           '["Index","Query planner","Sequential scan"]',
           '["Connection pool","Timeout","Backpressure"]',
           '["Idempotency","Retry","Exponential backoff"]'
       ])[1 + (p.id % 5)]::jsonb,
       (ARRAY[
           '["Hiểu cơ bản về ORM","Biết đọc log truy vấn"]',
           '["Biết Redis là gì","Hiểu khái niệm hết hạn dữ liệu"]',
           '["Biết đọc EXPLAIN","Hiểu B-tree ở mức khái niệm"]',
           '["Hiểu mô hình luồng của web server"]',
           '["Biết HTTP method nào an toàn để gọi lại"]'
       ])[1 + (p.id % 5)]::jsonb,
       (ARRAY[
           '[{"title":"Hibernate: Fetching strategies","url":"https://docs.jboss.org/hibernate/orm/6.4/userguide/html_single/Hibernate_User_Guide.html#fetching","reason":"Giải thích chính xác khi nào Hibernate phát sinh truy vấn phụ."}]',
           '[{"title":"Redis: Key expiration","url":"https://redis.io/docs/latest/develop/use/keyspace/","reason":"Mô tả cơ chế hết hạn thật sự hoạt động thế nào, khác với suy đoán thường gặp."}]',
           '[{"title":"PostgreSQL: Using EXPLAIN","url":"https://www.postgresql.org/docs/16/using-explain.html","reason":"Hướng dẫn đọc kế hoạch thực thi, phần cần nhất để xác nhận index có được dùng hay không."}]',
           '[{"title":"HikariCP: About Pool Sizing","url":"https://github.com/brettwooldridge/HikariCP/wiki/About-Pool-Sizing","reason":"Lý giải vì sao pool lớn hơn chưa chắc nhanh hơn."}]',
           '[{"title":"AWS: Timeouts, retries and backoff","url":"https://aws.amazon.com/builders-library/timeouts-retries-and-backoff-with-jitter/","reason":"Bài viết kinh điển về việc retry sai cách tự tạo ra sự cố."}]'
       ])[1 + (p.id % 5)]::jsonb,
       1 + (p.id % 5),
       -- Đa số chưa có phản hồi; một phần có, để luồng "giải thích này chưa rõ" có dữ liệu.
       CASE WHEN (u.id + p.id) % 5 = 0
            THEN 'Phần đầu dễ hiểu, nhưng đoạn cuối vẫn hơi trừu tượng với mình.' END,
       1,
       p.created_at + INTERVAL '2 days',
       p.created_at + INTERVAL '2 days'
  FROM socialapp.t_posts p
  JOIN socialapp.t_users u
    ON u.id BETWEEN 9001 AND 9058
   AND ((u.id * 19 + p.id * 7) % 23) < 1
  JOIN socialapp.t_user_professional_profiles prof ON prof.user_id = u.id
 WHERE p.moderation_status = 'APPROVED'
   AND p.post_type IN ('REGULAR', 'ARTICLE', 'QNA', 'CODE_SNIPPET')
   AND u.id <> p.author_id;

-- Bốn bản giải thích phiên bản 2: người dùng thấy bản đầu chưa đủ rõ nên xin diễn giải lại.
-- Chỉ tạo cho những dòng ĐÃ có phản hồi chê khó hiểu, để lịch sử phiên bản có nguyên nhân hợp lý
-- thay vì tự dưng có hai bản.
INSERT INTO socialapp.t_explanations
    (post_id, user_id, original_content, explanation_content, concepts, prerequisites,
     external_links, complexity_score, feedback_note, version, created_at, updated_at)
SELECT e.post_id,
       e.user_id,
       e.original_content,
       'Bản diễn giải lại, chia nhỏ hơn: ' || e.explanation_content ||
       ' Nói gọn trong một câu: hãy đo trước, rồi mới sửa đúng chỗ đang chậm.',
       e.concepts,
       e.prerequisites,
       e.external_links,
       GREATEST(1, e.complexity_score - 1),
       NULL,
       2,
       e.created_at + INTERVAL '1 day',
       e.created_at + INTERVAL '1 day'
  FROM socialapp.t_explanations e
 WHERE e.feedback_note IS NOT NULL
   AND e.version = 1
 ORDER BY e.id
 LIMIT 4;

-- ── Kho ghi chú cá nhân (t_vault_notes) ────────────────────────────────────────────────────
-- Ghi chú đồng bộ từ thư mục Obsidian của người dùng qua /v1/api/knowledge/sync.
-- UNIQUE (user_id, filename). `links` là danh sách tên file khác được nhắc tới trong ghi chú,
-- dạng liên kết [[wiki]] của Obsidian — chính là thứ dựng nên đồ thị ghi chú.
INSERT INTO socialapp.t_vault_notes (user_id, filename, content, tags, links, created_at, updated_at)
SELECT u.id,
       n.filename,
       n.content,
       n.tags::jsonb,
       n.links::jsonb,
       now() - ((u.id % 60) * INTERVAL '1 day'),
       now() - ((u.id % 30) * INTERVAL '1 day')
  FROM socialapp.t_users u
  CROSS JOIN (VALUES
      ('kien-truc/ghi-chu-kien-truc.md',
       '# Ghi chú kiến trúc

Nguyên tắc: chọn kiến trúc theo số người vận hành được, không theo số người mong muốn dùng.

Liên quan: [[danh-sach-doc]], [[toi-uu-truy-van]]',
       '["kiến-trúc","ghi-chú"]', '["danh-sach-doc.md","toi-uu-truy-van.md"]'),
      ('hoc-tap/danh-sach-doc.md',
       '# Danh sách cần đọc

- Designing Data-Intensive Applications
- Database Internals
- Tài liệu Postgres chương index

Đọc xong ghi lại vào [[ghi-chu-kien-truc]].',
       '["đọc","học-tập"]', '["ghi-chu-kien-truc.md"]'),
      ('ky-thuat/toi-uu-truy-van.md',
       '# Tối ưu truy vấn

Quy trình: EXPLAIN ANALYZE trước, đọc kế hoạch, tìm sequential scan trên bảng lớn, rồi mới thêm index.

Đừng thêm index theo cảm tính — mỗi index là chi phí cho mọi lần ghi.',
       '["postgres","hiệu-năng"]', '[]'),
      ('nhat-ky/nhat-ky-tuan.md',
       '# Nhật ký tuần

Việc đã xong, việc còn vướng, và một điều học được.
Mục tiêu là viết ngắn, đều đặn hơn là viết dài rồi bỏ.',
       '["nhật-ký"]', '[]')
  ) AS n(filename, content, tags, links)
 WHERE u.id BETWEEN 9001 AND 9058
   -- Khoảng một phần ba số người có bật đồng bộ vault.
   AND u.id % 3 = 0;

-- ── Đẩy sequence qua vùng id tường minh ────────────────────────────────────────────────────
SELECT setval('socialapp.q_explanations_id',
              (SELECT COALESCE(MAX(id), 1) FROM socialapp.t_explanations), true);
SELECT setval('socialapp.q_vault_notes_id',
              (SELECT COALESCE(MAX(id), 1) FROM socialapp.t_vault_notes), true);
