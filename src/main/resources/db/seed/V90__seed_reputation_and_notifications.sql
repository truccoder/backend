-- =============================================================================================
-- Sự kiện uy tín (dẫn xuất từ dữ liệu thật), elite_score tính lại, và thông báo.

-- Flyway chạy file này qua chính pool của ứng dụng, nơi application.yml đặt
-- statement_timeout = 15s cho MỌI kết nối. Trần đó đúng cho một request người dùng và sai cho một
-- lần nạp dữ liệu hàng chục nghìn hàng. LOCAL: chỉ có hiệu lực trong giao dịch của migration này,
-- không rò sang bất kỳ kết nối nào khác của ứng dụng — dùng `SET` trần sẽ nới trần cho cả
-- connection sau khi nó được trả về pool, tức vô hiệu hoá một lớp bảo vệ có chủ đích ở một chỗ
-- hoàn toàn không liên quan.
SET LOCAL statement_timeout = 0;

-- FILE NÀY SINH TỰ ĐỘNG bởi scripts/seed/generate_seed.py — sửa tay sẽ bị ghi đè.
--
-- SỰ KIỆN UY TÍN ĐƯỢC DẪN XUẤT BẰNG SQL TỪ DỮ LIỆU ĐÃ CHÈN, KHÔNG PHẢI SINH RA RỒI GÁN.
-- Đó là khác biệt quan trọng: nếu generator tự bịa ra các hàng uy tín thì elite_score vẫn bằng tổng
-- điểm — bất biến vẫn đúng — nhưng con số ấy không còn tương ứng với bất cứ hoạt động nào nhìn thấy
-- được trên giao diện. Một người có 900 điểm mà bài viết chỉ có ba lượt thích là dữ liệu nói dối.
-- Dẫn xuất bằng SQL làm cho chuyện đó không xảy ra được.
--
-- Bảng điểm, lấy đúng theo mã nguồn:
--     REACTION_RECEIVED            1   source_id "{postId}:{reactorId}"  (PostReactionService)
--     ACCEPTED_ANSWER             15   source_id "{commentId}"           (PostService)
--     ROADMAP_SELF_VERIFIED        5   source_id "{userId}:{nodeId}"
--     ROADMAP_NODE_VERIFIED       20   source_id "{userId}:{nodeId}"
--     PROJECT_APPLICATION_ACCEPTED 10  source_id "{applicationId}"       (ProjectService)
--
-- uq_reputation_event UNIQUE (user_id, source_type, source_id) — mọi câu lệnh dưới đây đều
-- ON CONFLICT DO NOTHING để chạy lại được.
--
-- V42 có sẵn một câu INSERT sinh ROADMAP_NODE_VERIFIED, nhưng nó chạy TRƯỚC bộ seed này trên bảng
-- rỗng nên là no-op. File này phải tự làm lại phần đó.

-- =============================================================================================

-- REACTION_RECEIVED — 1 điểm cho mỗi cảm xúc mà bài của một người nhận được.

INSERT INTO socialapp.t_reputation_events (user_id, source_type, source_id, points, created_at)
SELECT p.author_id,
       'REACTION_RECEIVED',
       r.post_id::text || ':' || r.user_id::text,
       1,
       r.created_at
  FROM socialapp.t_post_reactions r
  JOIN socialapp.t_posts p ON p.id = r.post_id
 WHERE p.author_id <> r.user_id
    ON CONFLICT (user_id, source_type, source_id) DO NOTHING;

-- ACCEPTED_ANSWER — 15 điểm cho tác giả của bình luận được chọn làm câu trả lời.

INSERT INTO socialapp.t_reputation_events (user_id, source_type, source_id, points, created_at)
SELECT c.author_id,
       'ACCEPTED_ANSWER',
       c.id::text,
       15,
       c.created_at
  FROM socialapp.t_posts p
  JOIN socialapp.t_comments c
    ON c.id = (p.qna_details->>'acceptedAnswerId')::int
 WHERE p.post_type = 'QNA'
   AND p.qna_details->>'acceptedAnswerId' IS NOT NULL
    ON CONFLICT (user_id, source_type, source_id) DO NOTHING;

-- Lộ trình — 5 điểm khi tự xác nhận, 20 điểm khi được người khác duyệt. Chỉ tính bản ghi đã VERIFIED:
-- một nút đang chờ duyệt chưa mang lại điểm nào, đúng như luồng thật.

INSERT INTO socialapp.t_reputation_events (user_id, source_type, source_id, points, created_at)
SELECT pr.user_id,
       CASE WHEN pr.tier = 'SELF_VERIFIED' THEN 'ROADMAP_SELF_VERIFIED'
            ELSE 'ROADMAP_NODE_VERIFIED' END,
       pr.user_id::text || ':' || pr.node_id::text,
       CASE WHEN pr.tier = 'SELF_VERIFIED' THEN 5 ELSE 20 END,
       pr.created_at
  FROM socialapp.t_user_roadmap_progress pr
 WHERE pr.status = 'VERIFIED'
    ON CONFLICT (user_id, source_type, source_id) DO NOTHING;

-- PROJECT_APPLICATION_ACCEPTED — 10 điểm cho người được nhận vào dự án.

INSERT INTO socialapp.t_reputation_events (user_id, source_type, source_id, points, created_at)
SELECT a.applicant_id, 'PROJECT_APPLICATION_ACCEPTED', a.id::text, 10, a.created_at
  FROM socialapp.t_project_applications a
 WHERE a.status = 'ACCEPTED'
    ON CONFLICT (user_id, source_type, source_id) DO NOTHING;

-- elite_score TÍNH LẠI từ tổng điểm, không gõ tay. Đây là bất biến dễ lệch nhất của cả bộ seed: mọi
-- thứ khác đều hiện ra trên giao diện, còn một elite_score sai thì trông vẫn hoàn toàn hợp lý.

UPDATE socialapp.t_users u
   SET elite_score = COALESCE(e.total, 0)
  FROM (SELECT user_id, SUM(points) AS total
          FROM socialapp.t_reputation_events GROUP BY user_id) e
 WHERE e.user_id = u.id;

-- ── Thông báo ────────────────────────────────────────────────────────────────────────────────
-- LUẬT post_id (V72), và V72 nay là NO-OP nên không còn backfill nào đỡ cho file này:
--
--   · reference_type = 'COMMENT'  →  post_id PHẢI khác NULL và bằng đúng t_comments.post_id của
--                                    reference_id. Áp cho cả USER_MENTIONED lẫn COMMENT_LIKED.
--   · mọi reference_type khác     →  post_id PHẢI là NULL.
--
-- Vì sao NULL là bắt buộc chứ không phải tuỳ: DTO dùng @JsonInclude(NON_NULL), nên một giá trị thừa
-- làm JSON của FRIEND_REQUEST hay BOOK_PURCHASED mọc thêm một khoá lạ mà frontend không khai.
--
-- channel là 'BOTH', đúng giá trị mà chính ứng dụng ghi (SendNotificationRequest mặc định
-- NotificationChannel.BOTH). Enum chỉ có PUSH / EMAIL / BOTH — KHÔNG có 'IN_APP', dù đó là cái tên
-- nghe hợp lý nhất cho một thông báo trong ứng dụng. Cột là varchar không có CHECK nên Flyway nhận
-- tuốt; chỗ nổ là Hibernate lúc đọc, và vì mọi hàng đều mang cùng một giá trị nên sai ở đây làm
-- /notifications trả 500 cho MỌI tài khoản chứ không phải hỏng lác đác.
--
-- Sinh bằng SQL từ dữ liệu đã có, nên post_id không thể lệch: nó lấy thẳng từ chính hàng bình luận.

-- POST_LIKED: trỏ tới bài, nên post_id để NULL (route /posts/{id} đã đủ).
INSERT INTO socialapp.t_notifications
    (recipient_id, actor_id, type, title, body, reference_id, reference_type, post_id,
     channel, is_read, sent_at, created_at)
SELECT p.author_id, r.user_id, 'POST_LIKED',
       'Có người bày tỏ cảm xúc về bài viết của bạn',
       u.full_name || ' đã bày tỏ cảm xúc về bài viết của bạn.',
       p.id, 'POST', NULL,
       'BOTH', (r.user_id % 3) <> 0, r.created_at, r.created_at
  FROM socialapp.t_post_reactions r
  JOIN socialapp.t_posts p ON p.id = r.post_id
  JOIN socialapp.t_users u ON u.id = r.user_id
 WHERE p.author_id <> r.user_id
   AND (r.post_id + r.user_id) % 7 = 0;

-- POST_COMMENTED: cũng trỏ tới bài.
INSERT INTO socialapp.t_notifications
    (recipient_id, actor_id, type, title, body, reference_id, reference_type, post_id,
     channel, is_read, sent_at, created_at)
SELECT p.author_id, c.author_id, 'POST_COMMENTED',
       'Có bình luận mới trong bài viết của bạn',
       u.full_name || ' đã bình luận về bài viết của bạn.',
       p.id, 'POST', NULL,
       'BOTH', (c.id % 4) <> 0, c.created_at, c.created_at
  FROM socialapp.t_comments c
  JOIN socialapp.t_posts p ON p.id = c.post_id
  JOIN socialapp.t_users u ON u.id = c.author_id
 WHERE p.author_id <> c.author_id
   AND c.parent_id IS NULL
   AND c.id % 3 = 0;

-- USER_MENTIONED: trỏ tới BÌNH LUẬN, nên post_id BẮT BUỘC có, lấy thẳng từ chính hàng bình luận.
-- Bình luận mà dấu @ là địa chỉ email KHÔNG lọt vào đây: điều kiện dưới đây chỉ nhận nội dung bắt
-- đầu bằng '@' và không chứa dấu chấm trước khoảng trắng đầu tiên, đúng cách MentionScanner phân
-- biệt một handle với một địa chỉ thư.
INSERT INTO socialapp.t_notifications
    (recipient_id, actor_id, type, title, body, reference_id, reference_type, post_id,
     channel, is_read, sent_at, created_at)
SELECT target.id, c.author_id, 'USER_MENTIONED',
       'Có người nhắc tới bạn',
       u.full_name || ' đã nhắc tới bạn trong một bình luận.',
       c.id, 'COMMENT', c.post_id,
       'BOTH', (c.id % 5) <> 0, c.created_at, c.created_at
  FROM socialapp.t_comments c
  JOIN socialapp.t_users u ON u.id = c.author_id
  JOIN socialapp.t_users target
    ON target.username = substring(c.content from '^@([A-Za-z0-9]+)')
 WHERE c.content LIKE '@%'
   AND target.id <> c.author_id;

-- COMMENT_LIKED: cũng trỏ tới bình luận, cùng luật post_id.
INSERT INTO socialapp.t_notifications
    (recipient_id, actor_id, type, title, body, reference_id, reference_type, post_id,
     channel, is_read, sent_at, created_at)
SELECT c.author_id, cr.user_id, 'COMMENT_LIKED',
       'Có người thấy bình luận của bạn hữu ích',
       u.full_name || ' thấy bình luận của bạn hữu ích.',
       c.id, 'COMMENT', c.post_id,
       'BOTH', (cr.user_id % 3) <> 0, cr.created_at, cr.created_at
  FROM socialapp.t_comment_reactions cr
  JOIN socialapp.t_comments c ON c.id = cr.comment_id
  JOIN socialapp.t_users u ON u.id = cr.user_id
 WHERE c.author_id <> cr.user_id
   AND (cr.comment_id + cr.user_id) % 11 = 0;

-- FRIEND_REQUEST và FRIEND_ACCEPTED: không nói về bài viết nào, nên post_id là NULL.
INSERT INTO socialapp.t_notifications
    (recipient_id, actor_id, type, title, body, reference_id, reference_type, post_id,
     channel, is_read, sent_at, created_at)
SELECT fr.addressee_id, fr.requester_id,
       CASE WHEN fr.status = 'PENDING' THEN 'FRIEND_REQUEST' ELSE 'FRIEND_ACCEPTED' END,
       CASE WHEN fr.status = 'PENDING' THEN 'Bạn có lời mời kết bạn mới'
            ELSE 'Lời mời kết bạn đã được chấp nhận' END,
       u.full_name || CASE WHEN fr.status = 'PENDING'
                           THEN ' đã gửi cho bạn một lời mời kết bạn.'
                           ELSE ' đã chấp nhận lời mời kết bạn của bạn.' END,
       fr.id, 'FRIEND_REQUEST', NULL,
       'BOTH', fr.status <> 'PENDING', fr.created_at, fr.created_at
  FROM socialapp.t_friend_requests fr
  JOIN socialapp.t_users u ON u.id = fr.requester_id
 WHERE fr.status IN ('PENDING', 'ACCEPTED')
   AND fr.id % 9 = 0;

-- BOOK_PURCHASED: trỏ tới sách, post_id NULL.
INSERT INTO socialapp.t_notifications
    (recipient_id, actor_id, type, title, body, reference_id, reference_type, post_id,
     channel, is_read, sent_at, created_at)
SELECT b.author_id, bp.buyer_id, 'BOOK_PURCHASED',
       'Có người mua sách của bạn',
       u.full_name || ' vừa mua "' || b.title || '".',
       b.id, 'BOOK', NULL,
       'BOTH', (bp.buyer_id % 2) = 0, bp.created_at, bp.created_at
  FROM socialapp.t_book_purchases bp
  JOIN socialapp.t_books b ON b.id = bp.book_id
  JOIN socialapp.t_users u ON u.id = bp.buyer_id
 WHERE bp.payment_status = 'COMPLETED'
   AND b.author_id <> bp.buyer_id;

-- Kiểm tra tại chỗ: nếu bất kỳ hàng nào phá luật post_id thì câu lệnh dưới đây làm migration ĐỔ NGAY,
-- ở lần chạy đầu tiên, thay vì để lỗi đi tới giao diện dưới dạng một thông báo bấm vào không làm gì.
-- Đây là bất biến mà V72 từng canh bằng backfill, và nay V72 là no-op.

DO $$
DECLARE bad INT;
BEGIN
    SELECT COUNT(*) INTO bad
      FROM socialapp.t_notifications n
     WHERE (n.reference_type = 'COMMENT'
            AND (n.post_id IS NULL
                 OR n.post_id <> (SELECT c.post_id FROM socialapp.t_comments c
                                   WHERE c.id = n.reference_id)))
        OR (n.reference_type <> 'COMMENT' AND n.post_id IS NOT NULL);
    IF bad > 0 THEN
        RAISE EXCEPTION 'Seed hong: % thong bao pha luat post_id cua V72', bad;
    END IF;
END $$;

SELECT setval('socialapp.q_notifications_id',
              GREATEST((SELECT COALESCE(MAX(id), 0) FROM socialapp.t_notifications), 1), true);
