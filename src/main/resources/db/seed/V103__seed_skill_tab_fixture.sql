-- =============================================================================================
-- Khớp kỹ năng của tài khoản demo chính với hashtag thật — vá tab "Kỹ năng của tôi"
-- (FeedScope.SKILLS) cho 9001.
--
-- Vấn đề: SkillTagResolver nối tên node roadmap (tiếng Việt, do người biên soạn đặt) với tên
-- hashtag (tiếng Anh, do tác giả bài gõ) bằng cách tách từ + bỏ dấu rồi so chuỗi. Cả 8 kỹ năng
-- VERIFIED của 9001 — tài khoản "Cao thủ" dùng để demo (xem README cùng thư mục) — đều mang tên
-- thuần Việt ("Giao thức HTTP", "Kim tự tháp kiểm thử"...), nên không mảnh nào khớp một hashtag có
-- thật. Kết quả: tab Kỹ năng của tôi trống với đúng tài khoản hay được đưa lên demo, bất kể Redis
-- đã fan-out hay chưa — đã xác minh bằng tay qua API trước khi viết file này.
--
-- Sửa: gắn thêm cho 9001 hai node có TÊN TRÙNG hashtag sẵn có — "TypeScript" (node 16) khớp thẳng
-- #typescript, "Python cho dữ liệu" (node 35) khớp #python qua mảnh "python" mà SkillTagResolver
-- tách được từ cụm đó. Không chọn ngẫu nhiên: cả hai hashtag đã có mặt trong feed hiện tại của 9001
-- (kiểm bằng ZINTERSTORE thủ công trên Redis trước khi viết file này — 4 bài #typescript, 5 bài
-- #python), nên tab có nội dung ngay, không cần đổi gì ở tầng fan-out hay chạy lại
-- NEWSFEED_REBUILD_ON_START.
--
-- File MỚI, không sửa V88 — xem quy ước "Đừng sửa tay V81–V90" ở README cùng thư mục.
-- =============================================================================================

INSERT INTO socialapp.t_user_roadmap_progress
    (user_id, node_id, tier, status, proof_url, proof_image_key, verifier_id,
     verified_at, created_at, updated_at)
SELECT 9001, node.id, 'SELF_VERIFIED', 'VERIFIED', NULL, NULL, NULL, NULL,
       now() - INTERVAL '15 days', now() - INTERVAL '15 days'
  FROM (VALUES (16), (35)) AS node(id)
 WHERE NOT EXISTS (
     SELECT 1 FROM socialapp.t_user_roadmap_progress p
      WHERE p.user_id = 9001 AND p.node_id = node.id
 );

-- Kiểm tại chỗ: nếu tên node đổi (generator chạy lại với tham số khác) hoặc hai hashtag mục tiêu
-- không còn tồn tại, file này phải nổ lúc migrate — không được để tab trống lặng lẽ lần nữa.
DO $$
DECLARE node16_name TEXT;
DECLARE node35_name TEXT;
DECLARE hashtag_count INTEGER;
BEGIN
    SELECT name INTO node16_name FROM socialapp.t_roadmap_nodes WHERE id = 16;
    SELECT name INTO node35_name FROM socialapp.t_roadmap_nodes WHERE id = 35;

    IF node16_name IS DISTINCT FROM 'TypeScript' THEN
        RAISE EXCEPTION 'Seed hong: node 16 doi ten (nay la "%"), khong con khop #typescript', node16_name;
    END IF;
    IF node35_name NOT LIKE '%Python%' THEN
        RAISE EXCEPTION 'Seed hong: node 35 doi ten (nay la "%"), khong con khop #python', node35_name;
    END IF;

    SELECT COUNT(*) INTO hashtag_count FROM socialapp.t_hashtags WHERE name IN ('typescript', 'python');
    IF hashtag_count <> 2 THEN
        RAISE EXCEPTION 'Seed hong: thieu hashtag typescript/python ma fixture nay dua vao';
    END IF;
END $$;
