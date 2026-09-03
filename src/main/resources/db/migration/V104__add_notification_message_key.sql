-- =============================================================================================
-- Payload có cấu trúc cho thông báo — B40 (i18n) trong docs/backend-plan.md.
--
-- Trước đây mỗi service phát thông báo tự ghép sẵn một câu tiếng Anh vào cột `body`
-- ("X reacted to your post"). App mặc định tiếng Việt nên hàng thông báo luôn ra tiếng Anh, và
-- FE phải parse ngược chuỗi tiếng Anh cố định để dựng lại câu (features/notifications/lib/
-- notification-text.ts) — đổi một từ trong mẫu là nhánh đó rơi về fallback lặng lẽ.
--
-- message_key + message_args tách "nói cái gì" khỏi "nói bằng ngôn ngữ nào": producer đặt khoá
-- mẫu (vd POST_LIKED) và các biến (vd {"actor": "Ada"}), FE chỉ tra bundle i18n của nó. `title`
-- và `body` tiếng Anh vẫn được ghi như cũ — chúng là nội dung cho push/email và là fallback cho
-- hàng cũ (materialize từ trước khi có cột này).
--
-- Cả hai cột nullable, không backfill: hàng cũ giữ message_key = NULL và FE fallback về `body`
-- nguyên văn — đúng bằng hành vi trước đây.
-- =============================================================================================

ALTER TABLE socialapp.t_notifications
  ADD COLUMN IF NOT EXISTS message_key  VARCHAR(64),
  ADD COLUMN IF NOT EXISTS message_args JSONB;
