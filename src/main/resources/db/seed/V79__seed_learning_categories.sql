-- =============================================================================================
-- Gán chủ đề cho dữ liệu demo đã có: 5 lộ trình (V58), 14 cuốn sách (V55), và các bản giải
-- thích AI (V56, V67).
--
-- V76/V77/V78 thêm cột với DEFAULT 'OTHER', nên sau ba file đó mọi hàng cũ đều nằm chung một
-- tab. Bộ lọc chạy đúng nhưng nhìn thì y hệt như đang hỏng: mở Thư viện ra thấy đúng một tab
-- "Khác" chứa tất cả. File này là thứ làm cho màn hình demo có cái để lọc.
--
-- ── Vì sao là file seed chứ không nằm luôn trong V76/V78 ───────────────────────────────────
-- db/migration chạy ở mọi môi trường, kể cả cơ sở dữ liệu thật không có hàng seed nào. Ghi
-- UPDATE theo id 2001-2005 / 3001-3014 vào đó là đặt tri thức về dữ liệu demo vào chỗ chỉ được
-- phép biết về schema. db/seed mới là nơi biết những id đó nghĩa là gì.
--
-- ── Vì sao liệt kê từng id thay vì đoán theo tiêu đề ───────────────────────────────────────
-- Đoán bằng ILIKE trên title thì 'Kubernetes thực chiến' và 'Đưa mô hình machine learning lên
-- production' đều trúng chữ "production", còn 'Dẫn dắt đội kỹ thuật' không trúng gì. 14 hàng thì
-- viết tay ra là xong, và người đọc file nhìn phát biết ngay cuốn nào vào đâu.
--
-- Chỉ động vào id của seed. Sách và lộ trình do người dùng thật tạo ra ở lại OTHER — không ai
-- ở đây biết nội dung của chúng, và đoán hộ tác giả là cách nhanh nhất để dán sai nhãn.
-- =============================================================================================

-- ── Lộ trình ───────────────────────────────────────────────────────────────────────────────
-- Năm lộ trình seed vốn đã được đặt tên đúng theo năm mảng nghề, nên ánh xạ là 1:1.
UPDATE socialapp.t_roadmaps SET category = 'BACKEND'  WHERE id = 2001;
UPDATE socialapp.t_roadmaps SET category = 'FRONTEND' WHERE id = 2002;
UPDATE socialapp.t_roadmaps SET category = 'DEVOPS'   WHERE id = 2003;
UPDATE socialapp.t_roadmaps SET category = 'DATA_ML'  WHERE id = 2004;
UPDATE socialapp.t_roadmaps SET category = 'SECURITY' WHERE id = 2005;

-- ── Sách ───────────────────────────────────────────────────────────────────────────────────
-- Trải trên 7 trong 9 chủ đề. Cố ý để mỗi chủ đề có số sách khác nhau (BACKEND 4 cuốn, MOBILE 1
-- cuốn): một bộ lọc mà tab nào cũng ra đúng hai kết quả thì không kiểm được là nó có thật sự
-- lọc hay không. MOBILE chỉ có một cuốn cũng chính là ca kiểm thử phân trang đáng giá nhất —
-- cuốn đó (id 3010) không nằm trong trang đầu nếu limit nhỏ, nên nó là bằng chứng bộ lọc chạy ở
-- SQL chứ không phải ở client.
UPDATE socialapp.t_books SET category = 'BACKEND'  WHERE id IN (3001, 3007, 3008, 3014);
UPDATE socialapp.t_books SET category = 'FRONTEND' WHERE id IN (3002, 3009);
UPDATE socialapp.t_books SET category = 'DEVOPS'   WHERE id IN (3003, 3011);
UPDATE socialapp.t_books SET category = 'QA'       WHERE id = 3004;
UPDATE socialapp.t_books SET category = 'SECURITY' WHERE id = 3005;
UPDATE socialapp.t_books SET category = 'DATA_ML'  WHERE id IN (3006, 3012);
UPDATE socialapp.t_books SET category = 'MOBILE'   WHERE id = 3010;
UPDATE socialapp.t_books SET category = 'CAREER'   WHERE id = 3013;

-- ── Bản giải thích AI ──────────────────────────────────────────────────────────────────────
-- Ở đây không có id cố định để liệt kê: V56 sinh hàng bằng SELECT trên t_posts, và concepts của
-- mỗi hàng được chọn từ 5 bộ cố định theo (post_id % 5). Chủ đề bám đúng vào 5 bộ đó, nên nhãn
-- khớp với nội dung thật của hàng thay vì được rải ngẫu nhiên cho đẹp.
--
-- Kết quả chỉ có hai chủ đề, và đó là sự thật về dữ liệu seed: cả 5 bộ concepts đều là chuyện
-- hiệu năng phía server. Rải chúng ra 9 tab cho cân sẽ tạo một màn hình demo đẹp hơn và một cơ
-- sở dữ liệu nói dối. Dữ liệu thật lấy nhãn từ Gemini, nơi bài viết đa dạng hơn nhiều.
--
-- Chặn theo dải id tài khoản seed, không phải "mọi hàng": db/seed chạy cả trên production (quyết
-- định 2026-08-21, xem README của thư mục này), nên một UPDATE không giới hạn ở đây sẽ dán lại
-- nhãn cho các bản giải thích của người dùng thật — những hàng mà Gemini đã phân loại đúng.
-- 9001-9058 là đúng dải V51 tạo ra và V56/V67 gán hàng vào.
UPDATE socialapp.t_explanations
   SET category = CASE post_id % 5
                      -- Connection pool / Timeout / Backpressure — chuyện vận hành.
                      WHEN 3 THEN 'DEVOPS'
                      -- N+1, cache, index, idempotency — chuyện viết dịch vụ.
                      ELSE 'BACKEND'
                  END
 WHERE user_id BETWEEN 9001 AND 9058;
