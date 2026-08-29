-- =============================================================================================
-- Seed generation 3 — dọn sạch mọi thế hệ seed trước khi nạp bộ 500 người dùng.
--
-- KHÔNG PHẢI FILE SINH TỰ ĐỘNG. Viết tay, vì nó nói về dữ liệu CŨ chứ không về dữ liệu mới, và
-- generator thì không biết gì về những thế hệ đã đi qua.
--
-- Vì sao cần bước reset. Hai thế hệ seed trước đã chạy trên các database dev đang tồn tại:
--   thế hệ 1 (V20/V21/V25/V29/V30, đã xoá khỏi repo)  — dải id 9001-9025
--   thế hệ 2 (V50-V79, xoá cùng đợt này)              — dải id 9001-9060, post 5001+
-- Bộ mới chiếm 9001-9500, tức là TRÙM LÊN cả hai. Nạp đè mà không dọn thì mọi INSERT có id
-- tường minh đụng khoá chính ngay ở hàng đầu tiên. Trên database sạch, file này là no-op.
--
-- Trên production quy trình là drop schema rồi migrate lại từ V1, nên file này cũng no-op ở đó.
-- Nó tồn tại cho những máy dev KHÔNG drop schema — tức là gần như mọi máy dev.
-- =============================================================================================

-- Ba bảng tham chiếu t_users mà KHÔNG có ON DELETE CASCADE. Đã rà lại toàn bộ db/migration
-- (28/08): chỉ đúng ba chỗ này, mọi khoá ngoại khác trỏ về t_users đều CASCADE hoặc SET NULL.
-- Không dọn trước thì DELETE ở cuối vướng khoá ngoại và cả migration đổ.
--   t_comments.author_id             (V19)
--   t_projects.author_id             (V36)
--   t_project_applications.applicant_id (V36)
DELETE FROM socialapp.t_comments
 WHERE author_id BETWEEN 9001 AND 9599;

DELETE FROM socialapp.t_project_applications
 WHERE applicant_id BETWEEN 9001 AND 9599
    OR project_id IN (SELECT id FROM socialapp.t_projects WHERE author_id BETWEEN 9001 AND 9599);

DELETE FROM socialapp.t_project_positions
 WHERE project_id IN (SELECT id FROM socialapp.t_projects WHERE author_id BETWEEN 9001 AND 9599);

DELETE FROM socialapp.t_projects
 WHERE author_id BETWEEN 9001 AND 9599;

-- Bài của thế hệ 1 được nhận diện bằng NỘI DUNG chứ không bằng tác giả: V21 chèn qua JOIN theo
-- username nên tác giả của chúng có thể nằm ngoài dải id ở trên.
DELETE FROM socialapp.t_posts
 WHERE content IN (
           'This deal is insane, buy now buy now buy now!!!',
           'You are so stupid and worthless, nobody likes you'
       );

-- Dữ liệu danh mục không gắn với user nào — bộ seed mới dựng lại từ đầu.
DELETE FROM socialapp.t_user_roadmap_progress;
DELETE FROM socialapp.t_roadmap_nodes;
DELETE FROM socialapp.t_roadmaps;
DELETE FROM socialapp.t_trending_items;
DELETE FROM socialapp.t_post_hashtags;
DELETE FROM socialapp.t_hashtags;

-- Cuối cùng: xoá user. Cascade kéo theo posts, reactions, comments đã dọn ở trên, books,
-- notifications, hồ sơ nghề nghiệp, tuỳ chọn thông báo, block, báo cáo, sự kiện uy tín…
--
-- Dải quét tới 9599 chứ không phải 9500: rộng hơn dải bộ seed mới đúng 99 chỗ, để nếu sau này
-- ai đó nới bộ seed thêm vài chục tài khoản thì file reset không phải sửa theo.
--
-- Ba mệnh đề email dọn nốt các thế hệ trước, vốn dùng tên miền khác:
--   @socialapp.com / @test.com  — thế hệ 1
--   @seed.test                  — thế hệ 2
-- Bộ hiện tại dùng @elitenexus.test và đã nằm trong dải id ở trên.
DELETE FROM socialapp.t_users
 WHERE id BETWEEN 9001 AND 9599
    OR email IN ('admin1@socialapp.com', 'admin2@socialapp.com')
    OR email LIKE '%@test.com'
    OR email LIKE '%@seed.test';

-- Trả các sequence về mốc an toàn.
--
-- Bộ seed mới cấp id TƯỜNG MINH cho users, posts, comments, explanations, notifications và
-- reputation_events, rồi tự setval ở cuối mỗi file tương ứng. Những sequence dưới đây là các
-- sequence mà bộ seed để Postgres tự cấp — chúng phải bắt đầu từ chỗ không đụng dữ liệu mà một
-- lập trình viên đã tạo bằng tay trên máy mình.
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
