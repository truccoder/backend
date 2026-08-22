-- =============================================================================================
-- Tương tác trên bài viết: cảm xúc, bình luận (có bình luận con), lịch sử tương tác dùng cho
-- xếp hạng feed, RSVP sự kiện, và bài nộp quiz.
--
-- Bình luận dùng id tường minh (6001+) vì QNA cần trỏ acceptedAnswerId tới một bình luận cụ thể,
-- và vì bình luận con phải tham chiếu parent_id của bình luận cha trong cùng file này.
-- =============================================================================================

-- ── Cảm xúc (t_post_reactions) ─────────────────────────────────────────────────────────────
-- Khoá chính là (user_id, post_id) nên mỗi người chỉ thả đúng một cảm xúc cho một bài — sinh
-- theo công thức tất định, không thể trùng cặp.
--
-- Mật độ giảm dần theo id bài: bài mới (id lớn) ít tương tác hơn bài cũ, giống dòng thời gian
-- thật. Điều này cũng làm cho điểm xếp hạng của PostScoringService phân bố khác nhau giữa các
-- bài thay vì bằng phẳng.
INSERT INTO socialapp.t_post_reactions (user_id, post_id, reaction_type, created_at)
SELECT u.id,
       p.id,
       -- Trải đủ 5 giá trị enum ReactionType, LIKE chiếm đa số như thực tế.
       (ARRAY['LIKE','LIKE','LIKE','LOVE','LOVE','HAHA','CRY','ANGRY'])[1 + ((u.id + p.id) % 8)],
       p.created_at + ((u.id % 48) * INTERVAL '1 hour')
  FROM socialapp.t_posts p
  JOIN socialapp.t_users u
    ON u.id BETWEEN 9001 AND 9058
   -- Chọn tất định người nào thả cảm xúc vào bài nào. Toán tử băm thô này cho mỗi bài khoảng
   -- 4-20 lượt, và người thả khác nhau giữa các bài.
   AND ((u.id * 31 + p.id * 17) % 11) < (CASE WHEN p.id < 5100 THEN 4 ELSE 2 END)
 WHERE p.moderation_status = 'APPROVED'
   AND p.visibility <> 'PRIVATE'
   -- Không ai thả cảm xúc cho chính bài mình.
   AND u.id <> p.author_id;

-- ── Bình luận gốc (6001+) ──────────────────────────────────────────────────────────────────
-- Mỗi bài APPROVED công khai nhận 0-4 bình luận gốc. row_number() cấp id liên tục để bình luận
-- con phía dưới tính được parent_id.
INSERT INTO socialapp.t_comments (id, post_id, author_id, content, parent_id, created_at, updated_at)
SELECT 6000 + row_number() OVER (ORDER BY p.id, u.id),
       p.id,
       u.id,
       (ARRAY[
           'Cảm ơn bạn đã chia sẻ, đúng thứ mình đang cần.',
           'Mình cũng từng gặp y hệt, cách xử lý của bạn gọn hơn cách mình làm.',
           'Bạn có thể nói rõ hơn phần cuối được không?',
           'Đã lưu lại để đọc kỹ, nhìn qua thì rất hợp lý.',
           'Chỗ này mình nghĩ còn một cách khác nữa, để mình thử rồi phản hồi lại.',
           'Bài viết rõ ràng quá, đọc một lượt là hiểu.',
           'Mình áp dụng thử và thấy hiệu quả, cảm ơn bạn.',
           'Có bạn nào đã thử cách này trên môi trường thật chưa?'
       ])[1 + ((u.id + p.id * 3) % 8)],
       NULL,
       p.created_at + ((1 + (u.id % 20)) * INTERVAL '1 hour'),
       p.created_at + ((1 + (u.id % 20)) * INTERVAL '1 hour')
  FROM socialapp.t_posts p
  JOIN socialapp.t_users u
    ON u.id BETWEEN 9001 AND 9058
   AND ((u.id * 13 + p.id * 7) % 29) < 2
 WHERE p.moderation_status = 'APPROVED'
   AND p.visibility = 'PUBLIC'
   AND u.id <> p.author_id;

-- ── Bình luận trả lời (bình luận con) ──────────────────────────────────────────────────────
-- parent_id trỏ về bình luận gốc; t_comments có khoá ngoại tự tham chiếu ON DELETE CASCADE nên
-- xoá bình luận cha sẽ kéo theo toàn bộ nhánh trả lời.
-- Người trả lời chọn là TÁC GIẢ BÀI VIẾT, đúng như hành vi hay gặp nhất: chủ bài phản hồi lại
-- người bình luận.
INSERT INTO socialapp.t_comments (id, post_id, author_id, content, parent_id, created_at, updated_at)
SELECT 7000 + row_number() OVER (ORDER BY c.id),
       c.post_id,
       p.author_id,
       (ARRAY[
           'Cảm ơn bạn, mình sẽ bổ sung thêm phần đó.',
           'Đúng rồi, chỗ đó mình viết chưa rõ. Để mình sửa lại.',
           'Bạn thử cách này xem, mình đã kiểm chứng trên môi trường thật.',
           'Câu hỏi hay, mình sẽ viết riêng một bài về phần này.'
       ])[1 + (c.id % 4)],
       c.id,
       c.created_at + INTERVAL '3 hours',
       c.created_at + INTERVAL '3 hours'
  FROM socialapp.t_comments c
  JOIN socialapp.t_posts p ON p.id = c.post_id
 WHERE c.parent_id IS NULL
   AND c.id % 3 = 0
   AND p.author_id <> c.author_id;

-- ── Chọn câu trả lời được chấp nhận cho bài QNA ────────────────────────────────────────────
-- qna_details.acceptedAnswerId ở V53 để null vì lúc đó bình luận chưa tồn tại. Giờ mới gán được,
-- và chỉ gán cho những bài đã đánh dấu isResolved = true — nếu không thì trạng thái mâu thuẫn:
-- câu hỏi báo đã giải quyết mà không chỉ ra được câu trả lời nào.
UPDATE socialapp.t_posts p
   SET qna_details = jsonb_set(p.qna_details, '{acceptedAnswerId}', to_jsonb(best.comment_id))
  FROM (
        SELECT c.post_id, MIN(c.id) AS comment_id
          FROM socialapp.t_comments c
         WHERE c.parent_id IS NULL
         GROUP BY c.post_id
       ) best
 WHERE p.id = best.post_id
   AND p.post_type = 'QNA'
   AND p.qna_details ->> 'isResolved' = 'true';

-- Câu hỏi nào đánh dấu đã giải quyết mà lại không có bình luận nào để chọn thì trả về chưa giải
-- quyết, thay vì để lại một acceptedAnswerId null trong khi isResolved vẫn là true.
UPDATE socialapp.t_posts
   SET qna_details = jsonb_set(qna_details, '{isResolved}', 'false'::jsonb)
 WHERE post_type = 'QNA'
   AND qna_details ->> 'isResolved' = 'true'
   AND qna_details ->> 'acceptedAnswerId' IS NULL;

-- ── Lịch sử tương tác (t_user_interactions) ────────────────────────────────────────────────
-- Bảng này là nguồn tín hiệu cho việc xếp hạng feed. InteractionType chỉ có LIKE và COMMENT, và
-- mỗi dòng phải phản ánh một tương tác CÓ THẬT ở trên — nên dựng thẳng từ hai bảng kia thay vì
-- sinh ngẫu nhiên, để tín hiệu xếp hạng không mâu thuẫn với những gì hiển thị trên bài.
INSERT INTO socialapp.t_user_interactions (user_id, post_id, author_id, type, created_at)
SELECT r.user_id, r.post_id, p.author_id, 'LIKE', r.created_at
  FROM socialapp.t_post_reactions r
  JOIN socialapp.t_posts p ON p.id = r.post_id;

INSERT INTO socialapp.t_user_interactions (user_id, post_id, author_id, type, created_at)
SELECT c.author_id, c.post_id, p.author_id, 'COMMENT', c.created_at
  FROM socialapp.t_comments c
  JOIN socialapp.t_posts p ON p.id = c.post_id
 WHERE c.author_id <> p.author_id;

-- ── RSVP sự kiện ───────────────────────────────────────────────────────────────────────────
-- UNIQUE (post_id, user_id) nên mỗi người một trạng thái cho một sự kiện.
-- RsvpStatus: GOING, INTERESTED, NOT_GOING.
INSERT INTO socialapp.t_event_rsvps (post_id, user_id, status, created_at)
SELECT p.id,
       u.id,
       (ARRAY['GOING','GOING','GOING','INTERESTED','INTERESTED','NOT_GOING'])[1 + ((u.id + p.id) % 6)],
       p.created_at + ((u.id % 72) * INTERVAL '1 hour')
  FROM socialapp.t_posts p
  JOIN socialapp.t_users u
    ON u.id BETWEEN 9001 AND 9058
   AND ((u.id * 7 + p.id * 3) % 5) < 2
 WHERE p.post_type = 'EVENT'
   AND u.id <> p.author_id;

-- ── Bài nộp quiz ───────────────────────────────────────────────────────────────────────────
-- `answers` là List<Integer> và QuizService bắt buộc số phần tử BẰNG số câu hỏi của quiz. Mọi
-- quiz trong V53 đều có đúng 3 câu, nên mỗi bài nộp ở đây có đúng 3 lựa chọn.
--
-- `score` phải là số câu đúng tính theo correctOptionIndex trong chính quiz đó, nếu không màn
-- hình kết quả sẽ hiện một điểm số không khớp với đáp án ngay bên cạnh. Vì vậy điểm được TÍNH
-- từ dữ liệu quiz chứ không gõ tay: đếm số vị trí mà lựa chọn trùng đáp án đúng.
INSERT INTO socialapp.t_quiz_answers (post_id, user_id, answers, score, created_at)
SELECT s.post_id,
       s.user_id,
       s.answers,
       (SELECT count(*)
          FROM jsonb_array_elements(s.questions) WITH ORDINALITY AS q(question, idx)
         WHERE (question ->> 'correctOptionIndex')::int
               = (s.answers ->> (idx - 1)::int)::int),
       s.created_at
  FROM (
        SELECT p.id AS post_id,
               u.id AS user_id,
               p.quiz_details -> 'questions' AS questions,
               -- Ba lựa chọn sinh tất định trong khoảng 0-3 (mọi câu hỏi đều có 4 phương án).
               jsonb_build_array((u.id * 3 + p.id) % 4,
                                 (u.id * 5 + p.id) % 4,
                                 (u.id * 7 + p.id) % 4) AS answers,
               p.created_at + ((u.id % 60) * INTERVAL '1 hour') AS created_at
          FROM socialapp.t_posts p
          JOIN socialapp.t_users u
            ON u.id BETWEEN 9001 AND 9058
           AND ((u.id * 11 + p.id) % 4) < 2
         WHERE p.quiz_details IS NOT NULL
           AND u.id <> p.author_id
       ) s;

-- ── Đẩy sequence qua vùng id tường minh ────────────────────────────────────────────────────
SELECT setval('socialapp.q_comments_id', (SELECT MAX(id) FROM socialapp.t_comments), true);
SELECT setval('socialapp.q_user_interactions_id',
              (SELECT COALESCE(MAX(id), 1) FROM socialapp.t_user_interactions), true);
SELECT setval('socialapp.q_event_rsvps_id',
              (SELECT COALESCE(MAX(id), 1) FROM socialapp.t_event_rsvps), true);
