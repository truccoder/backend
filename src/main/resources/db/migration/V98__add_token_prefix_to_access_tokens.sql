-- =============================================================================================
-- Tiền tố hiển thị được của token, để phân biệt hai token trùng tên — B29 trong docs/backend-plan.md.
--
-- GET /v1/api/tokens trả id, name, createdAt, expiresAt, lastUsedAt, vaultPermission — mọi thứ
-- trừ một mẩu của chính chuỗi token. Bí mật chỉ hiện đúng một lần lúc tạo (POST /tokens) và chỉ
-- tokenHash (SHA-256) được lưu lại, nên không có cách nào tái tạo tiền tố cho những hàng đã có —
-- cột này NULL với chúng, và đó là trạng thái đúng chứ không phải dữ liệu thiếu.
--
-- 12 ký tự đầu của "sk_" + base64url(32 byte) là dư entropy: lộ chúng không làm yếu bí mật.
-- =============================================================================================

ALTER TABLE socialapp.t_personal_access_tokens ADD COLUMN IF NOT EXISTS token_prefix VARCHAR(16);
