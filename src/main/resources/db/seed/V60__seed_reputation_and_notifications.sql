-- =============================================================================================
-- Điểm uy tín và thông báo.
--
-- t_reputation_events là sổ cái: mỗi dòng là một lần được cộng điểm, và
-- t_users.elite_score là tổng đã cộng dồn. ReputationReconcileScheduler chạy hằng đêm để đối
-- chiếu hai bên, nên seed phải để chúng khớp nhau ngay từ đầu — lệch thì lần chạy đối chiếu đầu
-- tiên sẽ âm thầm sửa lại toàn bộ điểm và mọi thứ dựng trên số cũ đều sai.
--
-- UNIQUE (user_id, source_type, source_id) là cơ chế chống cộng điểm hai lần: cùng một nguồn
-- chỉ được tính đúng một lần. Vì vậy source_id phải định danh được nguồn cụ thể, không được đặt
-- trùng — bên dưới dùng id của chính bản ghi sinh ra điểm.
-- =============================================================================================

-- ── Điểm từ cảm xúc nhận được ──────────────────────────────────────────────────────────────
-- RepSourceType.REACTION_RECEIVED: tác giả được cộng điểm khi bài của mình nhận cảm xúc.
-- source_id là "postId:userId" của người thả, đủ để một người thả cảm xúc vào nhiều bài khác
-- nhau vẫn sinh ra các dòng riêng biệt mà không đụng ràng buộc UNIQUE.
INSERT INTO socialapp.t_reputation_events (user_id, source_type, source_id, points, created_at)
SELECT p.author_id,
       'REACTION_RECEIVED',
       r.post_id || ':' || r.user_id,
       1,
       r.created_at
  FROM socialapp.t_post_reactions r
  JOIN socialapp.t_posts p ON p.id = r.post_id;

-- ── Điểm từ câu trả lời được chấp nhận ─────────────────────────────────────────────────────
-- Người viết bình luận được chọn làm đáp án cho một bài QNA.
INSERT INTO socialapp.t_reputation_events (user_id, source_type, source_id, points, created_at)
SELECT c.author_id,
       'ACCEPTED_ANSWER',
       c.id::text,
       15,
       c.created_at
  FROM socialapp.t_posts p
  JOIN socialapp.t_comments c
    ON c.id = (p.qna_details ->> 'acceptedAnswerId')::int
 WHERE p.post_type = 'QNA'
   AND p.qna_details ->> 'acceptedAnswerId' IS NOT NULL;

-- ── Điểm từ kỹ năng đã xác minh ────────────────────────────────────────────────────────────
-- Hai nguồn tách biệt: tự khai được ít điểm hơn hẳn so với được quản trị viên kiểm chứng — đó
-- chính là lý do tồn tại của hai mức xác minh.
INSERT INTO socialapp.t_reputation_events (user_id, source_type, source_id, points, created_at)
SELECT pr.user_id,
       CASE WHEN pr.tier = 'SELF_VERIFIED' THEN 'ROADMAP_SELF_VERIFIED' ELSE 'ROADMAP_NODE_VERIFIED' END,
       pr.node_id::text,
       CASE WHEN pr.tier = 'SELF_VERIFIED' THEN 2 ELSE 10 END,
       COALESCE(pr.verified_at, pr.created_at)
  FROM socialapp.t_user_roadmap_progress pr
 WHERE pr.status = 'VERIFIED';

-- ── Điểm từ đơn ứng tuyển được nhận ────────────────────────────────────────────────────────
INSERT INTO socialapp.t_reputation_events (user_id, source_type, source_id, points, created_at)
SELECT a.applicant_id,
       'PROJECT_APPLICATION_ACCEPTED',
       a.id::text,
       20,
       a.updated_at
  FROM socialapp.t_project_applications a
 WHERE a.status = 'ACCEPTED';

-- ── Cộng dồn vào elite_score ───────────────────────────────────────────────────────────────
-- Tính từ sổ cái chứ không gán số tuỳ ý, vì đây đúng là phép tính mà
-- ReputationReconcileScheduler thực hiện mỗi đêm.
UPDATE socialapp.t_users u
   SET elite_score = COALESCE(e.total, 0), updated_at = now()
  FROM (SELECT user_id, sum(points) AS total FROM socialapp.t_reputation_events GROUP BY user_id) e
 WHERE u.id = e.user_id;

-- ── Thông báo ──────────────────────────────────────────────────────────────────────────────
-- Cũng dựng từ những sự kiện CÓ THẬT ở các file trước, không sinh rời rạc: một thông báo
-- "A đã thích bài của bạn" mà không tồn tại lượt thích tương ứng sẽ dẫn người dùng bấm vào rồi
-- lạc vào trang trống.
--
-- NotificationChannel: PUSH, EMAIL, BOTH. NotificationType có 9 giá trị; ở đây phủ những loại
-- suy ra được từ dữ liệu đã có.
-- `channel` tôn trọng t_notification_preferences: ai tắt email thì chỉ nhận PUSH.

-- Bài được thả cảm xúc.
INSERT INTO socialapp.t_notifications
    (recipient_id, actor_id, type, title, body, reference_id, reference_type, channel, is_read, sent_at, created_at)
SELECT p.author_id,
       r.user_id,
       'POST_LIKED',
       actor.full_name || ' đã bày tỏ cảm xúc về bài viết của bạn',
       left(p.content, 100),
       p.id,
       'POST',
       CASE WHEN pref.email_enabled THEN 'BOTH' ELSE 'PUSH' END,
       -- Thông báo cũ coi như đã đọc, thông báo trong 7 ngày gần nhất thì chưa, để huy hiệu
       -- "chưa đọc" có số khác không.
       r.created_at < now() - INTERVAL '7 days',
       r.created_at,
       r.created_at
  FROM socialapp.t_post_reactions r
  JOIN socialapp.t_posts p ON p.id = r.post_id
  JOIN socialapp.t_users actor ON actor.id = r.user_id
  JOIN socialapp.t_notification_preferences pref ON pref.user_id = p.author_id
 -- t_post_reactions không có cột id — khoá chính là cặp (user_id, post_id), nên lấy mẫu theo
 -- tổng hai cột đó thay vì theo một id không tồn tại.
 WHERE (r.user_id + r.post_id) % 4 = 0;

-- Bài được bình luận.
INSERT INTO socialapp.t_notifications
    (recipient_id, actor_id, type, title, body, reference_id, reference_type, channel, is_read, sent_at, created_at)
SELECT p.author_id,
       c.author_id,
       'POST_COMMENTED',
       actor.full_name || ' đã bình luận về bài viết của bạn',
       left(c.content, 100),
       p.id,
       'POST',
       CASE WHEN pref.email_enabled THEN 'BOTH' ELSE 'PUSH' END,
       c.created_at < now() - INTERVAL '7 days',
       c.created_at,
       c.created_at
  FROM socialapp.t_comments c
  JOIN socialapp.t_posts p ON p.id = c.post_id
  JOIN socialapp.t_users actor ON actor.id = c.author_id
  JOIN socialapp.t_notification_preferences pref ON pref.user_id = p.author_id
 WHERE c.author_id <> p.author_id
   AND c.id % 3 = 0;

-- Được gắn thẻ trong bài.
INSERT INTO socialapp.t_notifications
    (recipient_id, actor_id, type, title, body, reference_id, reference_type, channel, is_read, sent_at, created_at)
SELECT t.tagged_user_id,
       p.author_id,
       'POST_TAGGED',
       actor.full_name || ' đã gắn thẻ bạn trong một bài viết',
       left(p.content, 100),
       p.id,
       'POST',
       CASE WHEN pref.email_enabled THEN 'BOTH' ELSE 'PUSH' END,
       p.created_at < now() - INTERVAL '7 days',
       p.created_at,
       p.created_at
  FROM socialapp.t_post_tags t
  JOIN socialapp.t_posts p ON p.id = t.post_id
  JOIN socialapp.t_users actor ON actor.id = p.author_id
  JOIN socialapp.t_notification_preferences pref ON pref.user_id = t.tagged_user_id;

-- Lời mời kết bạn đang chờ, và lời mời đã được chấp nhận.
INSERT INTO socialapp.t_notifications
    (recipient_id, actor_id, type, title, body, reference_id, reference_type, channel, is_read, sent_at, created_at)
SELECT fr.addressee_id,
       fr.requester_id,
       'FRIEND_REQUEST',
       actor.full_name || ' đã gửi lời mời kết bạn',
       NULL,
       fr.id,
       'FRIEND_REQUEST',
       CASE WHEN pref.email_enabled THEN 'BOTH' ELSE 'PUSH' END,
       FALSE,
       fr.created_at,
       fr.created_at
  FROM socialapp.t_friend_requests fr
  JOIN socialapp.t_users actor ON actor.id = fr.requester_id
  JOIN socialapp.t_notification_preferences pref ON pref.user_id = fr.addressee_id
 WHERE fr.status = 'PENDING';

INSERT INTO socialapp.t_notifications
    (recipient_id, actor_id, type, title, body, reference_id, reference_type, channel, is_read, sent_at, created_at)
SELECT fr.requester_id,
       fr.addressee_id,
       'FRIEND_ACCEPTED',
       actor.full_name || ' đã chấp nhận lời mời kết bạn',
       NULL,
       fr.id,
       'FRIEND_REQUEST',
       CASE WHEN pref.email_enabled THEN 'BOTH' ELSE 'PUSH' END,
       TRUE,
       fr.updated_at,
       fr.updated_at
  FROM socialapp.t_friend_requests fr
  JOIN socialapp.t_users actor ON actor.id = fr.addressee_id
  JOIN socialapp.t_notification_preferences pref ON pref.user_id = fr.requester_id
 WHERE fr.status = 'ACCEPTED'
   AND fr.id % 5 = 0;

-- Có người nhận lời tham dự sự kiện.
INSERT INTO socialapp.t_notifications
    (recipient_id, actor_id, type, title, body, reference_id, reference_type, channel, is_read, sent_at, created_at)
SELECT p.author_id,
       rs.user_id,
       'EVENT_RSVP',
       actor.full_name || ' sẽ tham dự sự kiện của bạn',
       p.event_details ->> 'eventTitle',
       p.id,
       'POST',
       CASE WHEN pref.email_enabled THEN 'BOTH' ELSE 'PUSH' END,
       rs.created_at < now() - INTERVAL '7 days',
       rs.created_at,
       rs.created_at
  FROM socialapp.t_event_rsvps rs
  JOIN socialapp.t_posts p ON p.id = rs.post_id
  JOIN socialapp.t_users actor ON actor.id = rs.user_id
  JOIN socialapp.t_notification_preferences pref ON pref.user_id = p.author_id
 WHERE rs.status = 'GOING'
   AND rs.id % 3 = 0;

-- Sách được đánh giá, và sách được mua.
INSERT INTO socialapp.t_notifications
    (recipient_id, actor_id, type, title, body, reference_id, reference_type, channel, is_read, sent_at, created_at)
SELECT b.author_id,
       rv.user_id,
       'BOOK_REVIEW',
       actor.full_name || ' đã đánh giá sách của bạn ' || rv.rating || ' sao',
       COALESCE(rv.feedback, b.title),
       b.id,
       'BOOK',
       CASE WHEN pref.email_enabled THEN 'BOTH' ELSE 'PUSH' END,
       rv.created_at < now() - INTERVAL '7 days',
       rv.created_at,
       rv.created_at
  FROM socialapp.t_book_reviews rv
  JOIN socialapp.t_books b ON b.id = rv.book_id
  JOIN socialapp.t_users actor ON actor.id = rv.user_id
  JOIN socialapp.t_notification_preferences pref ON pref.user_id = b.author_id
 WHERE rv.id % 4 = 0;

INSERT INTO socialapp.t_notifications
    (recipient_id, actor_id, type, title, body, reference_id, reference_type, channel, is_read, sent_at, created_at)
SELECT b.author_id,
       pu.buyer_id,
       'BOOK_PURCHASED',
       actor.full_name || ' đã mua sách của bạn',
       b.title,
       b.id,
       'BOOK',
       CASE WHEN pref.email_enabled THEN 'BOTH' ELSE 'PUSH' END,
       pu.paid_at < now() - INTERVAL '7 days',
       pu.paid_at,
       pu.paid_at
  FROM socialapp.t_book_purchases pu
  JOIN socialapp.t_books b ON b.id = pu.book_id
  JOIN socialapp.t_users actor ON actor.id = pu.buyer_id
  JOIN socialapp.t_notification_preferences pref ON pref.user_id = b.author_id
 WHERE pu.payment_status = 'COMPLETED'
   AND pu.id % 3 = 0;

SELECT setval('socialapp.q_notifications_id',
              (SELECT COALESCE(MAX(id), 1) FROM socialapp.t_notifications), true);
