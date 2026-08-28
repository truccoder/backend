-- =============================================================================================
-- Ảnh bìa hồ sơ.
--
-- Trước migration này người dùng có đúng MỘT trường ảnh — profile_picture_url — nên trang hồ sơ
-- không có gì để vẽ ở dải trên cùng. Chữ "cover" xuất hiện đúng một lần trong toàn bộ V1-V64:
-- V44, và đó là ảnh bìa SÁCH, không liên quan.
--
-- NULL được phép, và đó là trạng thái mặc định chứ không phải dữ liệu thiếu: hồ sơ không đặt bìa
-- là chuyện bình thường, frontend đã dựng sẵn nhánh "khối màu token" cho trường hợp đó và nhánh
-- ấy phải sống vĩnh viễn.
--
-- ── varchar(512), không phải 2048 ────────────────────────────────────────────────────────────
-- Bằng đúng profile_picture_url ngay bên cạnh. Giá trị ghi vào đây do CHÍNH backend sinh ra ở
-- POST /v1/api/media (`{minio.url}/post-media/posts/{userId}/{uuid}.{ext}`) — dài dưới 150 ký
-- tự — chứ không phải một URL tuỳ ý từ máy chủ người lạ như t_trending_items.image_url. Trần
-- rộng hơn chỉ mời người ta dán vào đây thứ không nên nằm ở đây.
--
-- ── URL tuyệt đối, cùng đặc tính (và cùng cái bẫy) với ảnh đại diện ──────────────────────────
-- Cột này lưu URL đầy đủ, không lưu object key — y như profile_picture_url. Nghĩa là nó thừa kế
-- nguyên vẹn cảnh báo ghi ở đầu db/seed-dev/V66__seed_dev_avatars.sql: giá trị đã ghi gắn chặt
-- với địa chỉ MinIO tại thời điểm upload, đổi `minio.url` thì mọi hàng cũ trỏ sai.
--
-- Đã cân nhắc lưu object key rồi ghép URL lúc đọc — sạch hơn thật. Không làm, vì làm thế là để
-- hai trường ảnh của cùng một người dùng theo hai quy ước khác nhau: mỗi chỗ đọc user phải nhớ
-- trường nào cần ghép, trường nào không. Đó chính là "làm một nửa" mà backend-plan cảnh báo.
-- Muốn đổi thì đổi cả hai cùng lúc, trong một migration riêng có kèm bước chuyển dữ liệu cũ.
-- =============================================================================================

ALTER TABLE socialapp.t_users
    ADD COLUMN cover_image_url VARCHAR(512);
