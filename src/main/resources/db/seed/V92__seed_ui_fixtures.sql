-- =============================================================================================
-- Fixture giao diện — những ca mà dữ liệu sinh theo phân phối KHÔNG bảo đảm sẽ có.
--
-- KHÔNG PHẢI FILE SINH TỰ ĐỘNG. Viết tay, và đó là chủ ý: mỗi khối dưới đây tồn tại vì một nhánh
-- cụ thể của giao diện, và lý do ấy phải đọc được ngay cạnh dữ liệu. Sinh chúng bằng generator sẽ
-- biến một quyết định thành một tham số.
--
-- ── Vì sao file này chạy CUỐI, sau V84 và V90 ───────────────────────────────────────────────
-- Số bình luận gốc của bốn bài kiểm ca 0/1/2/>=5 phải CHÍNH XÁC, mà V84 rải bình luận theo phép
-- chia dư — chạy trước nó thì một bài "đúng hai bình luận" có thể thành ba. Và khối hạng uy tín ở
-- cuối file cần elite_score đã được V90 tính xong.
--
-- ── Đánh dấu bằng hashtag, không bằng id ────────────────────────────────────────────────────
-- Thế hệ test trước ghi cứng id (5301, 5310, 3021...) nên vỡ ngay khi đổi dữ liệu. Ở đây mỗi bài
-- fixture mang một hashtag `fixture_<tên_ca>`, và test hỏi "bài mang dấu zero_comments có bao
-- nhiêu bình luận gốc" thay vì "bài 100841". Đổi dải id sau này không phải sửa test.
--
-- Hashtag fixture dùng dải id 1201+, tách khỏi dải 1001-1120 của hashtag thường. usage_count của
-- chúng vẫn được tính lại ở cuối file như mọi hashtag khác: bất biến "usage_count bằng số bài
-- thật sự gắn thẻ" phải đúng với MỌI hàng, nếu không trang hashtag hiện một con số sai và không
-- có gì phân biệt được đó là fixture hay là lỗi. Chúng không lọt vào gợi ý nhờ số đếm nhỏ, chứ
-- không nhờ một ngoại lệ trong dữ liệu.
-- =============================================================================================

INSERT INTO socialapp.t_hashtags (id, name, usage_count, created_at) VALUES
    (1201, 'fixture_zero_comments',  0, now()),
    (1202, 'fixture_one_comment',    0, now()),
    (1203, 'fixture_two_comments',   0, now()),
    (1204, 'fixture_many_comments',  0, now()),
    (1205, 'fixture_zero_reactions', 0, now()),
    (1206, 'fixture_long_content',   0, now()),
    (1207, 'fixture_medium_content', 0, now()),
    (1208, 'fixture_missing_image',  0, now()),
    (1209, 'fixture_blocked_thread', 0, now()),
    (1210, 'fixture_mixed_levels',   0, now());

-- Gắn dấu vào các bài mà V83/V84 đã dựng sẵn cho từng ca.
INSERT INTO socialapp.t_post_hashtags (post_id, hashtag_id) VALUES
    (100841, 1201),
    (100842, 1202),
    (100843, 1203),
    (100844, 1204),
    (100847, 1205);

-- Ba ca còn lại được tìm bằng ĐẶC ĐIỂM của chính dữ liệu, không bằng id: nếu generator đổi cách
-- rải thì dấu vẫn gắn đúng bài, hoặc không gắn được bài nào và test đỏ ngay — cả hai đều tốt hơn
-- một dấu gắn nhầm chỗ.
INSERT INTO socialapp.t_post_hashtags (post_id, hashtag_id)
SELECT id, 1206 FROM socialapp.t_posts
 WHERE LENGTH(content) >= 1200 ORDER BY id LIMIT 1;

INSERT INTO socialapp.t_post_hashtags (post_id, hashtag_id)
SELECT id, 1207 FROM socialapp.t_posts
 WHERE LENGTH(content) BETWEEN 550 AND 750 ORDER BY id LIMIT 1;

INSERT INTO socialapp.t_post_hashtags (post_id, hashtag_id)
SELECT id, 1208 FROM socialapp.t_posts
 WHERE images::text LIKE '%khong-ton-tai%' ORDER BY id LIMIT 1;

-- =============================================================================================
-- S4 · Một bài mà sau khi lọc chặn, hai bình luận cũ nhất CÒN LẠI đều là trả lời.
--
-- Vì sao ca này đáng một khối riêng: luồng đọc bình luận lọc bỏ tác giả bị chặn TRƯỚC khi cắt
-- lấy hai bình luận đầu để xem trước. Nếu hai bình luận cũ nhất của bài luôn là bình luận gốc thì
-- nhánh "phần xem trước chỉ còn toàn trả lời" chưa bao giờ chạy — và nó là nhánh dễ hỏng nhất, vì
-- một trả lời không tự nói nó đang trả lời ai.
--
-- Dựng bằng cách: tạo một bài riêng, cho hai người bị 9001 chặn viết hai bình luận GỐC sớm nhất,
-- rồi hai người khác viết TRẢ LỜI muộn hơn. Với người xem là 9001, hai bình luận cũ nhất còn lại
-- sau khi lọc chính là hai trả lời đó.
-- =============================================================================================

INSERT INTO socialapp.t_posts
    (id, content, visibility, author_id, post_type, moderation_status, created_at, updated_at)
VALUES
    (102998,
     'Bài dùng để kiểm phần xem trước bình luận sau khi lọc người bị chặn. Hai bình luận gốc sớm nhất là của người đã bị chặn.',
     'PUBLIC', 9003, 'REGULAR', 'APPROVED',
     now() - INTERVAL '40 days', now() - INTERVAL '40 days');

INSERT INTO socialapp.t_post_hashtags (post_id, hashtag_id) VALUES (102998, 1209);

-- Hai người sẽ bị chặn. Chọn từ dải id cố định để khối này không phụ thuộc vào phép rải ngẫu
-- nhiên của generator.
INSERT INTO socialapp.t_user_blocks (blocker_id, blocked_id, created_at)
SELECT 9001, u.id, now() - INTERVAL '35 days'
  FROM (VALUES (9101), (9102)) AS u(id)
 WHERE NOT EXISTS (SELECT 1 FROM socialapp.t_user_blocks b
                    WHERE b.blocker_id = 9001 AND b.blocked_id = u.id);

INSERT INTO socialapp.t_comments (id, post_id, author_id, content, parent_id, created_at, updated_at)
VALUES
    -- Hai bình luận GỐC cũ nhất — của người bị 9001 chặn, nên 9001 không nhìn thấy.
    (209101, 102998, 9101, 'Bình luận gốc cũ nhất, của một tài khoản đã bị chặn.', NULL,
     now() - INTERVAL '39 days', now() - INTERVAL '39 days'),
    (209102, 102998, 9102, 'Bình luận gốc cũ thứ hai, cũng của tài khoản bị chặn.', NULL,
     now() - INTERVAL '38 days', now() - INTERVAL '38 days'),
    -- Một bình luận gốc của người KHÔNG bị chặn, muộn hơn cả hai trả lời bên dưới.
    (209103, 102998, 9103, 'Bình luận gốc muộn nhất, của người không bị chặn.', NULL,
     now() - INTERVAL '10 days', now() - INTERVAL '10 days'),
    -- Hai TRẢ LỜI, cũ hơn bình luận gốc ở trên. Sau khi lọc chặn, đây là hai bình luận cũ nhất
    -- còn lại — và cả hai đều là trả lời.
    (209104, 102998, 9104, 'Trả lời cho bình luận đầu, của người không bị chặn.', 209101,
     now() - INTERVAL '37 days', now() - INTERVAL '37 days'),
    (209105, 102998, 9105, 'Trả lời thứ hai, cũng của người không bị chặn.', 209102,
     now() - INTERVAL '36 days', now() - INTERVAL '36 days');

-- =============================================================================================
-- B22 · Một luồng bình luận có ít nhất HAI HẠNG uy tín khác nhau.
--
-- Từ B22, mỗi hàng bình luận hiện chip điểm và tên hạng của người viết. Nếu mọi người trong một
-- luồng cùng hạng thì chip ấy chưa từng được nhìn thấy ở trạng thái nào khác — đúng căn bệnh mà
-- cả đợt dựng lại bộ seed này sinh ra để chữa.
--
-- Khối này chạy SAU V90 nên elite_score đã tính xong, và nó CHỌN người theo điểm thật thay vì ghi
-- cứng id: một người đứng đầu bảng và một người chưa có điểm nào.
-- =============================================================================================

INSERT INTO socialapp.t_posts
    (id, content, visibility, author_id, post_type, moderation_status, created_at, updated_at)
VALUES
    (102999,
     'Bài dùng để kiểm chip hạng trên hàng bình luận: luồng bên dưới cố ý có nhiều hạng khác nhau.',
     'PUBLIC', 9003, 'REGULAR', 'APPROVED',
     now() - INTERVAL '20 days', now() - INTERVAL '20 days');

INSERT INTO socialapp.t_post_hashtags (post_id, hashtag_id) VALUES (102999, 1210);

INSERT INTO socialapp.t_comments (post_id, author_id, content, parent_id, created_at, updated_at)
SELECT 102999, u.id,
       'Bình luận của ' || u.username || ' — dùng để kiểm chip hạng trên hàng bình luận.',
       NULL,
       now() - INTERVAL '19 days', now() - INTERVAL '19 days'
  FROM (
        -- Người điểm cao nhất...
        (SELECT id, username FROM socialapp.t_users
          WHERE role <> 'ADMIN' ORDER BY elite_score DESC LIMIT 1)
        UNION ALL
        -- ...và một người chưa có điểm nào.
        (SELECT id, username FROM socialapp.t_users
          WHERE role <> 'ADMIN' AND elite_score = 0 ORDER BY id LIMIT 1)
        UNION ALL
        -- ...cộng một người ở khoảng giữa, để luồng có ba mức chứ không chỉ hai đầu.
        (SELECT id, username FROM socialapp.t_users
          WHERE role <> 'ADMIN' AND elite_score BETWEEN 100 AND 999
          ORDER BY elite_score DESC LIMIT 1)
       ) AS u(id, username);

-- =============================================================================================
-- Kiểm tra tại chỗ. Ba khối trên đều dựa vào dữ liệu do generator sinh ra, nên nếu generator đổi
-- cách rải mà file này không được cập nhật thì hỏng phải nổ Ở ĐÂY, lúc migrate, chứ không phải
-- trên giao diện dưới dạng một fixture âm thầm biến mất.
-- =============================================================================================

DO $$
DECLARE missing TEXT;
BEGIN
    SELECT string_agg(h.name, ', ') INTO missing
      FROM socialapp.t_hashtags h
     WHERE h.id BETWEEN 1201 AND 1210
       AND NOT EXISTS (SELECT 1 FROM socialapp.t_post_hashtags ph WHERE ph.hashtag_id = h.id);
    IF missing IS NOT NULL THEN
        RAISE EXCEPTION 'Seed hong: dau fixture khong gan duoc bai nao: %', missing;
    END IF;

    IF (SELECT COUNT(DISTINCT
            CASE WHEN u.elite_score >= 5000 THEN 'EXPERT'
                 WHEN u.elite_score >= 1000 THEN 'PRACTITIONER'
                 WHEN u.elite_score >= 100  THEN 'CONTRIBUTOR'
                 ELSE 'NEWCOMER' END)
          FROM socialapp.t_comments c
          JOIN socialapp.t_users u ON u.id = c.author_id
         WHERE c.post_id = 102999) < 2 THEN
        RAISE EXCEPTION 'Seed hong: luong 102999 khong co du hai hang uy tin khac nhau';
    END IF;
END $$;

-- usage_count tính lại cho TOÀN BỘ hashtag, gồm cả các dấu fixture vừa thêm. V83 đã tính một lần
-- rồi, nhưng file này gắn thêm thẻ nên con số ở đó không còn đúng nữa.
UPDATE socialapp.t_hashtags h
   SET usage_count = COALESCE(c.total, 0)
  FROM (SELECT hashtag_id, COUNT(*) AS total
          FROM socialapp.t_post_hashtags GROUP BY hashtag_id) c
 WHERE c.hashtag_id = h.id;

-- Đẩy sequence bình luận quá dải id gán tay ở file này.
SELECT setval('socialapp.q_comments_id',
              GREATEST((SELECT COALESCE(MAX(id), 0) FROM socialapp.t_comments) + 1, 1), false);
SELECT setval('socialapp.q_posts_id',
              GREATEST((SELECT COALESCE(MAX(id), 0) FROM socialapp.t_posts) + 1, 1), false);
