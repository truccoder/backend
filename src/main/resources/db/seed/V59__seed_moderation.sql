-- =============================================================================================
-- Kiểm duyệt: nhật ký chấm bài, vi phạm của người dùng, lệnh cấm, và đơn khiếu nại.
--
-- Dữ liệu ở đây tuân theo ĐÚNG luật mà UserBanService áp trong code, không bịa ra luật riêng:
--   - Mức độ suy ra từ loại vi phạm (determineSeverity):
--       HATE_SPEECH, VIOLENCE, THREAT          -> CRITICAL
--       NSFW, SEXUALLY_EXPLICIT                -> HIGH
--       INSULT, KEYWORD_BLACKLIST              -> MEDIUM
--       SPAM, DUPLICATE_CONTENT                -> LOW
--   - VIOLATIONS_BEFORE_BAN = 2: đủ 2 vi phạm thì bị cấm, BAN_DURATION_DAYS = 7.
--
-- Nếu seed đặt mức độ tuỳ tiện hoặc cấm người mới có 1 vi phạm, thì màn hình quản trị sẽ hiển
-- thị trạng thái mà chính hệ thống không bao giờ tạo ra được — và người thử tính năng sẽ tưởng
-- là code sai trong khi thật ra là dữ liệu sai.
-- =============================================================================================

-- ── Nhật ký chấm bài ───────────────────────────────────────────────────────────────────────
-- Mỗi bài đi qua kiểm duyệt đều để lại một dòng. Bài APPROVED có điểm độc hại thấp; bài
-- PENDING_REVIEW nằm ở vùng xám giữa hai ngưỡng; bài REJECTED vượt ngưỡng trên.
--
-- Ngưỡng lấy theo application.yml: toxicity-threshold 0.7, review-threshold 0.5. Nghĩa là
-- điểm dưới 0.5 thì duyệt, 0.5-0.7 thì đưa người xem, trên 0.7 thì từ chối. Các giá trị dưới
-- đây được đặt để nằm đúng phía ngưỡng ứng với trạng thái của bài.
--
-- text_toxicity_score và image_safe_score là numeric(4,3) — tối đa 3 chữ số thập phân.
INSERT INTO socialapp.t_moderation_logs
    (post_id, status, violation_type, text_toxicity_score, image_safe_score, rule_violations, reviewed_at, created_at)
SELECT p.id,
       p.moderation_status,
       CASE p.moderation_status
           WHEN 'REJECTED' THEN (ARRAY['SPAM','INSULT'])[1 + (p.id % 2)]
           WHEN 'PENDING_REVIEW' THEN (ARRAY['SPAM','KEYWORD_BLACKLIST','INSULT'])[1 + (p.id % 3)]
       END,
       CASE p.moderation_status
           WHEN 'APPROVED'       THEN round((0.02 + (p.id % 40) * 0.01)::numeric, 3)
           WHEN 'PENDING_REVIEW' THEN round((0.52 + (p.id % 15) * 0.01)::numeric, 3)
           WHEN 'REJECTED'       THEN round((0.74 + (p.id % 20) * 0.01)::numeric, 3)
           ELSE NULL
       END,
       0.000,
       CASE p.moderation_status
           WHEN 'REJECTED'       THEN '["Vượt ngưỡng độc hại của mô hình chấm nội dung"]'
           WHEN 'PENDING_REVIEW' THEN '["Điểm nằm giữa hai ngưỡng, cần người xem lại"]'
           ELSE '[]'
       END::jsonb,
       -- Chỉ bài đã có kết luận cuối cùng mới có mốc thời gian xem xét. Bài đang chờ thì chưa ai
       -- xem, nên để NULL — đây chính là thứ phân biệt "đã duyệt" với "đang nằm hàng chờ".
       CASE WHEN p.moderation_status IN ('APPROVED', 'REJECTED')
            THEN p.created_at + INTERVAL '3 minutes' END,
       p.created_at + INTERVAL '1 minute'
  FROM socialapp.t_posts p
 -- Bài PENDING_MODERATION chưa được chấm nên chưa có nhật ký nào cả.
 WHERE p.moderation_status <> 'PENDING_MODERATION';

-- ── Vi phạm của người dùng ─────────────────────────────────────────────────────────────────
-- Sinh từ chính các bài đã bị REJECTED, chứ không rải ngẫu nhiên: mỗi vi phạm phải truy ngược
-- được về một bài cụ thể, nếu không màn hình chi tiết vi phạm sẽ trỏ vào khoảng không.
INSERT INTO socialapp.t_user_violations (user_id, post_id, violation_type, severity, description, created_at)
SELECT p.author_id,
       p.id,
       v.violation_type,
       -- Ánh xạ mức độ y hệt determineSeverity trong UserBanService.
       CASE v.violation_type
           WHEN 'HATE_SPEECH'       THEN 'CRITICAL'
           WHEN 'VIOLENCE'          THEN 'CRITICAL'
           WHEN 'THREAT'            THEN 'CRITICAL'
           WHEN 'NSFW'              THEN 'HIGH'
           WHEN 'SEXUALLY_EXPLICIT' THEN 'HIGH'
           WHEN 'INSULT'            THEN 'MEDIUM'
           WHEN 'KEYWORD_BLACKLIST' THEN 'MEDIUM'
           ELSE 'LOW'
       END,
       'AI moderation detected violation: [' || v.violation_type || ']',
       p.created_at + INTERVAL '3 minutes'
  FROM socialapp.t_posts p
  JOIN socialapp.t_moderation_logs v ON v.post_id = p.id
 WHERE p.moderation_status = 'REJECTED'
   AND v.violation_type IS NOT NULL;

-- Thêm một vi phạm thứ hai cho hai người, để họ chạm ngưỡng VIOLATIONS_BEFORE_BAN = 2 và việc
-- bị cấm bên dưới là hệ quả hợp lệ chứ không phải một dòng cấm từ trên trời rơi xuống.
--
-- Hai mốc thời gian khác nhau là có chủ đích, để cả hai trạng thái cùng tồn tại:
--   9007 vi phạm cách đây 2 ngày  -> lệnh cấm 7 ngày CÒN hiệu lực, tài khoản đang bị khoá
--   9028 vi phạm cách đây 16 ngày -> lệnh cấm đã hết hạn, chỉ còn trong lịch sử
-- Nếu cả hai đều cũ thì không có tài khoản nào đang bị cấm, và không thử được màn hình chặn
-- đăng nhập lẫn luồng khiếu nại của người đang bị khoá.
INSERT INTO socialapp.t_user_violations (user_id, post_id, violation_type, severity, description, created_at) VALUES
    (9007, NULL, 'SPAM', 'LOW', 'AI moderation detected violation: [SPAM]', now() - INTERVAL '2 days'),
    (9028, NULL, 'INSULT', 'MEDIUM', 'AI moderation detected violation: [INSULT]', now() - INTERVAL '16 days');

-- ── Lệnh cấm ───────────────────────────────────────────────────────────────────────────────
-- Chỉ cấm người đã có từ 2 vi phạm trở lên. Điều kiện này viết thành truy vấn thay vì liệt kê
-- id bằng tay, để nếu ai đó thêm bớt vi phạm ở trên thì danh sách bị cấm tự khớp lại theo.
INSERT INTO socialapp.t_user_bans (user_id, post_id, banned_until, created_at)
SELECT v.user_id,
       max(v.post_id),
       -- Cấm 7 ngày kể từ vi phạm gần nhất, đúng BAN_DURATION_DAYS.
       max(v.created_at) + INTERVAL '7 days',
       max(v.created_at)
  FROM socialapp.t_user_violations v
 GROUP BY v.user_id
HAVING count(*) >= 2;

-- t_users.banned_until là thứ đường đăng nhập thực sự đọc. Bảng t_user_bans là lịch sử; cột này
-- là trạng thái hiện tại. Hai chỗ lệch nhau thì người bị cấm vẫn đăng nhập bình thường (hoặc
-- ngược lại, người không có lệnh cấm nào lại bị chặn) — nên đồng bộ lại từ chính t_user_bans.
UPDATE socialapp.t_users u
   SET banned_until = b.until, updated_at = now()
  FROM (SELECT user_id, max(banned_until) AS until FROM socialapp.t_user_bans GROUP BY user_id) b
 WHERE u.id = b.user_id
   -- Chỉ đặt cho lệnh cấm CÒN hiệu lực. Lệnh đã hết hạn nằm lại trong lịch sử, nhưng không được
   -- để lại dấu vết ở trạng thái hiện tại.
   AND b.until > now();

-- ── Đơn khiếu nại ──────────────────────────────────────────────────────────────────────────
-- AppealStatus: PENDING, APPROVED (gỡ vi phạm và lệnh cấm), REJECTED (giữ nguyên).
-- Chỉ số uq_appeal_one_pending_per_violation (V49) bắt buộc mỗi vi phạm chỉ có tối đa MỘT đơn
-- đang chờ, nên mỗi violation_id dưới đây xuất hiện đúng một lần ở trạng thái PENDING.
--
-- Lưu ý về tính nhất quán: đơn APPROVED trong đời thực sẽ khiến vi phạm bị XOÁ
-- (UserBanService.revokeViolation) và violation_id trở thành NULL nhờ ON DELETE SET NULL. Vì vậy
-- đơn APPROVED ở đây cố ý để violation_id NULL — đúng như trạng thái sau khi hệ thống xử lý xong.
INSERT INTO socialapp.t_moderation_appeals
    (user_id, violation_id, reason, status, reviewer_id, reviewer_note, reviewed_at, created_at, updated_at)
SELECT v.user_id,
       v.id,
       (ARRAY[
           'Bài viết của mình chỉ trích dẫn lại nội dung để phân tích, không phải để lan truyền. Mong ban quản trị xem lại.',
           'Mình nghĩ hệ thống nhận nhầm. Nội dung là thuật ngữ kỹ thuật chứ không phải từ ngữ công kích.',
           'Mình đã đọc lại quy định và vẫn chưa rõ bài của mình vi phạm điểm nào. Mong được giải thích cụ thể.'
       ])[1 + (v.id % 3)],
       'PENDING',
       NULL, NULL, NULL,
       v.created_at + INTERVAL '1 day',
       v.created_at + INTERVAL '1 day'
  FROM socialapp.t_user_violations v
 WHERE v.id % 3 = 0;

-- Một đơn đã bị từ chối và một đơn đã được chấp nhận, để hàng chờ của quản trị viên có cả ba
-- trạng thái chứ không chỉ toàn đơn đang chờ.
INSERT INTO socialapp.t_moderation_appeals
    (user_id, violation_id, reason, status, reviewer_id, reviewer_note, reviewed_at, created_at, updated_at) VALUES
    (9013, NULL,
     'Mình đăng nhầm nội dung quảng cáo từ nhóm khác, mình đã xoá và cam kết không lặp lại.',
     'REJECTED', 9059, 'Nội dung vi phạm rõ ràng quy định về quảng cáo. Giữ nguyên quyết định.',
     now() - INTERVAL '5 days', now() - INTERVAL '7 days', now() - INTERVAL '5 days'),
    (9051, NULL,
     'Bài của mình bị đánh dấu nhầm, đó là đoạn mã ví dụ chứ không phải đường dẫn lừa đảo.',
     'APPROVED', 9060, 'Đã xem lại, hệ thống nhận nhầm đoạn mã thành liên kết. Gỡ vi phạm.',
     now() - INTERVAL '3 days', now() - INTERVAL '6 days', now() - INTERVAL '3 days');
