-- =============================================================================================
-- Remediation: vô hiệu hoá các tài khoản do migration seed tạo ra trên database production.
--
-- Bối cảnh: tới trước 2026-08-18, V20/V21/V25/V29/V30 nằm trong db/migration nên Flyway apply
-- chúng vào MỌI database, kể cả production. Việc chuyển chúng sang db/seed (xem
-- src/main/resources/db/seed/README.md) chỉ ngăn database MỚI bị seed — những hàng đã được tạo
-- trên database đang chạy thì vẫn còn nguyên. Script này xử lý phần đó.
--
-- Ba đường tấn công cần đóng, không chỉ một:
--   1. Mật khẩu — `12345678a` (admin) và `12345678` (user) được ghi thẳng trong comment của file
--      migration trong repo.
--   2. Quyền — hai tài khoản seed có role = ADMIN: duyệt/từ chối kiểm duyệt, ban user, tạo roadmap.
--   3. Email — `socialapp.com` là domain có thật và KHÔNG thuộc sở hữu của dự án. Ai kiểm soát
--      hòm thư ở domain đó có thể bấm "quên mật khẩu" hoặc magic-link cho admin1@socialapp.com và
--      chiếm tài khoản, kể cả sau khi mật khẩu đã bị đổi. Vì vậy STEP 2 đổi luôn địa chỉ email.
--
-- CÁCH CHẠY: đọc kết quả STEP 1 trước, rồi mới chạy STEP 2. Đừng chạy cả file một lượt.
-- Mọi lệnh ghi đều nằm trong transaction tường minh để có thể ROLLBACK.
--
-- Kết nối (Supabase pooler — xem DATN-infra/docker/docker-compose.prod.yml):
--   psql "postgresql://postgres.rjauctcerrnmacrhaixx:<DB_PASSWORD>@aws-1-ap-south-1.pooler.supabase.com:5432/postgres?sslmode=require"
-- =============================================================================================


-- ---------------------------------------------------------------------------------------------
-- STEP 1 — KIỂM TRA (chỉ đọc, chạy trước và đọc kỹ kết quả)
--
-- Nếu cả ba truy vấn đều trả về 0 hàng thì production chưa từng bị seed và không cần làm gì thêm.
-- ---------------------------------------------------------------------------------------------

-- 1a. Các tài khoản seed còn tồn tại.
SELECT id,
       email,
       username,
       role,
       email_verified,
       auth_provider,
       created_at,
       -- Mật khẩu đã bị vô hiệu hoá bởi lần chạy trước chưa?
       (password LIKE 'DISABLED-%') AS already_disabled
  FROM socialapp.t_users
 WHERE email IN ('admin1@socialapp.com', 'admin2@socialapp.com')
    OR email LIKE '%@test.com'
    OR id BETWEEN 9001 AND 9025
 ORDER BY role DESC, id;

-- 1b. Còn tài khoản ADMIN nào KHÔNG phải seed không?
--
-- Quan trọng: nếu truy vấn này trả về 0 hàng thì admin1/admin2 đang là admin duy nhất, và hạ
-- quyền chúng ở STEP 2 sẽ khiến hệ thống không còn ai duyệt kiểm duyệt được. Làm STEP 3 (tạo
-- admin thật) TRƯỚC trong trường hợp đó.
SELECT id, email, username, created_at
  FROM socialapp.t_users
 WHERE role = 'ADMIN'
   AND email NOT IN ('admin1@socialapp.com', 'admin2@socialapp.com')
 ORDER BY id;

-- 1c. Bài viết rác do V21 chèn vào hàng chờ kiểm duyệt.
SELECT p.id, p.author_id, p.moderation_status, left(p.content, 60) AS content_preview
  FROM socialapp.t_posts p
 WHERE p.content IN (
           'This deal is insane, buy now buy now buy now!!!',
           'You are so stupid and worthless, nobody likes you'
       );


-- ---------------------------------------------------------------------------------------------
-- STEP 2 — VÔ HIỆU HOÁ (ghi dữ liệu)
--
-- Cố ý dùng UPDATE chứ không DELETE. Nhiều bảng tham chiếu t_users mà KHÔNG có ON DELETE CASCADE
-- (t_comments.author_id ở V19, t_project_positions/t_position_applications ở V36,
-- t_post_tags ở V8), nên nếu một tài khoản seed đã kịp có hoạt động thì DELETE sẽ nổ lỗi khoá
-- ngoại giữa chừng. Vô hiệu hoá thì luôn thành công và đạt đúng mục tiêu: không đăng nhập được,
-- không còn quyền, không khôi phục được qua email.
--
-- Việc xoá hẳn để dọn dẹp có thể làm sau, khi không còn vội — xem STEP 4.
-- ---------------------------------------------------------------------------------------------

BEGIN;

UPDATE socialapp.t_users
   SET -- Không khớp BCrypt pattern nên BCryptPasswordEncoder.matches() trả false ngay, không cần
       -- biết mật khẩu cũ là gì. Để lại dấu vết đọc được thay vì NULL, để lần chạy sau nhận ra.
       password       = 'DISABLED-SEED-ACCOUNT-2026-08-18',
       -- Hạ quyền: đây là thứ biến rò rỉ mật khẩu thành chiếm quyền quản trị.
       role           = 'USER',
       -- Cắt đường khôi phục qua email. `.invalid` là TLD dành riêng theo RFC 2606, bảo đảm không
       -- định tuyến được, nên không ai nhận được link reset/magic-link cho các tài khoản này.
       -- Kèm id để không đụng ràng buộc UNIQUE trên email.
       email          = 'disabled-seed-' || id || '@invalid',
       email_verified = FALSE,
       updated_at     = now()
 WHERE (
           email IN ('admin1@socialapp.com', 'admin2@socialapp.com')
        OR email LIKE '%@test.com'
        OR id BETWEEN 9001 AND 9025
       )
   -- Idempotent: chạy lại lần hai không đụng vào hàng đã xử lý.
   AND password NOT LIKE 'DISABLED-%';

-- Thu hồi mọi refresh token đang sống của các tài khoản đó — đổi mật khẩu KHÔNG tự làm việc này,
-- nên một phiên đăng nhập đã mở từ trước vẫn tiếp tục dùng được nếu bỏ qua bước này.
DELETE FROM socialapp.t_refresh_tokens
 WHERE user_id IN (
           SELECT id FROM socialapp.t_users WHERE email LIKE 'disabled-seed-%@invalid'
       );

DELETE FROM socialapp.t_password_reset_tokens
 WHERE user_id IN (
           SELECT id FROM socialapp.t_users WHERE email LIKE 'disabled-seed-%@invalid'
       );

DELETE FROM socialapp.t_magic_link_tokens
 WHERE user_id IN (
           SELECT id FROM socialapp.t_users WHERE email LIKE 'disabled-seed-%@invalid'
       );

DELETE FROM socialapp.t_email_verification_tokens
 WHERE user_id IN (
           SELECT id FROM socialapp.t_users WHERE email LIKE 'disabled-seed-%@invalid'
       );

-- Kiểm tra lại NGAY TRONG transaction, trước khi commit.
-- Kỳ vọng: 0 hàng.
SELECT id, email, role
  FROM socialapp.t_users
 WHERE role = 'ADMIN'
   AND password NOT LIKE 'DISABLED-%'
   AND email LIKE '%socialapp.com';

-- Hài lòng với kết quả thì:
COMMIT;
-- Không hài lòng thì: ROLLBACK;


-- ---------------------------------------------------------------------------------------------
-- STEP 3 — Tạo tài khoản admin thật
--
-- Không seed admin bằng SQL nữa. Cách làm đúng, không có mật khẩu nào lọt vào git:
--
--   1. Đăng ký bình thường qua API bằng email công việc có thật:
--        POST /v1/api/auth/register
--      Mật khẩu do người đó tự đặt và không bao giờ xuất hiện ở đâu ngoài trình duyệt của họ.
--
--   2. Xác thực email theo luồng bình thường.
--
--   3. Nâng quyền đúng một tài khoản đó, thay <email> bên dưới:
--
--        BEGIN;
--        UPDATE socialapp.t_users
--           SET role = 'ADMIN', updated_at = now()
--         WHERE email = '<email>'
--           AND email_verified = TRUE;
--        -- Kỳ vọng UPDATE 1. Nếu là 0 thì email sai hoặc chưa xác thực.
--        COMMIT;
--
--   4. Ghi lại ai được cấp quyền admin và vào lúc nào, ở nơi nhóm cùng đọc được.
-- ---------------------------------------------------------------------------------------------


-- ---------------------------------------------------------------------------------------------
-- STEP 4 — Dọn dẹp (tuỳ chọn, không gấp)
--
-- Chỉ chạy khi muốn xoá hẳn dữ liệu rác, và chấp nhận là có thể vướng khoá ngoại. Transaction
-- bảo đảm hoặc xoá được trọn vẹn, hoặc không đụng gì tới dữ liệu.
-- ---------------------------------------------------------------------------------------------

-- 4a. Bài viết rác trong hàng chờ kiểm duyệt (V21).
-- BEGIN;
-- DELETE FROM socialapp.t_moderation_logs
--  WHERE post_id IN (
--            SELECT id FROM socialapp.t_posts
--             WHERE content IN (
--                       'This deal is insane, buy now buy now buy now!!!',
--                       'You are so stupid and worthless, nobody likes you'
--                   )
--        );
-- DELETE FROM socialapp.t_posts
--  WHERE content IN (
--            'This deal is insane, buy now buy now buy now!!!',
--            'You are so stupid and worthless, nobody likes you'
--        );
-- COMMIT;

-- 4b. Xoá hẳn các tài khoản seed chưa từng có hoạt động.
-- Nếu lệnh này báo lỗi khoá ngoại thì nghĩa là tài khoản đó CÓ hoạt động — cứ để nguyên ở trạng
-- thái đã vô hiệu hoá ở STEP 2, như vậy là đủ an toàn.
-- BEGIN;
-- DELETE FROM socialapp.t_users
--  WHERE email LIKE 'disabled-seed-%@invalid';
-- COMMIT;
