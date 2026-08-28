-- =============================================================================================
-- B20 · Nhắc tên trong bình luận — dữ liệu để nhánh này chạy được thật
--
-- Không có MỘT ký tự '@' nào trong toàn bộ V54 (đo ngày 24/08). Nghĩa là đường quét mention vừa
-- viết — MentionScanner + CommentService.notifyMentionedUsers — chưa có dòng dữ liệu nào chạm
-- tới, và cả hai mặt của tính năng đều vô hình: bình luận không có tag để tô màu, chuông không
-- có thông báo loại USER_MENTIONED để hiện.
--
-- Cùng loại thiếu sót mà V65 đã sửa cho phần kẹp nội dung: seed đầy đủ về số lượng nhưng không
-- chạm tới nhánh nào.
--
-- ── Vì sao ở db/seed chứ không phải db/seed-dev ─────────────────────────────────────────────
-- Khác V69: ở đây không có URL nào, không có đường dẫn MinIO nào. Chỉ là chữ và khoá ngoại, nên
-- đúng ở mọi môi trường.
--
-- ── Dải id ──────────────────────────────────────────────────────────────────────────────────
--   bình luận  8014-8016   (V65 dùng tới 8013)
--
-- Chạy SAU V65 vì ba bình luận dưới đây treo vào bài 5301/5313 do V65 tạo, và vì id phải tiếp
-- ngay sau dải của nó.
--
-- ── Chọn bài để treo vào, KHÔNG tuỳ ý ───────────────────────────────────────────────────────
-- V65 ghim số bình luận gốc của 5310/5311/5312/5313 ở đúng 0 / 1 / 2 / >=5, và SeedMigrationTest
-- assert cả bốn: đó là bốn ca của khối xem trước hai bình luận. Thêm một hàng vào 5311 là biến
-- ca "đúng 1" thành "đúng 2" và làm chết một ca kiểm thử đang đúng — chính test đó đã bắt được
-- lần đầu viết file này. Nên 8014 treo vào 5301 (bài nội dung dài, không có ràng buộc số bình
-- luận nào), còn 8015/8016 treo vào 5313 vì bài đó chỉ yêu cầu >= 5, thêm vào vẫn thoả.
--
-- ── Handle dùng ở đây là handle THẬT ────────────────────────────────────────────────────────
-- '@backend_truc_anh' và '@backend_dung_nhan' lấy đúng từ cột username trong V51. Bịa một handle
-- trông giống thật sẽ tạo ra một fixture im lặng vô dụng: MentionScanner tìm ra nó, tra bảng
-- user không thấy ai, rồi bỏ qua — nhánh thông báo vẫn không chạy, mà nhìn file SQL thì tưởng là
-- có. Mọi handle dưới đây đều phải đối chiếu được với V51.
-- =============================================================================================

-- ── Ba ca của luồng nhắc tên ────────────────────────────────────────────────────────────────
--   8014  nhắc MỘT người, tag đứng đầu chuỗi     → ca thường, đúng cách client điền sẵn ô soạn
--   8015  nhắc HAI người trong cùng một câu       → kiểm việc tra nhiều handle trong một truy vấn
--   8016  có '@' nhưng KHÔNG phải mention         → ca âm bắt buộc, xem ghi chú bên dưới
INSERT INTO socialapp.t_comments
    (id, post_id, author_id, content, parent_id, created_at, updated_at) VALUES
    (8014, 5301, 9002,
     '@backend_truc_anh đúng ý mình, phần ThreadLocal là chỗ dễ sập nhất khi đổi sang virtual threads.',
     NULL, now() - INTERVAL '280 hours', now() - INTERVAL '280 hours'),
    (8015, 5313, 9003,
     'Chỗ này nên hỏi @backend_truc_anh và @backend_dung_nhan, hai bạn đó trực ca hôm sự cố.',
     NULL, now() - INTERVAL '270 hours', now() - INTERVAL '270 hours'),
    -- Ca âm, và là ca quan trọng nhất trong ba ca. Chuỗi này chứa '@' nhưng không nhắc ai:
    -- MentionScanner bắt buộc ký tự trước '@' phải là khoảng trắng (hoặc đầu chuỗi), nếu không
    -- thì mọi bình luận trích dẫn một địa chỉ email sẽ báo cho người tình cờ giữ handle đó.
    -- Không có hàng này thì luật ấy không có gì chứng minh trong dữ liệu thật.
    (8016, 5313, 9004,
     'Gửi log vào ops@socialapp.com nhé, mình không kiểm tra được từ máy cá nhân.',
     NULL, now() - INTERVAL '260 hours', now() - INTERVAL '260 hours');

-- ── Thông báo tương ứng ─────────────────────────────────────────────────────────────────────
-- Sinh từ chính ba bình luận trên thay vì gõ tay, nên nội dung không thể lệch với comment mà nó
-- trỏ tới. Điều kiện WHERE lặp lại đúng luật của MentionScanner ở dạng SQL:
--
--   - '@' phải đứng đầu chuỗi hoặc sau khoảng trắng  → (^|\s) trong biểu thức
--   - phần thân handle đúng bộ ký tự của V47          → [a-z0-9][a-z0-9_-]{2,29}
--
-- Nên bình luận 8016 tự động KHÔNG sinh thông báo nào, chứ không phải bị loại trừ bằng tay.
--
-- reference_id là id BÌNH LUẬN và reference_type là 'COMMENT': một luồng có thể dài hàng trăm
-- dòng, và thông báo phải mở đúng vào dòng đã gọi tên người đọc — xem NotificationType.USER_MENTIONED.
INSERT INTO socialapp.t_notifications
    (recipient_id, actor_id, type, title, body, reference_id, reference_type, channel, is_read,
     sent_at, created_at)
SELECT mentioned.id,
       c.author_id,
       'USER_MENTIONED',
       actor.full_name || ' đã nhắc tới bạn trong một bình luận',
       left(c.content, 100),
       c.id,
       'COMMENT',
       CASE WHEN pref.email_enabled THEN 'BOTH' ELSE 'PUSH' END,
       -- Chưa đọc: cả ba bình luận này đều mới hơn 7 ngày theo cách tính của V60, nên chúng cộng
       -- vào huy hiệu "chưa đọc" và người đi demo nhìn thấy con số nhúc nhích.
       FALSE,
       c.created_at,
       c.created_at
  FROM socialapp.t_comments c
  JOIN socialapp.t_users actor ON actor.id = c.author_id
  CROSS JOIN LATERAL regexp_matches(
                 c.content, '(?:^|\s)@([a-z0-9][a-z0-9_-]{2,29})', 'g') AS m(handle)
  JOIN socialapp.t_users mentioned ON lower(mentioned.username) = m.handle[1]
  JOIN socialapp.t_notification_preferences pref ON pref.user_id = mentioned.id
 WHERE c.id BETWEEN 8014 AND 8016
   -- Nhắc chính mình thì không báo, giống hệt luật trong notifyMentionedUsers.
   AND mentioned.id <> c.author_id;

-- ── Đẩy sequence lên quá vùng id vừa cấp tay ────────────────────────────────────────────────
-- Bắt buộc, cùng lý do đã ghi ở cuối V65: bản ghi đầu tiên tạo qua API sẽ đụng khoá chính nếu
-- sequence vẫn đứng ở giá trị cũ. t_notifications dùng sequence riêng và ở trên không cấp id tay
-- nên không cần đụng tới.
SELECT setval('socialapp.q_comments_id', (SELECT MAX(id) FROM socialapp.t_comments), true);
