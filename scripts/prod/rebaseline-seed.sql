-- =============================================================================================
-- RE-BASELINE bộ seed trên production — chạy MỘT LẦN, ngay trước deploy đầu tiên ship bản seed
-- sinh ngày 2026-08-30.
--
-- Vì sao cần. Tới deploy `f9aeea7`, production đã migrate qua V80–V92 và Flyway ghi checksum của
-- từng file vào `flyway_schema_history`. Bản seed mới sinh lại TOÀN BỘ V81–V92 một lượt (V88 nay
-- mang parent_node_id cho cây lộ trình, và RNG dịch nên mọi file phía sau đổi theo) — checksum
-- không còn khớp. `application-prod.yml` bật `validate-on-migrate: true`, nên deploy kế tiếp sẽ
-- CHẾT ĐỨNG ở V88 với "Migration checksum mismatch for migration version 88" rồi tự rollback.
-- Đúng chuyện này đã xảy ra một lần (commit 9dd89ab).
--
-- Cách xử lý: xoá sạch schema `socialapp` để lần khởi động kế tiếp Flyway migrate lại từ V1 —
-- gồm cả bộ seed mới, với checksum mới. Dữ liệu người dùng thật trên production (nếu có) sẽ MẤT;
-- xem STEP 1 để cân nhắc, và STEP 0 để sao lưu.
--
-- CÁCH CHẠY: đọc kết quả STEP 1 trước và quyết định. Đừng chạy cả file một lượt.
--
-- Kết nối (Supabase pooler — xem DATN-infra/docker/docker-compose.prod.yml):
--   psql "postgresql://postgres.rjauctcerrnmacrhaixx:<DB_PASSWORD>@aws-1-ap-south-1.pooler.supabase.com:5432/postgres?sslmode=require"
-- =============================================================================================


-- ---------------------------------------------------------------------------------------------
-- STEP 0 — SAO LƯU (chạy từ shell, KHÔNG phải trong psql)
--
--   pg_dump "postgresql://postgres.<ref>:<pw>@<host>:5432/postgres?sslmode=require" \
--     --schema=socialapp --no-owner --no-privileges \
--     -f rebaseline-backup-$(date +%Y%m%d-%H%M).sql
--
-- Giữ file này cho tới khi buổi demo xong. Khôi phục: tạo lại schema rỗng rồi `psql < file`.
-- ---------------------------------------------------------------------------------------------


-- ---------------------------------------------------------------------------------------------
-- STEP 1 — KIỂM TRA có dữ liệu thật đáng giữ không (chỉ đọc)
--
-- Nếu tất cả đều là seed (id người dùng trong dải 9001–9599, email @elitenexus.test / @seed.test,
-- tài khoản admin seed) thì drop schema là an toàn. Có tài khoản NGOÀI dải seed với hoạt động
-- thật thì dừng lại và bàn với nhóm trước.
-- ---------------------------------------------------------------------------------------------

-- 1a. Người dùng KHÔNG thuộc bất kỳ thế hệ seed nào.
SELECT id, email, username, role, created_at
  FROM socialapp.t_users
 WHERE id NOT BETWEEN 9001 AND 9599
   AND email NOT LIKE '%@elitenexus.test'
   AND email NOT LIKE '%@seed.test'
   AND email NOT LIKE '%@test.com'
   AND email NOT LIKE 'disabled-seed-%@invalid'
 ORDER BY created_at;

-- 1b. Phiên bản migration production đang ở.
SELECT installed_rank, version, description, checksum, installed_on, success
  FROM socialapp.flyway_schema_history
 ORDER BY installed_rank DESC
 LIMIT 15;

-- 1c. Có giao dịch mua sách THẬT (COMPLETED, người mua ngoài dải seed) không.
SELECT bp.id, bp.buyer_id, bp.book_id, bp.amount, bp.payment_status, bp.paid_at
  FROM socialapp.t_book_purchases bp
 WHERE bp.payment_status = 'COMPLETED'
   AND bp.buyer_id NOT BETWEEN 9001 AND 9599;


-- ---------------------------------------------------------------------------------------------
-- STEP 2 — DROP SCHEMA
--
-- Chỉ chạy sau khi STEP 0 xong và STEP 1 xác nhận không có gì đáng giữ.
--
-- Không dùng `flyway clean`: nó bị tắt mặc định (`spring.flyway.clean-disabled=true`) và bật lại
-- chỉ để chạy một lần là mở một khẩu súng đã lên đạn trong cấu hình production. `DROP SCHEMA` ở
-- đây làm đúng một việc, tường minh, trong một transaction có thể ROLLBACK tới phút chót.
-- ---------------------------------------------------------------------------------------------

BEGIN;

DROP SCHEMA IF EXISTS socialapp CASCADE;

-- Flyway sẽ tự tạo lại schema khi migrate (createSchemas=true / spring.flyway.create-schemas).
-- Kiểm tra lại NGAY trong transaction:
SELECT count(*) AS schema_con_lai
  FROM information_schema.schemata
 WHERE schema_name = 'socialapp';
-- Kỳ vọng: 0.

-- Hài lòng thì:
COMMIT;
-- Không thì: ROLLBACK;


-- ---------------------------------------------------------------------------------------------
-- STEP 3 — DEPLOY
--
-- Ngay sau khi COMMIT, chạy deploy như bình thường (push lên main hoặc "Run workflow"). Backend
-- khởi động, Flyway thấy schema rỗng và migrate lại từ V1 tới file mới nhất — gồm cả bộ seed.
-- Lần khởi động này lâu hơn ~25s (SeedMigrationTest đo cùng bộ trên Testcontainers).
--
-- Healthcheck trong deploy.yml đợi tới 300s nên không cần can thiệp; nếu nó rollback thì đọc
-- `docker compose -f docker-compose.prod.yml logs backend` — lỗi seed sẽ hiện rõ ở đó.
-- ---------------------------------------------------------------------------------------------


-- ---------------------------------------------------------------------------------------------
-- STEP 4 — SAU DEPLOY
--
--   1. Đổi mật khẩu hai tài khoản ADMIN seed (9499, 9500) qua API — xem README mục "Tài khoản demo".
--   2. Nạp các phần ngoài Flyway: Neo4j (NEO4J_SEED_ON_START), bảng tin
--      (POST /v1/api/admin/newsfeed/rebuild), và Stream chat (scripts/seed/seed-stream-chat.mjs
--      --reset). Xem README.
-- ---------------------------------------------------------------------------------------------
