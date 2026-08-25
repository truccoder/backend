-- =============================================================================================
-- Ảnh đại diện — CHỈ MÁY DEV.
--
-- Vì sao nằm ở db/seed-dev chứ không phải db/seed, dù nội dung hoàn toàn vô hại:
-- t_users.profile_picture_url không chứa object key mà chứa URL TUYỆT ĐỐI, ghép từ giá trị cấu
-- hình minio.url tại thời điểm upload (ProfileService.changeProfilePicture). Nên một giá trị ghi
-- cứng ở đây chỉ đúng với đúng một địa chỉ MinIO. Trên production, địa chỉ đó khác, và hàng ghi
-- sẵn sẽ trỏ tới http://localhost:9000 — tức là một loạt ảnh vỡ trên môi trường thật, đúng thứ
-- mà V51 để NULL để tránh.
--
-- Đặt ở seed-dev thì giá trị localhost là ĐÚNG chứ không phải xấp xỉ: db/seed-dev theo định
-- nghĩa chỉ chạy trên máy dev, nơi docker-compose luôn dựng MinIO ở cổng 9000.
--
-- Ba tài khoản có ảnh, phần còn lại giữ NULL. Cần CẢ HAI nhánh: một hồ sơ có ảnh và một hồ sơ
-- rơi về chữ viết tắt. Nếu mọi tài khoản đều có ảnh thì nhánh chữ viết tắt không bao giờ chạy,
-- và ngược lại — đó chính là tình trạng trước file này.
--
-- Object tương ứng do scripts/seed/load-minio-objects.sh nạp lên, đọc key thẳng từ chính file
-- này. Bỏ bước đó thì ba tài khoản dưới đây hiện ảnh vỡ thay vì chữ viết tắt, tức là tệ hơn
-- trạng thái cũ — nên đừng chạy file này mà không chạy script.
-- =============================================================================================

UPDATE socialapp.t_users SET profile_picture_url =
    'http://localhost:9000/profile-pictures/avatars/9001/seed-avatar-truc-anh.png'
 WHERE id = 9001;

UPDATE socialapp.t_users SET profile_picture_url =
    'http://localhost:9000/profile-pictures/avatars/9011/seed-avatar-frontend.png'
 WHERE id = 9011;

UPDATE socialapp.t_users SET profile_picture_url =
    'http://localhost:9000/profile-pictures/avatars/9059/seed-avatar-admin.png'
 WHERE id = 9059;
