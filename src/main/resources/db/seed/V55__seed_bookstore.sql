-- =============================================================================================
-- Gian sách: 20 đầu sách, đánh giá, và lịch sử mua.
--
-- CÁC CỘT *_key TRỎ VÀO OBJECT TRONG MINIO MÀ SQL KHÔNG TẠO ĐƯỢC.
-- Chạy scripts/seed/load-minio-objects.sh để nạp file mẫu lên đúng những key này, nếu không
-- danh sách sách hiển thị bình thường nhưng bấm tải hoặc xem thử sẽ lỗi vì object không tồn tại.
--
-- Quy ước key lấy đúng theo BookStorageService:
--   nội dung sách   bucket `books`        key `books/<authorId>/<tên>.<pdf|epub>`
--   bản xem thử     bucket `books`        key `previews/<authorId>/<tên>.<pdf|epub>`
--   ảnh bìa         bucket `book-covers`  key `covers/<authorId>/<tên>.png`
--
-- Ảnh bìa lưu KEY chứ không lưu URL (cột tên là cover_image_key, không phải *_url — xem V44:
-- trước đây lưu presigned URL nên mọi ảnh bìa chết sau 24 giờ). Link tải được ký lúc đọc.
-- =============================================================================================

INSERT INTO socialapp.t_books
    (id, author_id, post_id, title, description, file_key, cover_image_key, preview_file_key,
     file_format, file_size_bytes, total_pages, preview_pages, price, currency, is_free,
     download_count, avg_rating, review_count, created_at, updated_at) VALUES
    -- Sáu cuốn đầu gắn với bài giới thiệu sách 5091-5096 ở V53.
    (3001, 9005, 5091, 'Tối ưu Spring Boot trong thực chiến',
     'Gom lại sáu năm kinh nghiệm vận hành dịch vụ Spring Boot: đo trước khi tối ưu, và những chỗ tối ưu thật sự tạo ra khác biệt.',
     'books/9005/seed-spring-toi-uu.pdf', 'covers/9005/seed-spring-toi-uu.png', 'previews/9005/seed-spring-toi-uu.pdf',
     'PDF', 4718592, 248, 20, 149000, 'VND', FALSE, 87, 0.0, 0, now() - INTERVAL '50 days', now() - INTERVAL '50 days'),
    (3002, 9018, 5092, 'React hiện đại cho người đã biết cơ bản',
     'Bỏ qua phần nhập môn, đi thẳng vào server component, quản lý state, và cách chia component để không phải viết lại sau sáu tháng.',
     'books/9018/seed-react-hien-dai.epub', 'covers/9018/seed-react-hien-dai.png', 'previews/9018/seed-react-hien-dai.epub',
     'EPUB', 2359296, 186, 15, 129000, 'VND', FALSE, 64, 0.0, 0, now() - INTERVAL '58 days', now() - INTERVAL '58 days'),
    (3003, 9033, 5093, 'Kubernetes thực chiến',
     'Toàn bộ tình huống gặp thật khi vận hành cụm Kubernetes: pod bị OOMKilled, rolling update kẹt, và cách đọc sự kiện cho đúng.',
     'books/9033/seed-k8s-thuc-chien.pdf', 'covers/9033/seed-k8s-thuc-chien.png', 'previews/9033/seed-k8s-thuc-chien.pdf',
     'PDF', 6291456, 312, 25, 199000, 'VND', FALSE, 122, 0.0, 0, now() - INTERVAL '66 days', now() - INTERVAL '66 days'),
    (3004, 9049, 5094, 'Kiểm thử tự động từ đầu',
     'Kim tự tháp kiểm thử áp dụng vào dự án thật, kèm ví dụ chạy được bằng JUnit và Playwright.',
     'books/9049/seed-kiem-thu-tu-dong.pdf', 'covers/9049/seed-kiem-thu-tu-dong.png', 'previews/9049/seed-kiem-thu-tu-dong.pdf',
     'PDF', 3670016, 204, 18, 0, 'VND', TRUE, 341, 0.0, 0, now() - INTERVAL '74 days', now() - INTERVAL '74 days'),
    (3005, 9045, 5095, 'Bảo mật ứng dụng web cho lập trình viên',
     'Viết cho người viết code chứ không cho người kiểm thử xâm nhập: mỗi lỗ hổng kèm đoạn code sinh ra nó và đoạn code sửa nó.',
     'books/9045/seed-bao-mat-web.pdf', 'covers/9045/seed-bao-mat-web.png', 'previews/9045/seed-bao-mat-web.pdf',
     'PDF', 5242880, 276, 22, 179000, 'VND', FALSE, 98, 0.0, 0, now() - INTERVAL '82 days', now() - INTERVAL '82 days'),
    (3006, 9039, 5096, 'Nhập môn kỹ thuật dữ liệu',
     'Từ tệp CSV tới pipeline chạy hằng ngày, viết bằng tiếng Việt cho người mới vào nghề dữ liệu.',
     'books/9039/seed-ky-thuat-du-lieu.epub', 'covers/9039/seed-ky-thuat-du-lieu.png', 'previews/9039/seed-ky-thuat-du-lieu.epub',
     'EPUB', 2883584, 220, 20, 0, 'VND', TRUE, 512, 0.0, 0, now() - INTERVAL '95 days', now() - INTERVAL '95 days'),
    -- Mười bốn cuốn còn lại không gắn bài viết nào (post_id NULL) — đúng như sách đăng thẳng vào
    -- gian sách mà không viết bài giới thiệu.
    (3007, 9010, NULL, 'Thiết kế hệ thống cho đội nhỏ',
     'Chọn kiến trúc theo số người vận hành được, không theo số người muốn dùng.',
     'books/9010/seed-thiet-ke-he-thong.pdf', 'covers/9010/seed-thiet-ke-he-thong.png', 'previews/9010/seed-thiet-ke-he-thong.pdf',
     'PDF', 4194304, 232, 20, 159000, 'VND', FALSE, 76, 0.0, 0, now() - INTERVAL '40 days', now() - INTERVAL '40 days'),
    (3008, 9003, NULL, 'PostgreSQL từ góc nhìn lập trình viên',
     'Không phải sách quản trị cơ sở dữ liệu. Là sách về việc viết truy vấn mà máy chủ chạy được nhanh.',
     'books/9003/seed-postgres-lap-trinh.pdf', 'covers/9003/seed-postgres-lap-trinh.png', 'previews/9003/seed-postgres-lap-trinh.pdf',
     'PDF', 5767168, 288, 24, 169000, 'VND', FALSE, 143, 0.0, 0, now() - INTERVAL '35 days', now() - INTERVAL '35 days'),
    (3009, 9011, NULL, 'Design System bằng Tailwind',
     'Dựng hệ thống thiết kế mà designer và lập trình viên cùng sửa được, không ai phải chờ ai.',
     'books/9011/seed-design-system.epub', 'covers/9011/seed-design-system.png', 'previews/9011/seed-design-system.epub',
     'EPUB', 1835008, 164, 14, 99000, 'VND', FALSE, 52, 0.0, 0, now() - INTERVAL '30 days', now() - INTERVAL '30 days'),
    (3010, 9026, NULL, 'Ứng dụng di động offline-first',
     'Đồng bộ dữ liệu khi mạng chập chờn, và cách xử lý xung đột mà không làm mất dữ liệu người dùng.',
     'books/9026/seed-offline-first.pdf', 'covers/9026/seed-offline-first.png', 'previews/9026/seed-offline-first.pdf',
     'PDF', 3145728, 198, 16, 139000, 'VND', FALSE, 41, 0.0, 0, now() - INTERVAL '28 days', now() - INTERVAL '28 days'),
    (3011, 9035, NULL, 'Quan sát hệ thống: log, metric, trace',
     'Ba trụ cột của observability, và cách chọn cái nào cho câu hỏi nào.',
     'books/9035/seed-observability.pdf', 'covers/9035/seed-observability.png', 'previews/9035/seed-observability.pdf',
     'PDF', 4456448, 244, 20, 0, 'VND', TRUE, 287, 0.0, 0, now() - INTERVAL '25 days', now() - INTERVAL '25 days'),
    (3012, 9042, NULL, 'Đưa mô hình machine learning lên production',
     'Phần khó không nằm ở mô hình, nằm ở dữ liệu đầu vào thay đổi theo thời gian.',
     'books/9042/seed-mlops.epub', 'covers/9042/seed-mlops.png', 'previews/9042/seed-mlops.epub',
     'EPUB', 3407872, 226, 18, 189000, 'VND', FALSE, 67, 0.0, 0, now() - INTERVAL '22 days', now() - INTERVAL '22 days'),
    (3013, 9021, NULL, 'Dẫn dắt đội kỹ thuật',
     'Ghi chép của một người từ vị trí viết code chuyển sang vị trí chịu trách nhiệm cho người viết code.',
     'books/9021/seed-dan-dat-doi.pdf', 'covers/9021/seed-dan-dat-doi.png', 'previews/9021/seed-dan-dat-doi.pdf',
     'PDF', 2621440, 178, 15, 119000, 'VND', FALSE, 93, 0.0, 0, now() - INTERVAL '20 days', now() - INTERVAL '20 days'),
    (3014, 9001, NULL, 'Caching: khi nào nên và khi nào không',
     'Cache giải quyết vấn đề hiệu năng và tạo ra vấn đề nhất quán. Sách bàn về việc đánh đổi đó.',
     'books/9001/seed-caching.pdf', 'covers/9001/seed-caching.png', 'previews/9001/seed-caching.pdf',
     'PDF', 2097152, 152, 12, 89000, 'VND', FALSE, 58, 0.0, 0, now() - INTERVAL '18 days', now() - INTERVAL '18 days'),
    (3015, 9050, NULL, 'Playwright cho người kiểm thử',
     'Viết test end-to-end mà không phải chịu đựng test đỏ ngẫu nhiên.',
     'books/9050/seed-playwright.epub', 'covers/9050/seed-playwright.png', 'previews/9050/seed-playwright.epub',
     'EPUB', 1572864, 142, 12, 0, 'VND', TRUE, 198, 0.0, 0, now() - INTERVAL '15 days', now() - INTERVAL '15 days'),
    (3016, 9036, NULL, 'Terraform trong đội nhiều người',
     'State, module, và cách nhiều người cùng sửa hạ tầng mà không giẫm chân nhau.',
     'books/9036/seed-terraform.pdf', 'covers/9036/seed-terraform.png', 'previews/9036/seed-terraform.pdf',
     'PDF', 3932160, 212, 18, 149000, 'VND', FALSE, 71, 0.0, 0, now() - INTERVAL '12 days', now() - INTERVAL '12 days'),
    (3017, 9055, NULL, 'Nghiên cứu người dùng cho đội sản phẩm nhỏ',
     'Làm nghiên cứu tử tế khi không có ngân sách và không có bộ phận nghiên cứu riêng.',
     'books/9055/seed-ux-research.epub', 'covers/9055/seed-ux-research.png', 'previews/9055/seed-ux-research.epub',
     'EPUB', 2228224, 168, 14, 109000, 'VND', FALSE, 36, 0.0, 0, now() - INTERVAL '10 days', now() - INTERVAL '10 days'),
    (3018, 9056, NULL, 'Viết tài liệu kỹ thuật ai cũng đọc được',
     'Tài liệu tốt bắt đầu bằng vấn đề, không bắt đầu bằng danh sách tính năng.',
     'books/9056/seed-viet-tai-lieu.pdf', 'covers/9056/seed-viet-tai-lieu.png', 'previews/9056/seed-viet-tai-lieu.pdf',
     'PDF', 1310720, 124, 10, 0, 'VND', TRUE, 402, 0.0, 0, now() - INTERVAL '8 days', now() - INTERVAL '8 days'),
    (3019, 9008, NULL, 'Gỡ lỗi hệ thống phân tán',
     'Khi lỗi không nằm ở service nào cả mà nằm ở khoảng giữa chúng.',
     'books/9008/seed-go-loi-phan-tan.pdf', 'covers/9008/seed-go-loi-phan-tan.png', 'previews/9008/seed-go-loi-phan-tan.pdf',
     'PDF', 4980736, 264, 22, 179000, 'VND', FALSE, 84, 0.0, 0, now() - INTERVAL '6 days', now() - INTERVAL '6 days'),
    (3020, 9053, NULL, 'Từ ý tưởng tới bản phát hành đầu tiên',
     'Quy trình rút gọn cho đội chưa có quy trình, viết bởi người đã làm hỏng vài lần.',
     'books/9053/seed-y-tuong-toi-phat-hanh.epub', 'covers/9053/seed-y-tuong-toi-phat-hanh.png', 'previews/9053/seed-y-tuong-toi-phat-hanh.epub',
     'EPUB', 1966080, 156, 13, 99000, 'VND', FALSE, 45, 0.0, 0, now() - INTERVAL '4 days', now() - INTERVAL '4 days');

-- ── Đánh giá sách ──────────────────────────────────────────────────────────────────────────
-- UNIQUE (book_id, user_id): mỗi người một đánh giá cho một cuốn. CHECK rating BETWEEN 1 AND 5.
-- Không ai đánh giá sách của chính mình.
INSERT INTO socialapp.t_book_reviews (book_id, user_id, rating, feedback, created_at, updated_at)
SELECT b.id,
       u.id,
       -- Nghiêng về điểm cao như đánh giá thật, nhưng vẫn có 1-2 sao để bộ lọc theo điểm có dữ liệu.
       --
       -- Mỗi cuốn có một "chất lượng" riêng (b.id % 3) dịch chuyển cả thang điểm của nó. Không có
       -- yếu tố này thì mọi cuốn đều lấy mẫu từ cùng một phân phối, và trung bình của hơn chục
       -- đánh giá hội tụ về gần như cùng một số — 20 cuốn sách cùng 4.0 sao thì bảng xếp hạng
       -- theo điểm chẳng còn gì để xếp.
       GREATEST(1, LEAST(5,
           (ARRAY[5,5,4,4,3,5,2,4,1,5,3,4])[1 + ((u.id * 7 + b.id) % 12)]
           + (CASE (b.id % 3) WHEN 0 THEN 1 WHEN 1 THEN 0 ELSE -1 END)
       )),
       (ARRAY[
           'Sách viết dễ hiểu, ví dụ sát thực tế.',
           'Phần đầu hơi dài nhưng từ chương ba trở đi rất đáng đọc.',
           'Mình áp dụng được ngay vào dự án đang làm.',
           'Nội dung tốt, giá như có thêm bài tập cuối chương.',
           'Đọc xong hiểu được vấn đề mình loay hoay cả tháng.',
           'Hơi nặng với người mới, nhưng với người có kinh nghiệm thì vừa.',
           NULL,
           'Trình bày rõ ràng, hình minh hoạ dễ theo dõi.'
       ])[1 + ((u.id * 5 + b.id) % 8)],
       b.created_at + ((3 + (u.id % 40)) * INTERVAL '1 day'),
       b.created_at + ((3 + (u.id % 40)) * INTERVAL '1 day')
  FROM socialapp.t_books b
  JOIN socialapp.t_users u
    ON u.id BETWEEN 9001 AND 9058
   AND ((u.id * 17 + b.id * 23) % 9) < 2
 WHERE u.id <> b.author_id;

-- avg_rating và review_count phải tính từ t_book_reviews chứ không gõ tay, nếu không trang chi
-- tiết sách hiện "4.5 sao / 12 đánh giá" trong khi danh sách bên dưới lại có số lượng khác.
-- Cột avg_rating là numeric(2,1) nên chỉ giữ được một chữ số thập phân — làm tròn cho khớp.
UPDATE socialapp.t_books b
   SET avg_rating   = COALESCE(r.avg_rating, 0.0),
       review_count = COALESCE(r.n, 0)
  FROM (SELECT book_id, round(avg(rating)::numeric, 1) AS avg_rating, count(*) AS n
          FROM socialapp.t_book_reviews GROUP BY book_id) r
 WHERE b.id = r.book_id;

-- ── Lịch sử mua sách ───────────────────────────────────────────────────────────────────────
-- Chỉ sách TRẢ PHÍ mới có giao dịch: sách miễn phí tải thẳng, không đi qua cổng thanh toán.
-- UNIQUE (book_id, buyer_id) — một người mua một cuốn đúng một lần.
-- UNIQUE (transaction_ref) — mã giao dịch dựng từ cặp (book, buyer) nên chắc chắn không trùng.
--
-- PaymentStatus trải đủ bốn giá trị: phần lớn COMPLETED, còn lại PENDING (bỏ giữa chừng),
-- FAILED, và REFUNDED. amount lấy đúng giá sách tại thời điểm mua.
INSERT INTO socialapp.t_book_purchases
    (book_id, buyer_id, amount, currency, payment_status, transaction_ref, gateway_transaction_no,
     payment_method, payment_link_id, paid_at, created_at, updated_at)
SELECT b.id,
       u.id,
       b.price,
       'VND',
       s.status,
       'SEED-' || b.id || '-' || u.id,
       CASE WHEN s.status = 'COMPLETED' THEN '2606' || lpad((b.id * 100 + (u.id - 9000))::text, 8, '0') END,
       CASE WHEN s.status IN ('COMPLETED', 'REFUNDED') THEN 'MOMO' END,
       CASE WHEN s.status <> 'FAILED' THEN 'seed-link-' || b.id || '-' || u.id END,
       CASE WHEN s.status IN ('COMPLETED', 'REFUNDED')
            THEN b.created_at + ((2 + (u.id % 50)) * INTERVAL '1 day') END,
       b.created_at + ((2 + (u.id % 50)) * INTERVAL '1 day'),
       b.created_at + ((2 + (u.id % 50)) * INTERVAL '1 day')
  FROM socialapp.t_books b
  JOIN socialapp.t_users u
    ON u.id BETWEEN 9001 AND 9058
   AND ((u.id * 29 + b.id * 13) % 8) < 2
 CROSS JOIN LATERAL (
     -- Modulo 9, KHÔNG phải 8. Điều kiện lọc phía trên đã dùng `% 8`; nếu biểu thức chọn trạng
     -- thái cũng chia 8 thì hai cái tương quan với nhau và tập kết quả chỉ rơi vào vài nhánh cố
     -- định — lần chạy đầu ra đúng như vậy: chỉ sinh được COMPLETED và PENDING, không có dòng
     -- FAILED hay REFUNDED nào. Dùng hai modulo nguyên tố cùng nhau thì cả bốn trạng thái đều xuất hiện.
     SELECT (ARRAY['COMPLETED','COMPLETED','COMPLETED','COMPLETED','COMPLETED',
                   'PENDING','PENDING','FAILED','REFUNDED'])[1 + ((u.id * 3 + b.id * 7) % 9)] AS status
 ) s
 WHERE b.is_free = FALSE
   AND u.id <> b.author_id;

-- download_count chỉ nên phản ánh lượt tải có thật. Với sách trả phí, "có thật" nghĩa là đã
-- thanh toán xong; với sách miễn phí thì giữ nguyên con số đã ghi ở trên vì không có giao dịch
-- nào để đếm.
UPDATE socialapp.t_books b
   SET download_count = p.n
  FROM (SELECT book_id, count(*) AS n
          FROM socialapp.t_book_purchases
         WHERE payment_status = 'COMPLETED'
         GROUP BY book_id) p
 WHERE b.id = p.book_id
   AND b.is_free = FALSE;

-- ── Đẩy sequence qua vùng id tường minh ────────────────────────────────────────────────────
SELECT setval('socialapp.q_books_id', (SELECT MAX(id) FROM socialapp.t_books), true);
SELECT setval('socialapp.q_book_reviews_id',
              (SELECT COALESCE(MAX(id), 1) FROM socialapp.t_book_reviews), true);
SELECT setval('socialapp.q_book_purchases_id',
              (SELECT COALESCE(MAX(id), 1) FROM socialapp.t_book_purchases), true);
