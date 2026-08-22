-- =============================================================================================
-- Seed generation 2 — dọn sạch thế hệ seed cũ trước khi nạp bộ mới.
--
-- CHỈ CHẠY Ở MÁY DEV. File này nằm trong db/seed, không được Flyway load trừ khi
-- FLYWAY_LOCATIONS trỏ tới đây — xem src/main/resources/db/seed/README.md.
--
-- Vì sao cần bước reset: bộ seed cũ (V20/V21/V25/V29/V30, đã xoá khỏi repo) đã chạy trên các
-- database dev đang tồn tại và chiếm dải id 9001-9025 với dữ liệu khác hẳn thiết kế mới. Nếu
-- nạp đè, mọi INSERT có id tường minh sẽ đụng khoá chính. Database sạch thì file này là no-op.
--
-- Xoá theo dải id 9001-9099 và theo email của thế hệ cũ. Hầu hết bảng con có ON DELETE CASCADE
-- từ t_users nên xoá user là kéo theo; các bảng KHÔNG cascade được xử lý tường minh phía trên.
-- =============================================================================================

-- Các bảng tham chiếu t_users mà KHÔNG có ON DELETE CASCADE — phải tự dọn trước, nếu không
-- DELETE ở cuối sẽ vướng khoá ngoại:
--   t_comments.author_id (V19), t_projects.author_id + t_project_applications.applicant_id (V36)
DELETE FROM socialapp.t_comments
 WHERE author_id BETWEEN 9001 AND 9099;

DELETE FROM socialapp.t_project_applications
 WHERE applicant_id BETWEEN 9001 AND 9099
    OR project_id IN (SELECT id FROM socialapp.t_projects WHERE author_id BETWEEN 9001 AND 9099);

DELETE FROM socialapp.t_project_positions
 WHERE project_id IN (SELECT id FROM socialapp.t_projects WHERE author_id BETWEEN 9001 AND 9099);

DELETE FROM socialapp.t_projects
 WHERE author_id BETWEEN 9001 AND 9099;

-- t_posts.author_id CÓ cascade, nhưng bài của thế hệ cũ được nhận diện bằng nội dung chứ không
-- bằng tác giả (V21 chèn qua JOIN theo username), nên dọn riêng cho chắc.
DELETE FROM socialapp.t_posts
 WHERE content IN (
           'This deal is insane, buy now buy now buy now!!!',
           'You are so stupid and worthless, nobody likes you'
       );

-- Dữ liệu không gắn với user nào: bảng danh mục dùng chung, seed lại từ đầu ở các file sau.
DELETE FROM socialapp.t_user_roadmap_progress;
DELETE FROM socialapp.t_roadmap_nodes;
DELETE FROM socialapp.t_roadmaps;
DELETE FROM socialapp.t_trending_items;
DELETE FROM socialapp.t_post_hashtags;
DELETE FROM socialapp.t_hashtags;

-- Cuối cùng: xoá user, cascade kéo theo posts/reactions/comments-đã-dọn/books/notifications/...
DELETE FROM socialapp.t_users
 WHERE id BETWEEN 9001 AND 9099
    OR email IN ('admin1@socialapp.com', 'admin2@socialapp.com')
    OR email LIKE '%@test.com'
    OR email LIKE '%@seed.test';

-- Trả các sequence dùng chung về mốc an toàn. Bộ seed mới cấp id tường minh cho t_users
-- (9001-9060) nhưng để sequence tự cấp cho posts/comments/books/..., nên các sequence đó phải
-- bắt đầu từ một chỗ không đụng dữ liệu còn sót lại của lập trình viên.
SELECT setval('socialapp.q_posts_id',
              GREATEST((SELECT COALESCE(MAX(id), 0) FROM socialapp.t_posts), 1), true);
SELECT setval('socialapp.q_comments_id',
              GREATEST((SELECT COALESCE(MAX(id), 0) FROM socialapp.t_comments), 1), true);
SELECT setval('socialapp.q_books_id',
              GREATEST((SELECT COALESCE(MAX(id), 0) FROM socialapp.t_books), 1), true);
SELECT setval('socialapp.q_notifications_id',
              GREATEST((SELECT COALESCE(MAX(id), 0) FROM socialapp.t_notifications), 1), true);
SELECT setval('socialapp.q_friend_requests_id',
              GREATEST((SELECT COALESCE(MAX(id), 0) FROM socialapp.t_friend_requests), 1), true);
