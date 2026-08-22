-- =============================================================================================
-- CHỈ DÀNH CHO MÁY DEV — thư mục này KHÔNG được nạp ở production.
--
-- Tách khỏi db/seed vì từ 2026-08-21 bộ seed ở đó chạy cả trên production, còn những gì nằm đây
-- là CREDENTIAL DÙNG ĐƯỢC NGAY. Nội dung demo thì vô hại ở mọi môi trường; một API token thì không.
--
-- Nạp ở máy dev bằng cách thêm location này:
--   FLYWAY_LOCATIONS=classpath:db/migration,classpath:db/seed,classpath:db/seed-dev
-- =============================================================================================

-- ── Personal access token ──────────────────────────────────────────────────────────────────
-- ⚠️ CÁC TOKEN DƯỚI ĐÂY LÀ CREDENTIAL CÓ THẬT VÀ DÙNG ĐƯỢC NGAY.
--
-- Endpoint /v1/api/knowledge/sync/** là permitAll ở tầng Spring Security và tự xác thực bằng
-- chính token này (PersonalAccessTokenService.verify), nên ai có chuỗi token là ghi được vào
-- vault của tài khoản tương ứng. Đây là lý do bộ seed bắt buộc không được chạy ở production —
-- xem src/main/resources/db/seed/README.md.
--
-- Cột lưu SHA-256 dạng hex của token, không lưu token gốc (PersonalAccessTokenService.hashToken).
-- Token gốc tương ứng, dùng để thử luồng đồng bộ ở máy dev:
--
--   9003  sk_seed_dev_vault_token_alpha   (BIDIRECTIONAL, không hết hạn)
--   9021  sk_seed_dev_vault_token_beta    (WRITE_ONLY,   còn hạn 90 ngày)
--   9039  sk_seed_dev_vault_token_gamma   (BIDIRECTIONAL, ĐÃ hết hạn — dùng để thử nhánh từ chối)
INSERT INTO socialapp.t_personal_access_tokens
    (user_id, token_hash, name, vault_permission, last_used_at, expires_at, created_at) VALUES
    (9003, '4b7006e2932f08afbf0c56f873886ad7cce3875b36489a52d529aac6d3e12e9e',
     'Máy tính cá nhân', 'BIDIRECTIONAL', now() - INTERVAL '2 days', NULL, now() - INTERVAL '120 days'),
    (9021, '40ea468452309048a7c9ecf863a94187e25c79902edd33da1ad09fc9f026edba',
     'Máy tính công ty', 'WRITE_ONLY', now() - INTERVAL '5 days', now() + INTERVAL '90 days', now() - INTERVAL '30 days'),
    (9039, 'b8b13b5bdac213dcfc2394c144b7258dd590c65817be6f00b038ddb8f56be9b0',
     'Laptop cũ (đã hết hạn)', 'BIDIRECTIONAL', now() - INTERVAL '200 days', now() - INTERVAL '10 days', now() - INTERVAL '365 days');

SELECT setval('socialapp.q_access_tokens_id',
              (SELECT COALESCE(MAX(id), 1) FROM socialapp.t_personal_access_tokens), true);
