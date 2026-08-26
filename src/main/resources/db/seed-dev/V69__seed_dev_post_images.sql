-- =============================================================================================
-- S10 · Ảnh trong bài viết — CHỈ MÁY DEV.
--
-- Đo ngày 24/08 qua GET /v1/api/users/{id}/posts trên dải 9000-9030: 29 tác giả, 80 bài,
-- images = 0, articleDetails.coverImage = 0, linkDetails.thumbnailUrl = 0. Cả ba đường dẫn ảnh
-- mà backend đã hỗ trợ sẵn đều rỗng trên mọi bài, nên sản phẩm không hiện một tấm ảnh nào ở bất
-- kỳ đâu — trong khi kịch bản demo có một ô kiểm là "bảng tin có bài, có ảnh".
--
-- Không cần backend đổi mã: `images`, `coverImage` và `thumbnailUrl` đã thông cả hai chiều từ
-- lâu. Chỉ thiếu dữ liệu.
--
-- ── Vì sao nằm ở db/seed-dev chứ không phải db/seed ─────────────────────────────────────────
-- Cùng đúng một lý do đã ghi ở đầu V66__seed_dev_avatars.sql: các URL dưới đây là URL TUYỆT ĐỐI
-- trỏ tới http://localhost:9000, khớp cách MediaService dựng URL (`{minio.url}/post-media/...`).
-- Ghi cứng localhost vào db/seed là rải ảnh vỡ lên production. Ở seed-dev thì localhost:9000 là
-- ĐÚNG chứ không phải xấp xỉ, vì docker-compose luôn dựng MinIO ở đó.
--
-- Object tương ứng do docker compose up nạp lên (minio-seed-objects đọc key thẳng từ chính file
-- này; tiền tố `posts/` → bucket post-media). Không có object thì mọi bài dưới đây hiện ảnh vỡ —
-- tệ hơn trạng thái cũ là không có ảnh nào.
--
-- ── UPDATE chứ không INSERT ─────────────────────────────────────────────────────────────────
-- Gắn ảnh vào bài đã có thay vì thêm bài mới. Thêm bài mới sẽ phải tự lo author, hashtag, bình
-- luận, cảm xúc và một suất trong fan-out — tức là dựng lại một phần V53/V54 chỉ để có chỗ treo
-- ảnh. Các bài được chọn đều là bài PUBLIC đã APPROVED nên chúng đã nằm sẵn trong bảng tin.
-- =============================================================================================

-- ── Ba nhánh bố cục của lưới ảnh ────────────────────────────────────────────────────────────
-- MỘT ảnh, HAI ảnh và BỐN ảnh là ba bố cục khác nhau phía client, không phải một bố cục lặp
-- lại. Thiếu ca nào thì nhánh đó không bao giờ chạy — đúng kiểu thiếu sót mà V65 đã sửa cho
-- phần kẹp nội dung.
UPDATE socialapp.t_posts SET images =
    '["http://localhost:9000/post-media/posts/9001/seed-post-single.png"]'::jsonb
 WHERE id = 5301;

UPDATE socialapp.t_posts SET images =
    '["http://localhost:9000/post-media/posts/9002/seed-post-pair-a.png",
      "http://localhost:9000/post-media/posts/9002/seed-post-pair-b.png"]'::jsonb
 WHERE id = 5302;

UPDATE socialapp.t_posts SET images =
    '["http://localhost:9000/post-media/posts/9010/seed-post-grid-a.png",
      "http://localhost:9000/post-media/posts/9010/seed-post-grid-b.png",
      "http://localhost:9000/post-media/posts/9010/seed-post-grid-c.png",
      "http://localhost:9000/post-media/posts/9010/seed-post-grid-d.png",
      "http://localhost:9000/post-media/posts/9010/seed-post-grid-e.png"]'::jsonb
 WHERE id = 5313;

-- ── Ca ảnh hỏng ─────────────────────────────────────────────────────────────────────────────
-- Key cố ý KHÔNG được script nạp lên, y hệt cách quyển sách 3021 trong V65 dựng lại nhánh lỗi
-- kho lưu trữ. Bucket post-media mở đọc công khai nên URL này trả 404 chứ không phải 403 —
-- dù sao thì với thẻ <img> cả hai đều là một nhánh: ảnh không tải được.
--
-- Cần thiết vì URL ảnh ở đây trỏ ra ngoài và có ngày chết thật: nhánh dự phòng phải được nhìn
-- thấy ít nhất một lần, thay vì lần đầu tiên nó chạy là trên máy người dùng.
UPDATE socialapp.t_posts SET images =
    '["http://localhost:9000/post-media/posts/9004/seed-object-khong-ton-tai.png"]'::jsonb
 WHERE id = 5310;

-- ── ARTICLE · coverImage ────────────────────────────────────────────────────────────────────
-- V53 để null cả 10 bài ARTICLE kèm ghi chú "không có nơi chứa ảnh thật". Giờ có rồi.
-- jsonb_set thay vì ghi đè cả khối: title và summary của bài này là nội dung thật, viết tay
-- trong V53, và ghi đè nguyên khối chỉ để đổi một trường là cách đánh mất chúng.
UPDATE socialapp.t_posts
   SET article_details = jsonb_set(article_details, '{coverImage}',
       '"http://localhost:9000/post-media/posts/9010/seed-article-cover.png"')
 WHERE id = 5031;

-- ── LINK · thumbnailUrl ─────────────────────────────────────────────────────────────────────
-- Cùng lý do, và đây chính là trường mà POST /v1/api/link-preview sinh ra khi người dùng dán
-- một liên kết. Hàng này là bài LINK đã unfurl xong; các bài 5082-5090 giữ nguyên null, tức là
-- ca "dán liên kết mà chưa lấy được ảnh" — cả hai nhánh đều cần có mặt.
UPDATE socialapp.t_posts
   SET link_details = jsonb_set(link_details, '{thumbnailUrl}',
       '"http://localhost:9000/post-media/posts/9001/seed-link-thumb.png"')
 WHERE id = 5081;

-- ── B18 · Ảnh bìa hồ sơ ─────────────────────────────────────────────────────────────────────
-- Cột vừa thêm ở V68. Đúng một tài khoản có bìa, phần còn lại giữ NULL: nhánh "khối màu token"
-- là nhánh mặc định và phải luôn có dữ liệu để chạy, cùng lập luận với ba tài khoản có avatar
-- trong V66 và phần còn lại rơi về chữ viết tắt.
--
-- 9001 là tài khoản demo, nên đây cũng là hồ sơ người trình bày sẽ mở.
UPDATE socialapp.t_users SET cover_image_url =
    'http://localhost:9000/post-media/posts/9001/seed-profile-cover.png'
 WHERE id = 9001;
