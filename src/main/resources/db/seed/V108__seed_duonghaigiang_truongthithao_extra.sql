-- =============================================================================================
-- Dữ liệu demo BỔ SUNG cho đúng hai tài khoản: 9001 (duonghaigiang, "Cao thủ" BACKEND) và
-- 9133 (truongthithao, Kỹ sư Mobile MID) — xem README.md cùng thư mục và scripts/seed/id-map.md.
--
-- File MỚI, không sửa V81-V90 — xem quy ước "Đừng sửa tay V81–V90" ở README cùng thư mục. Mọi id
-- ở đây do sequence/SERIAL tự cấp (không gán tay) nên không đụng dải id tài liệu hoá trong
-- id-map.md, và mọi INSERT có nguy cơ trùng khoá duy nhất đều tự canh bằng NOT EXISTS — file này
-- chạy được nhiều lần trên cùng một database mà không sinh dữ liệu trùng.
--
-- Bao phủ: bài viết đủ 8 PostType × 3 PostVisibility, thêm bạn bè (Postgres + Neo4j, xem
-- friend-graph.cypher), thêm dự án đã đăng (đủ ba ProjectStatus) và dự án đã tham gia (đủ bốn
-- ApplicationStatus), thêm sách đã đăng + đã mua + đánh giá (cả hai chiều: người khác đánh giá
-- sách mới đăng, 9001/9133 đánh giá sách đã mua), thêm bản giải thích AI (Kho lưu trữ), thêm vi
-- phạm và khiếu nại (đủ ba AppealStatus).
--
-- KHÔNG bao phủ ở đây (cần bước ngoài Flyway, xem ghi chú cuối file):
--   · Đồ thị bạn bè Neo4j — cạnh mới đã được thêm vào friend-graph.cypher, cần
--     NEO4J_SEED_ON_START=true ở lần khởi động kế tiếp (hoặc nạp tay bằng cypher-shell).
--   · Chat Stream — cần chạy lại scripts/seed/seed-stream-chat.mjs (có key Stream thật).
--   · Object MinIO của 6 quyển sách mới (bìa/nội dung/preview) — key đã khai trong
--     seed-manifest.tsv, cần `docker compose up minio-seed-objects minio-init` lại (dev) hoặc
--     bước tương đương ở production để MinIOSeedObjectInitializer nạp chúng.
-- =============================================================================================

SET LOCAL statement_timeout = 0;

-- ─────────────────────────────────────────────────────────────────────────────────────────────
-- 1. BÀI VIẾT MỚI — đủ 8 PostType, mỗi loại đủ 3 PostVisibility, cho cả hai tài khoản.
--
-- Không ảnh (images để trống): mọi ảnh mới đều cần một object MinIO thật khai trong
-- seed-manifest.tsv, và seed này không đi kèm bước nạp MinIO — một cột trỏ tới object không tồn
-- tại tệ hơn NULL (xem quy ước ở README).
--
-- Hai bài REGULAR/PUBLIC và hai bài khác (CODE_SNIPPET/PUBLIC của 9001, LINK/PUBLIC của 9133) cố
-- ý mang moderation_status = 'REJECTED' — đó là bốn bài làm mục tiêu cho phần vi phạm ở mục 11.
-- Một bài mỗi người ở trạng thái PENDING_REVIEW, để hàng đợi kiểm duyệt có việc từ chính hai tài
-- khoản này.
-- ─────────────────────────────────────────────────────────────────────────────────────────────

-- EVENT
INSERT INTO socialapp.t_posts (content, visibility, author_id, post_type, moderation_status, event_details, created_at, updated_at)
SELECT * FROM (VALUES
    ('Buổi chia sẻ: Vận hành PostgreSQL ở quy mô lớn — 30 phút trình bày, 30 phút hỏi đáp, đăng ký sớm còn chỗ nhé cả nhà.',
     'PUBLIC', 9001, 'EVENT', 'APPROVED',
     '{"eventTitle":"Vận hành PostgreSQL ở quy mô lớn","eventDescription":"Chia sẻ kinh nghiệm đánh index và giao dịch cho hệ thống chịu tải cao.","startTime":"2026-10-10T18:30:00+07:00","endTime":"2026-10-10T21:00:00+07:00","timezone":"Asia/Ho_Chi_Minh","location":"Toà nhà Innovation Hub, Quận 1","onlineUrl":null,"maxAttendees":60}'::jsonb,
     now() - INTERVAL '12 days', now() - INTERVAL '12 days'),
    ('Buổi review kiến trúc nội bộ nhóm Backend đã diễn ra tuần trước — video và slide mình để ở phần bình luận.',
     'FRIENDS', 9001, 'EVENT', 'APPROVED',
     '{"eventTitle":"Review kiến trúc microservices quý 3","eventDescription":"Xem lại các quyết định tách dịch vụ trong quý vừa rồi.","startTime":"2026-07-01T18:30:00+07:00","endTime":"2026-07-01T20:30:00+07:00","timezone":"Asia/Ho_Chi_Minh","location":null,"onlineUrl":"https://meet.google.com/seed-9001-b1","maxAttendees":30}'::jsonb,
     now() - INTERVAL '70 days', now() - INTERVAL '70 days'),
    ('Hẹn nhóm nhỏ pair-programming buổi tối, chỉ vài anh em thân quen tham gia thôi nhé.',
     'PRIVATE', 9001, 'EVENT', 'APPROVED',
     '{"eventTitle":"Pair-programming buổi tối","eventDescription":"Cùng nhau giải quyết một bài toán tối ưu truy vấn.","startTime":"2026-11-05T19:00:00+07:00","endTime":"2026-11-05T21:00:00+07:00","timezone":"Asia/Ho_Chi_Minh","location":null,"onlineUrl":"https://meet.google.com/seed-9001-b2","maxAttendees":6}'::jsonb,
     now() - INTERVAL '5 days', now() - INTERVAL '5 days'),
    ('Buổi chia sẻ: Kiến trúc offline-first cho ứng dụng di động — đăng ký sớm còn chỗ nhé cả nhà.',
     'PUBLIC', 9133, 'EVENT', 'APPROVED',
     '{"eventTitle":"Kiến trúc offline-first cho ứng dụng di động","eventDescription":"Ghi trước, đồng bộ sau, và cách giải quyết xung đột dữ liệu.","startTime":"2026-10-20T18:30:00+07:00","endTime":"2026-10-20T21:00:00+07:00","timezone":"Asia/Ho_Chi_Minh","location":"Toà nhà Innovation Hub, Quận 1","onlineUrl":null,"maxAttendees":50}'::jsonb,
     now() - INTERVAL '18 days', now() - INTERVAL '18 days'),
    ('Buổi demo Jetpack Compose nội bộ nhóm Mobile đã diễn ra tháng trước — mọi người xem lại bản ghi ở bình luận nhé.',
     'FRIENDS', 9133, 'EVENT', 'APPROVED',
     '{"eventTitle":"Demo Jetpack Compose nội bộ","eventDescription":"Trình diễn state hoisting và recomposition có chọn lọc.","startTime":"2026-06-15T18:30:00+07:00","endTime":"2026-06-15T20:00:00+07:00","timezone":"Asia/Ho_Chi_Minh","location":null,"onlineUrl":"https://meet.google.com/seed-9133-b1","maxAttendees":25}'::jsonb,
     now() - INTERVAL '80 days', now() - INTERVAL '80 days'),
    ('Cà phê cuối tuần với vài đồng nghiệp cũ, bàn chuyện chuyển sang Kotlin Multiplatform.',
     'PRIVATE', 9133, 'EVENT', 'APPROVED',
     '{"eventTitle":"Cà phê Kotlin Multiplatform","eventDescription":"Trao đổi kinh nghiệm chia sẻ code giữa Android và iOS.","startTime":"2026-11-12T09:00:00+07:00","endTime":"2026-11-12T11:00:00+07:00","timezone":"Asia/Ho_Chi_Minh","location":"Quán cà phê góc đường Nguyễn Huệ","onlineUrl":null,"maxAttendees":8}'::jsonb,
     now() - INTERVAL '3 days', now() - INTERVAL '3 days')
) AS v(content, visibility, author_id, post_type, moderation_status, event_details, created_at, updated_at)
WHERE NOT EXISTS (
    SELECT 1 FROM socialapp.t_posts existing
     WHERE existing.author_id = v.author_id AND existing.content = v.content
);

-- CODE_SNIPPET (bài PUBLIC của 9001 cố ý REJECTED — mục tiêu vi phạm ở mục 11)
INSERT INTO socialapp.t_posts (content, visibility, author_id, post_type, moderation_status, code_snippet_details, created_at, updated_at)
SELECT * FROM (VALUES
    ('Đoạn cấu hình connection pool Hikari mình dùng cho service chịu tải cao, chia sẻ cho ai đang tối ưu tương tự.',
     'PUBLIC', 9001, 'CODE_SNIPPET', 'REJECTED',
     '{"language":"yaml","code":"spring:\n  datasource:\n    hikari:\n      maximum-pool-size: 30\n      minimum-idle: 10\n      connection-timeout: 3000\n      statement-timeout: 15000"}'::jsonb,
     now() - INTERVAL '9 days', now() - INTERVAL '9 days'),
    ('Bạn nào cần một transactional outbox tối giản cho Spring Boot thì đây, mình đang dùng bản này trong dự án thật.',
     'FRIENDS', 9001, 'CODE_SNIPPET', 'APPROVED',
     '{"language":"java","code":"@Transactional\npublic void placeOrder(Order order) {\n  orderRepository.save(order);\n  outboxRepository.save(OutboxEvent.of(order));\n}"}'::jsonb,
     now() - INTERVAL '40 days', now() - INTERVAL '40 days'),
    ('Ghi riêng cho mình: script dọn migration Flyway lỗi trên môi trường staging, đừng chạy nhầm sang prod.',
     'PRIVATE', 9001, 'CODE_SNIPPET', 'APPROVED',
     '{"language":"sql","code":"DELETE FROM flyway_schema_history WHERE success = false;"}'::jsonb,
     now() - INTERVAL '15 days', now() - INTERVAL '15 days'),
    ('Đoạn xử lý retry có jitter cho gọi mạng trên Android, tránh nghẽn khi cả app đồng loạt thử lại.',
     'PUBLIC', 9133, 'CODE_SNIPPET', 'APPROVED',
     '{"language":"kotlin","code":"suspend fun <T> withJitterRetry(times: Int, block: suspend () -> T): T {\n  repeat(times - 1) {\n    try { return block() } catch (e: IOException) { delay((200..800).random().toLong()) }\n  }\n  return block()\n}"}'::jsonb,
     now() - INTERVAL '22 days', now() - INTERVAL '22 days'),
    ('Đoạn dùng CompositionLocal để truyền theme xuống nhiều tầng Composable mà không phải truyền tay từng cấp.',
     'FRIENDS', 9133, 'CODE_SNIPPET', 'APPROVED',
     '{"language":"kotlin","code":"val LocalAppTheme = compositionLocalOf { AppTheme.Default }\n\n@Composable\nfun ThemedScreen(content: @Composable () -> Unit) {\n  CompositionLocalProvider(LocalAppTheme provides AppTheme.Dark) { content() }\n}"}'::jsonb,
     now() - INTERVAL '33 days', now() - INTERVAL '33 days'),
    ('Ghi riêng: cấu hình ProGuard tạm thời để build release không lỗi, cần dọn lại sau.',
     'PRIVATE', 9133, 'CODE_SNIPPET', 'APPROVED',
     '{"language":"groovy","code":"proguardFiles getDefaultProguardFile(\"proguard-android-optimize.txt\"), \"proguard-rules.pro\""}'::jsonb,
     now() - INTERVAL '6 days', now() - INTERVAL '6 days')
) AS v(content, visibility, author_id, post_type, moderation_status, code_snippet_details, created_at, updated_at)
WHERE NOT EXISTS (
    SELECT 1 FROM socialapp.t_posts existing
     WHERE existing.author_id = v.author_id AND existing.content = v.content
);

-- ARTICLE
INSERT INTO socialapp.t_posts (content, visibility, author_id, post_type, moderation_status, article_details, created_at, updated_at)
SELECT * FROM (VALUES
    ('Vì sao service của mình chuyển từ REST thuần sang thêm một lớp CQRS cho phần đọc báo cáo. Bài dài, có ví dụ thật.',
     'PUBLIC', 9001, 'ARTICLE', 'APPROVED',
     '{"title":"Khi nào đáng tách CQRS cho phần đọc","coverImage":null,"summary":"Ghi lại quyết định tách mô hình đọc/ghi sau khi báo cáo tổng hợp làm chậm cả API ghi."}'::jsonb,
     now() - INTERVAL '55 days', now() - INTERVAL '55 days'),
    ('Bài viết riêng cho nhóm: checklist review PR backend mà đội mình đang áp dụng, góp ý thêm nhé.',
     'FRIENDS', 9001, 'ARTICLE', 'APPROVED',
     '{"title":"Checklist review PR cho service Backend","coverImage":null,"summary":"Tám điểm nhóm mình luôn kiểm trước khi duyệt một PR chạm tới tầng dữ liệu."}'::jsonb,
     now() - INTERVAL '28 days', now() - INTERVAL '28 days'),
    ('Ghi chú cá nhân: so sánh chi phí vận hành giữa RDS và tự quản lý PostgreSQL cho dự án phụ của mình.',
     'PRIVATE', 9001, 'ARTICLE', 'APPROVED',
     '{"title":"RDS hay tự quản lý PostgreSQL","coverImage":null,"summary":"Bảng so sánh chi phí cho quy mô nhỏ, chỉ để tham khảo riêng."}'::jsonb,
     now() - INTERVAL '19 days', now() - INTERVAL '19 days'),
    ('Trải nghiệm chuyển đội mình từ XML layout sang hoàn toàn Jetpack Compose sau sáu tháng — được và chưa được.',
     'PUBLIC', 9133, 'ARTICLE', 'APPROVED',
     '{"title":"Sáu tháng chuyển hẳn sang Jetpack Compose","coverImage":null,"summary":"Tốc độ dựng UI nhanh hơn rõ rệt, nhưng debug recomposition lúc đầu khá vất vả."}'::jsonb,
     now() - INTERVAL '48 days', now() - INTERVAL '48 days'),
    ('Bài chia sẻ riêng nhóm Mobile: cách đội mình đo và cải thiện thời gian khởi động app.',
     'FRIENDS', 9133, 'ARTICLE', 'APPROVED',
     '{"title":"Giảm thời gian khởi động app xuống một nửa","coverImage":null,"summary":"Trì hoãn khởi tạo các SDK không cần thiết ngay ở màn hình đầu."}'::jsonb,
     now() - INTERVAL '31 days', now() - INTERVAL '31 days'),
    ('Ghi chú riêng: so sánh Flutter và Kotlin Multiplatform cho dự án cá nhân sắp tới của mình.',
     'PRIVATE', 9133, 'ARTICLE', 'APPROVED',
     '{"title":"Flutter hay Kotlin Multiplatform cho dự án riêng","coverImage":null,"summary":"Cân nhắc giữa tốc độ phát triển và mức độ chia sẻ code gốc."}'::jsonb,
     now() - INTERVAL '11 days', now() - INTERVAL '11 days')
) AS v(content, visibility, author_id, post_type, moderation_status, article_details, created_at, updated_at)
WHERE NOT EXISTS (
    SELECT 1 FROM socialapp.t_posts existing
     WHERE existing.author_id = v.author_id AND existing.content = v.content
);

-- QNA (bài FRIENDS của cả hai cố ý PENDING_REVIEW — hàng đợi kiểm duyệt có việc)
INSERT INTO socialapp.t_posts (content, visibility, author_id, post_type, moderation_status, qna_details, created_at, updated_at)
SELECT * FROM (VALUES
    ('Có ai từng gặp deadlock khi hai transaction cùng cập nhật ngược thứ tự hai bảng chưa? Đã thử sắp lại thứ tự lock nhưng vẫn thỉnh thoảng dính.',
     'PUBLIC', 9001, 'QNA', 'APPROVED',
     '{"isResolved":false,"bountyPoints":50,"acceptedAnswerId":null}'::jsonb,
     now() - INTERVAL '17 days', now() - INTERVAL '17 days'),
    ('Hỏi riêng nhóm: connection pool của mình thỉnh thoảng cạn giờ cao điểm, ai có kinh nghiệm tinh chỉnh không?',
     'FRIENDS', 9001, 'QNA', 'PENDING_REVIEW',
     '{"isResolved":false,"bountyPoints":0,"acceptedAnswerId":null}'::jsonb,
     now() - INTERVAL '2 days', now() - INTERVAL '2 days'),
    ('Ghi chú riêng: cần tra lại vì sao index tổ hợp của mình không được dùng trong một truy vấn cụ thể.',
     'PRIVATE', 9001, 'QNA', 'APPROVED',
     '{"isResolved":true,"bountyPoints":0,"acceptedAnswerId":null}'::jsonb,
     now() - INTERVAL '25 days', now() - INTERVAL '25 days'),
    ('Có ai biết cách xử lý tình trạng RecyclerView giật khi danh sách có ảnh tải từ mạng không? Đã thử đặt kích thước cố định nhưng vẫn giật.',
     'PUBLIC', 9133, 'QNA', 'APPROVED',
     '{"isResolved":false,"bountyPoints":25,"acceptedAnswerId":null}'::jsonb,
     now() - INTERVAL '13 days', now() - INTERVAL '13 days'),
    ('Hỏi riêng nhóm Mobile: app của mình bị crash ngẫu nhiên trên một số máy Android cũ, log không rõ nguyên nhân.',
     'FRIENDS', 9133, 'QNA', 'PENDING_REVIEW',
     '{"isResolved":false,"bountyPoints":0,"acceptedAnswerId":null}'::jsonb,
     now() - INTERVAL '1 days', now() - INTERVAL '1 days'),
    ('Ghi chú riêng: đã thử ba cách khác nhau để giữ trạng thái cuộn khi xoay màn hình, cách thứ ba mới ổn.',
     'PRIVATE', 9133, 'QNA', 'APPROVED',
     '{"isResolved":true,"bountyPoints":0,"acceptedAnswerId":null}'::jsonb,
     now() - INTERVAL '21 days', now() - INTERVAL '21 days')
) AS v(content, visibility, author_id, post_type, moderation_status, qna_details, created_at, updated_at)
WHERE NOT EXISTS (
    SELECT 1 FROM socialapp.t_posts existing
     WHERE existing.author_id = v.author_id AND existing.content = v.content
);

-- POLL
INSERT INTO socialapp.t_posts (content, visibility, author_id, post_type, moderation_status, poll_details, created_at, updated_at)
SELECT * FROM (VALUES
    ('Team backend hỏi nhanh: dự án của bạn đang dùng gì để quản lý migration?',
     'PUBLIC', 9001, 'POLL', 'APPROVED',
     '{"question":"Dự án của bạn đang dùng gì để quản lý migration?","options":[{"id":1,"text":"Flyway","votesCount":41},{"id":2,"text":"Liquibase","votesCount":12},{"id":3,"text":"Tự viết script","votesCount":8},{"id":4,"text":"Không dùng gì cả","votesCount":3}],"allowMultipleVotes":false,"endDate":"2026-10-30T23:59:59+07:00"}'::jsonb,
     now() - INTERVAL '14 days', now() - INTERVAL '14 days'),
    ('Hỏi riêng nhóm: đội bạn review code thế nào?',
     'FRIENDS', 9001, 'POLL', 'APPROVED',
     '{"question":"Đội bạn review code thế nào?","options":[{"id":1,"text":"Bắt buộc 1 người duyệt","votesCount":9},{"id":2,"text":"Bắt buộc 2 người","votesCount":4},{"id":3,"text":"Tuỳ tình huống","votesCount":6},{"id":4,"text":"Không review","votesCount":1}],"allowMultipleVotes":false,"endDate":"2026-06-01T23:59:59+07:00"}'::jsonb,
     now() - INTERVAL '110 days', now() - INTERVAL '110 days'),
    ('Khảo sát riêng cho mình: nên chọn giờ nào để chạy job dọn dữ liệu nặng?',
     'PRIVATE', 9001, 'POLL', 'APPROVED',
     '{"question":"Nên chạy job dọn dữ liệu nặng vào giờ nào?","options":[{"id":1,"text":"Nửa đêm","votesCount":2},{"id":2,"text":"Sáng sớm","votesCount":1},{"id":3,"text":"Cuối tuần","votesCount":1}],"allowMultipleVotes":false,"endDate":"2026-12-01T23:59:59+07:00"}'::jsonb,
     now() - INTERVAL '4 days', now() - INTERVAL '4 days'),
    ('Team mobile hỏi nhanh: bạn ưu tiên nền tảng nào trước khi ra mắt tính năng mới?',
     'PUBLIC', 9133, 'POLL', 'APPROVED',
     '{"question":"Bạn ưu tiên nền tảng nào trước khi ra mắt tính năng mới?","options":[{"id":1,"text":"Android trước","votesCount":22},{"id":2,"text":"iOS trước","votesCount":15},{"id":3,"text":"Cả hai cùng lúc","votesCount":18},{"id":4,"text":"Tuỳ tính năng","votesCount":9}],"allowMultipleVotes":false,"endDate":"2026-11-15T23:59:59+07:00"}'::jsonb,
     now() - INTERVAL '9 days', now() - INTERVAL '9 days'),
    ('Hỏi riêng nhóm Mobile: bạn kiểm thử UI bằng công cụ gì?',
     'FRIENDS', 9133, 'POLL', 'PENDING_REVIEW',
     '{"question":"Bạn kiểm thử UI bằng công cụ gì?","options":[{"id":1,"text":"Espresso","votesCount":0},{"id":2,"text":"Compose Testing","votesCount":0},{"id":3,"text":"XCUITest","votesCount":0},{"id":4,"text":"Không kiểm thử UI","votesCount":0}],"allowMultipleVotes":false,"endDate":"2026-12-05T23:59:59+07:00"}'::jsonb,
     now() - INTERVAL '1 days', now() - INTERVAL '1 days'),
    ('Khảo sát riêng: nên đặt tên package theo tính năng hay theo tầng kiến trúc?',
     'PRIVATE', 9133, 'POLL', 'APPROVED',
     '{"question":"Nên đặt tên package theo tính năng hay theo tầng?","options":[{"id":1,"text":"Theo tính năng","votesCount":3},{"id":2,"text":"Theo tầng kiến trúc","votesCount":1}],"allowMultipleVotes":false,"endDate":"2026-12-10T23:59:59+07:00"}'::jsonb,
     now() - INTERVAL '7 days', now() - INTERVAL '7 days')
) AS v(content, visibility, author_id, post_type, moderation_status, poll_details, created_at, updated_at)
WHERE NOT EXISTS (
    SELECT 1 FROM socialapp.t_posts existing
     WHERE existing.author_id = v.author_id AND existing.content = v.content
);

-- LINK (bài PUBLIC của 9133 cố ý REJECTED — mục tiêu vi phạm ở mục 11)
INSERT INTO socialapp.t_posts (content, visibility, author_id, post_type, moderation_status, link_details, created_at, updated_at)
SELECT * FROM (VALUES
    ('Đọc được bài này về connection pooling khá hay, để dành đọc lại.',
     'PUBLIC', 9001, 'LINK', 'APPROVED',
     '{"url":"https://github.com/brettwooldridge/HikariCP/wiki/About-Pool-Sizing","title":"About Pool Sizing — HikariCP wiki","description":"Công thức ước lượng kích thước connection pool hợp lý theo số lõi CPU.","thumbnailUrl":null}'::jsonb,
     now() - INTERVAL '24 days', now() - INTERVAL '24 days'),
    ('Chia sẻ riêng nhóm: tài liệu nội bộ về quy ước đặt tên bảng, ai chưa đọc thì xem qua.',
     'FRIENDS', 9001, 'LINK', 'APPROVED',
     '{"url":"https://flywaydb.org/documentation/concepts/migrations","title":"Flyway Migrations — tài liệu chính thức","description":"Tham khảo lại quy ước đặt tên version trước khi thêm migration mới.","thumbnailUrl":null}'::jsonb,
     now() - INTERVAL '37 days', now() - INTERVAL '37 days'),
    ('Lưu riêng: bài so sánh các chiến lược cache invalidation, đọc lại khi cần.',
     'PRIVATE', 9001, 'LINK', 'APPROVED',
     '{"url":"https://redis.io/docs/latest/develop/use/patterns/","title":"Redis usage patterns","description":"Các mẫu dùng Redis phổ biến cho cache và hàng đợi nhẹ.","thumbnailUrl":null}'::jsonb,
     now() - INTERVAL '16 days', now() - INTERVAL '16 days'),
    ('Bài viết này giải thích rất rõ về offline-first, mọi người đọc thử xem.',
     'PUBLIC', 9133, 'LINK', 'REJECTED',
     '{"url":"https://developer.android.com/topic/architecture/data-layer/offline-first","title":"Build an offline-first app — Android Developers","description":"Hướng dẫn chính thức của Google về kiến trúc offline-first.","thumbnailUrl":null}'::jsonb,
     now() - INTERVAL '8 days', now() - INTERVAL '8 days'),
    ('Chia sẻ riêng nhóm Mobile: tài liệu nội bộ về checklist trước khi phát hành bản mới.',
     'FRIENDS', 9133, 'LINK', 'APPROVED',
     '{"url":"https://developer.android.com/distribute/best-practices/launch/launch-checklist","title":"Launch checklist — Android Developers","description":"Danh sách kiểm tra trước khi phát hành, đội mình dùng làm mẫu.","thumbnailUrl":null}'::jsonb,
     now() - INTERVAL '29 days', now() - INTERVAL '29 days'),
    ('Lưu riêng: bài viết về App Thinning cho iOS, để đọc kỹ hơn sau.',
     'PRIVATE', 9133, 'LINK', 'APPROVED',
     '{"url":"https://developer.apple.com/documentation/xcode/reducing-your-app-s-size","title":"Reducing your app''s size — Apple Developer","description":"Tài liệu chính thức của Apple về giảm dung lượng ứng dụng.","thumbnailUrl":null}'::jsonb,
     now() - INTERVAL '10 days', now() - INTERVAL '10 days')
) AS v(content, visibility, author_id, post_type, moderation_status, link_details, created_at, updated_at)
WHERE NOT EXISTS (
    SELECT 1 FROM socialapp.t_posts existing
     WHERE existing.author_id = v.author_id AND existing.content = v.content
);

-- BOOK (bài giới thiệu, KHÔNG kèm t_books — sách đăng bán nằm riêng ở mục 8)
INSERT INTO socialapp.t_posts (content, visibility, author_id, post_type, moderation_status, created_at, updated_at)
SELECT * FROM (VALUES
    ('Vừa đọc xong một cuốn về Vận hành PostgreSQL ở quy mô lớn, kinh nghiệm thực chiến rất đáng đọc.',
     'PUBLIC', 9001, 'BOOK', 'APPROVED', now() - INTERVAL '20 days', now() - INTERVAL '20 days'),
    ('Giới thiệu riêng nhóm: cuốn sách về thiết kế API bền vững, ai đang làm backend nên đọc.',
     'FRIENDS', 9001, 'BOOK', 'APPROVED', now() - INTERVAL '34 days', now() - INTERVAL '34 days'),
    ('Ghi riêng: danh sách sách cần đọc tiếp về kiến trúc phân tán.',
     'PRIVATE', 9001, 'BOOK', 'APPROVED', now() - INTERVAL '6 days', now() - INTERVAL '6 days'),
    ('Vừa đọc xong cuốn Jetpack Compose thực chiến, rất nhiều ví dụ áp dụng được ngay.',
     'PUBLIC', 9133, 'BOOK', 'APPROVED', now() - INTERVAL '26 days', now() - INTERVAL '26 days'),
    ('Giới thiệu riêng nhóm Mobile: cuốn sách về Kotlin Multiplatform, đội mình đang tham khảo.',
     'FRIENDS', 9133, 'BOOK', 'APPROVED', now() - INTERVAL '41 days', now() - INTERVAL '41 days'),
    ('Ghi riêng: sách cần đọc tiếp về kiến trúc offline-first.',
     'PRIVATE', 9133, 'BOOK', 'APPROVED', now() - INTERVAL '2 days', now() - INTERVAL '2 days')
) AS v(content, visibility, author_id, post_type, moderation_status, created_at, updated_at)
WHERE NOT EXISTS (
    SELECT 1 FROM socialapp.t_posts existing
     WHERE existing.author_id = v.author_id AND existing.content = v.content
);

-- REGULAR (bài PUBLIC của cả hai cố ý REJECTED — mục tiêu vi phạm ở mục 11)
INSERT INTO socialapp.t_posts (content, visibility, author_id, post_type, moderation_status, created_at, updated_at)
SELECT * FROM (VALUES
    ('Vừa dọn xong một chuỗi liên kết quảng cáo lặp lại nhiều lần trong nhóm, mọi người cẩn thận với các đường dẫn này nhé.',
     'PUBLIC', 9001, 'REGULAR', 'REJECTED', now() - INTERVAL '3 days', now() - INTERVAL '3 days'),
    ('Riêng tư: ghi lại quyết định kiến trúc của quý này để sau còn nhớ vì sao chọn hướng đó.',
     'FRIENDS', 9001, 'REGULAR', 'APPROVED', now() - INTERVAL '44 days', now() - INTERVAL '44 days'),
    ('Nhắc bản thân: tuần sau nhớ nâng phiên bản Spring Boot cho service thanh toán.',
     'PRIVATE', 9001, 'REGULAR', 'APPROVED', now() - INTERVAL '1 days', now() - INTERVAL '1 days'),
    ('Một đường dẫn quảng cáo bị đăng lặp lại quá nhiều lần trong tuần này, xin lỗi cả nhà vì sự cố.',
     'PUBLIC', 9133, 'REGULAR', 'REJECTED', now() - INTERVAL '4 days', now() - INTERVAL '4 days'),
    ('Riêng tư nhóm: ghi lại lý do đội chọn Jetpack Compose thay vì tiếp tục XML layout.',
     'FRIENDS', 9133, 'REGULAR', 'APPROVED', now() - INTERVAL '52 days', now() - INTERVAL '52 days'),
    ('Nhắc bản thân: kiểm tra lại kích thước gói cài trước khi phát hành bản tới.',
     'PRIVATE', 9133, 'REGULAR', 'APPROVED', now() - INTERVAL '2 days', now() - INTERVAL '2 days')
) AS v(content, visibility, author_id, post_type, moderation_status, created_at, updated_at)
WHERE NOT EXISTS (
    SELECT 1 FROM socialapp.t_posts existing
     WHERE existing.author_id = v.author_id AND existing.content = v.content
);

-- ─────────────────────────────────────────────────────────────────────────────────────────────
-- 2. HASHTAG cho các bài mới — tra theo TÊN, không đoán id, vì id hashtag không nằm trong tài
--    liệu ổn định như dải id_map.md.
-- ─────────────────────────────────────────────────────────────────────────────────────────────

INSERT INTO socialapp.t_post_hashtags (post_id, hashtag_id)
SELECT p.id, h.id
  FROM socialapp.t_posts p
  JOIN (VALUES
      ('Buổi chia sẻ: Vận hành PostgreSQL ở quy mô lớn — 30 phút trình bày, 30 phút hỏi đáp, đăng ký sớm còn chỗ nhé cả nhà.', 'postgresql'),
      ('Đoạn cấu hình connection pool Hikari mình dùng cho service chịu tải cao, chia sẻ cho ai đang tối ưu tương tự.', 'springboot'),
      ('Bạn nào cần một transactional outbox tối giản cho Spring Boot thì đây, mình đang dùng bản này trong dự án thật.', 'springboot'),
      ('Vì sao service của mình chuyển từ REST thuần sang thêm một lớp CQRS cho phần đọc báo cáo. Bài dài, có ví dụ thật.', 'architecture'),
      ('Có ai từng gặp deadlock khi hai transaction cùng cập nhật ngược thứ tự hai bảng chưa? Đã thử sắp lại thứ tự lock nhưng vẫn thỉnh thoảng dính.', 'database'),
      ('Team backend hỏi nhanh: dự án của bạn đang dùng gì để quản lý migration?', 'java'),
      ('Đọc được bài này về connection pooling khá hay, để dành đọc lại.', 'postgresql'),
      ('Một đường dẫn quảng cáo bị đăng lặp lại quá nhiều lần trong tuần này, xin lỗi cả nhà vì sự cố.', 'kotlin'),
      ('Buổi chia sẻ: Kiến trúc offline-first cho ứng dụng di động — đăng ký sớm còn chỗ nhé cả nhà.', 'flutter'),
      ('Đoạn xử lý retry có jitter cho gọi mạng trên Android, tránh nghẽn khi cả app đồng loạt thử lại.', 'android'),
      ('Trải nghiệm chuyển đội mình từ XML layout sang hoàn toàn Jetpack Compose sau sáu tháng — được và chưa được.', 'kotlin'),
      ('Có ai biết cách xử lý tình trạng RecyclerView giật khi danh sách có ảnh tải từ mạng không? Đã thử đặt kích thước cố định nhưng vẫn giật.', 'android'),
      ('Team mobile hỏi nhanh: bạn ưu tiên nền tảng nào trước khi ra mắt tính năng mới?', 'ios'),
      ('Bài viết này giải thích rất rõ về offline-first, mọi người đọc thử xem.', 'flutter'),
      ('Vừa dọn xong một chuỗi liên kết quảng cáo lặp lại nhiều lần trong nhóm, mọi người cẩn thận với các đường dẫn này nhé.', 'security')
  ) AS tag(content, name) ON tag.content = p.content
  JOIN socialapp.t_hashtags h ON h.name = tag.name
 WHERE NOT EXISTS (
     SELECT 1 FROM socialapp.t_post_hashtags existing
      WHERE existing.post_id = p.id AND existing.hashtag_id = h.id
 );

UPDATE socialapp.t_hashtags h
   SET usage_count = COALESCE(c.total, 0)
  FROM (SELECT hashtag_id, COUNT(*) AS total
          FROM socialapp.t_post_hashtags GROUP BY hashtag_id) c
 WHERE c.hashtag_id = h.id;

-- ─────────────────────────────────────────────────────────────────────────────────────────────
-- 3. BẠN BÈ — 20 người bạn mới mỗi tài khoản (ACCEPTED). Cạnh Neo4j tương ứng nằm ở
--    friend-graph.cypher (đã thêm bằng tay ở cuối file đó — xem ghi chú tại đó). Guard bằng
--    NOT EXISTS trên CẢ HAI CHIỀU vì bảng không có ràng buộc UNIQUE nào bắt việc này cho ACCEPTED.
-- ─────────────────────────────────────────────────────────────────────────────────────────────

INSERT INTO socialapp.t_friend_requests (requester_id, addressee_id, status, created_at, updated_at)
SELECT 9001, peer.id, 'ACCEPTED', now() - INTERVAL '60 days', now() - INTERVAL '55 days'
  FROM (VALUES (9005),(9010),(9012),(9023),(9044),(9048),(9065),(9073),(9077),(9080),
               (9082),(9088),(9091),(9108),(9115),(9024),(9028),(9031),(9004),(9007)) AS peer(id)
 WHERE NOT EXISTS (
     SELECT 1 FROM socialapp.t_friend_requests fr
      WHERE (fr.requester_id = 9001 AND fr.addressee_id = peer.id)
         OR (fr.requester_id = peer.id AND fr.addressee_id = 9001)
 );

INSERT INTO socialapp.t_friend_requests (requester_id, addressee_id, status, created_at, updated_at)
SELECT 9133, peer.id, 'ACCEPTED', now() - INTERVAL '60 days', now() - INTERVAL '55 days'
  FROM (VALUES (9006),(9013),(9027),(9034),(9061),(9096),(9122),(9155),(9172),(9186),
               (9213),(9214),(9216),(9221),(9227),(9035),(9038),(9047),(9014),(9020)) AS peer(id)
 WHERE NOT EXISTS (
     SELECT 1 FROM socialapp.t_friend_requests fr
      WHERE (fr.requester_id = 9133 AND fr.addressee_id = peer.id)
         OR (fr.requester_id = peer.id AND fr.addressee_id = 9133)
 );

-- Lời mời ĐANG CHỜ — cả hai chiều, cho mỗi tài khoản. Chỉ số partial uq_friend_requests_pending_pair
-- (V33) chặn hai dòng PENDING trên cùng một cặp không thứ tự nên guard dưới đây soát cả hai chiều.
INSERT INTO socialapp.t_friend_requests (requester_id, addressee_id, status, created_at, updated_at)
SELECT peer.id, 9001, 'PENDING', now() - INTERVAL '4 days', now() - INTERVAL '4 days'
  FROM (VALUES (9117),(9118),(9121),(9125),(9128)) AS peer(id)
 WHERE NOT EXISTS (
     SELECT 1 FROM socialapp.t_friend_requests fr
      WHERE (fr.requester_id = peer.id AND fr.addressee_id = 9001)
         OR (fr.requester_id = 9001 AND fr.addressee_id = peer.id)
 );

INSERT INTO socialapp.t_friend_requests (requester_id, addressee_id, status, created_at, updated_at)
SELECT 9001, peer.id, 'PENDING', now() - INTERVAL '2 days', now() - INTERVAL '2 days'
  FROM (VALUES (9129),(9132),(9140),(9142),(9148)) AS peer(id)
 WHERE NOT EXISTS (
     SELECT 1 FROM socialapp.t_friend_requests fr
      WHERE (fr.requester_id = 9001 AND fr.addressee_id = peer.id)
         OR (fr.requester_id = peer.id AND fr.addressee_id = 9001)
 );

INSERT INTO socialapp.t_friend_requests (requester_id, addressee_id, status, created_at, updated_at)
SELECT peer.id, 9133, 'PENDING', now() - INTERVAL '3 days', now() - INTERVAL '3 days'
  FROM (VALUES (9238),(9246),(9255),(9261),(9264)) AS peer(id)
 WHERE NOT EXISTS (
     SELECT 1 FROM socialapp.t_friend_requests fr
      WHERE (fr.requester_id = peer.id AND fr.addressee_id = 9133)
         OR (fr.requester_id = 9133 AND fr.addressee_id = peer.id)
 );

INSERT INTO socialapp.t_friend_requests (requester_id, addressee_id, status, created_at, updated_at)
SELECT 9133, peer.id, 'PENDING', now() - INTERVAL '1 days', now() - INTERVAL '1 days'
  FROM (VALUES (9266),(9268),(9269),(9270),(9271)) AS peer(id)
 WHERE NOT EXISTS (
     SELECT 1 FROM socialapp.t_friend_requests fr
      WHERE (fr.requester_id = 9133 AND fr.addressee_id = peer.id)
         OR (fr.requester_id = peer.id AND fr.addressee_id = 9133)
 );

-- REJECTED và CANCELLED — không bị chỉ số partial ràng buộc, nhưng vẫn canh để chạy lại an toàn.
INSERT INTO socialapp.t_friend_requests (requester_id, addressee_id, status, created_at, updated_at)
SELECT * FROM (VALUES
    (9274, 9001, 'REJECTED'::text, now() - INTERVAL '90 days', now() - INTERVAL '88 days'),
    (9276, 9001, 'REJECTED'::text, now() - INTERVAL '95 days', now() - INTERVAL '93 days'),
    (9001, 9300, 'CANCELLED'::text, now() - INTERVAL '30 days', now() - INTERVAL '29 days'),
    (9298, 9133, 'REJECTED'::text, now() - INTERVAL '85 days', now() - INTERVAL '83 days'),
    (9301, 9133, 'REJECTED'::text, now() - INTERVAL '77 days', now() - INTERVAL '75 days'),
    (9133, 9303, 'CANCELLED'::text, now() - INTERVAL '19 days', now() - INTERVAL '18 days')
) AS v(requester_id, addressee_id, status, created_at, updated_at)
WHERE NOT EXISTS (
    SELECT 1 FROM socialapp.t_friend_requests fr
     WHERE fr.requester_id = v.requester_id AND fr.addressee_id = v.addressee_id
       AND fr.status = v.status
);

SELECT setval('socialapp.q_friend_requests_id',
              GREATEST((SELECT COALESCE(MAX(id), 0) FROM socialapp.t_friend_requests), 1), true);

-- ─────────────────────────────────────────────────────────────────────────────────────────────
-- 4. DỰ ÁN MỚI — ba dự án mỗi tài khoản, đủ ba ProjectStatus (OPEN/CLOSED/COMPLETED). tags và
--    required_skills rút từ đúng hai bảng DOMAINS/TECH_STACK mà generator dùng (xem V87), để phép
--    giao của MatchmakingService không bị rỗng oan.
-- ─────────────────────────────────────────────────────────────────────────────────────────────

-- Ba câu lệnh RIÊNG BIỆT (không lồng qua một khối WITH duy nhất): CTE ghi dữ liệu chỉ cho các CTE
-- khác trong CÙNG MỘT câu lệnh thấy được qua tên CTE, còn một SELECT trần nhắm lại chính bảng gốc
-- (ví dụ "JOIN socialapp.t_projects" bên dưới) vẫn chạy trên snapshot lúc câu lệnh bắt đầu — tức
-- KHÔNG thấy dòng vừa được một CTE anh em chèn. Tách thành các câu lệnh kế tiếp nhau (cùng một
-- transaction Flyway bọc quanh cả file) thì câu sau luôn thấy dữ liệu câu trước vừa ghi.
INSERT INTO socialapp.t_projects (author_id, title, description, banner_url, status, tags, created_at, updated_at)
SELECT * FROM (VALUES
    (9001, 'Nền tảng ghi log tập trung cho hệ vi dịch vụ',
     'Xây dựng đường ống thu log từ hơn hai mươi dịch vụ, chuẩn hoá định dạng và định tuyến theo mức độ nghiêm trọng.',
     NULL::text, 'OPEN', '["Distributed Systems","API Design"]'::jsonb,
     now() - INTERVAL '20 days', now() - INTERVAL '20 days'),
    (9001, 'Bộ công cụ kiểm tra tải cho API nội bộ',
     'Dự án đã dừng tuyển vì nhóm cốt lõi đã đủ, vẫn đang hoàn thiện phần báo cáo.',
     NULL, 'CLOSED', '["API Design","Database Internals"]'::jsonb,
     now() - INTERVAL '140 days', now() - INTERVAL '90 days'),
    (9001, 'Chuẩn hoá thư viện truy cập dữ liệu dùng chung',
     'Đã hoàn thành: gộp năm cách truy cập PostgreSQL khác nhau giữa các dịch vụ thành một thư viện dùng chung.',
     NULL, 'COMPLETED', '["Database Internals","Distributed Systems"]'::jsonb,
     now() - INTERVAL '300 days', now() - INTERVAL '160 days'),
    (9133, 'Ứng dụng đặt lịch offline-first cho nhân viên hiện trường',
     'Ghi trước đồng bộ sau, phục vụ nhân viên làm việc ở khu vực sóng yếu.',
     NULL, 'OPEN', '["Mobile UX","Offline First"]'::jsonb,
     now() - INTERVAL '16 days', now() - INTERVAL '16 days'),
    (9133, 'Bộ SDK đo hiệu năng cuộn danh sách trên Android',
     'Dự án đã dừng tuyển, đang chờ đóng gói bản phát hành nội bộ cuối cùng.',
     NULL, 'CLOSED', '["Mobile UX","Cross-platform"]'::jsonb,
     now() - INTERVAL '120 days', now() - INTERVAL '70 days'),
    (9133, 'Module thanh toán dùng chung cho hai nền tảng Android/iOS',
     'Đã hoàn thành: một module Kotlin Multiplatform dùng chung logic thanh toán cho cả hai nền tảng.',
     NULL, 'COMPLETED', '["Cross-platform","Mobile UX"]'::jsonb,
     now() - INTERVAL '280 days', now() - INTERVAL '150 days')
) AS v(author_id, title, description, banner_url, status, tags, created_at, updated_at)
WHERE NOT EXISTS (
    SELECT 1 FROM socialapp.t_projects existing
     WHERE existing.author_id = v.author_id AND existing.title = v.title
);

INSERT INTO socialapp.t_project_positions (project_id, title, description, required_skills, quantity, status, created_at, updated_at)
SELECT proj.id, pos.title, pos.description, pos.skills::jsonb, pos.quantity, pos.pos_status,
       now() - INTERVAL '15 days', now() - INTERVAL '15 days'
  FROM socialapp.t_projects proj
  JOIN (VALUES
      ('Nền tảng ghi log tập trung cho hệ vi dịch vụ', 'Kỹ sư Backend', 'Xây dựng phần thu thập và định tuyến log.', '["Java","Spring Boot","Kafka","Distributed Systems"]', 2, 'OPEN'),
      ('Nền tảng ghi log tập trung cho hệ vi dịch vụ', 'Kỹ sư Backend', 'Đã đủ người cho phần lưu trữ và truy vấn log.', '["PostgreSQL","Redis","Database Internals"]', 1, 'FILLED'),
      ('Bộ công cụ kiểm tra tải cho API nội bộ', 'Kỹ sư Backend', 'Viết kịch bản kiểm tải và phân tích kết quả.', '["Java","Docker","API Design"]', 1, 'CLOSED'),
      ('Bộ công cụ kiểm tra tải cho API nội bộ', 'Kỹ sư Backend', 'Dựng báo cáo tổng hợp kết quả kiểm tải.', '["Spring Boot","PostgreSQL","API Design"]', 1, 'CLOSED'),
      ('Chuẩn hoá thư viện truy cập dữ liệu dùng chung', 'Kỹ sư Backend', 'Đã hoàn thành phần thiết kế interface chung.', '["Java","PostgreSQL","Database Internals"]', 1, 'FILLED'),
      ('Chuẩn hoá thư viện truy cập dữ liệu dùng chung', 'Kỹ sư Backend', 'Đã hoàn thành phần viết test tích hợp.', '["Docker","Kafka","Distributed Systems"]', 1, 'FILLED'),
      ('Ứng dụng đặt lịch offline-first cho nhân viên hiện trường', 'Kỹ sư Mobile', 'Xây dựng lớp đồng bộ dữ liệu offline.', '["Kotlin","Flutter","Offline First"]', 2, 'OPEN'),
      ('Ứng dụng đặt lịch offline-first cho nhân viên hiện trường', 'Kỹ sư Mobile', 'Đã đủ người cho phần giao diện chính.', '["Jetpack Compose","Mobile UX"]', 1, 'FILLED'),
      ('Bộ SDK đo hiệu năng cuộn danh sách trên Android', 'Kỹ sư Mobile', 'Đã dừng tuyển, hoàn thiện phần đo đạc.', '["Kotlin","Firebase","Mobile UX"]', 1, 'CLOSED'),
      ('Bộ SDK đo hiệu năng cuộn danh sách trên Android', 'Kỹ sư Mobile', 'Đã dừng tuyển, hoàn thiện tài liệu.', '["Dart","Flutter","Cross-platform"]', 1, 'CLOSED'),
      ('Module thanh toán dùng chung cho hai nền tảng Android/iOS', 'Kỹ sư Mobile', 'Đã hoàn thành phần logic dùng chung.', '["Swift","Kotlin","Cross-platform"]', 1, 'FILLED'),
      ('Module thanh toán dùng chung cho hai nền tảng Android/iOS', 'Kỹ sư Mobile', 'Đã hoàn thành phần tích hợp cổng thanh toán.', '["Dart","Firebase","Mobile UX"]', 1, 'FILLED')
  ) AS pos(project_title, title, description, skills, quantity, pos_status) ON pos.project_title = proj.title
 WHERE proj.author_id IN (9001, 9133)
   -- proj.id > 4050: giới hạn về đúng sáu dự án mới ở trên, không phụ thuộc vào việc tiêu đề dự án
   -- có trùng ngẫu nhiên với một trong 50 dự án gốc hay không (dải gốc 4001-4050 — xem README).
   AND proj.id > 4050
   AND NOT EXISTS (
       SELECT 1 FROM socialapp.t_project_positions existing
        WHERE existing.project_id = proj.id AND existing.title = pos.title
          AND existing.description = pos.description
   );

-- Một ứng viên ACCEPTED cho mỗi vị trí đã FILLED, cộng một PENDING cho một vị trí đang OPEN —
-- rải đủ bốn ApplicationStatus qua sáu dự án mới cùng mục 5 dưới đây (REJECTED/REMOVED).
INSERT INTO socialapp.t_project_applications (project_id, position_id, applicant_id, message, status, created_at, updated_at)
SELECT pos.project_id, pos.id, applicant.id, applicant.message, applicant.app_status,
       now() - INTERVAL '10 days', now() - INTERVAL '8 days'
  FROM socialapp.t_project_positions pos
  JOIN socialapp.t_projects proj ON proj.id = pos.project_id
  JOIN (VALUES
      (9001, 9010, 'Mình có kinh nghiệm với Kafka, rất muốn tham gia phần thu log.', 'PENDING'::text),
      (9001, 9012, 'Từng làm một hệ thống log tương tự ở công ty cũ.', 'ACCEPTED'::text),
      (9133, 9006, 'Mình từng làm offline-first cho app giao hàng, muốn thử phần này.', 'PENDING'::text),
      (9133, 9013, 'Có kinh nghiệm Jetpack Compose, xin tham gia.', 'ACCEPTED'::text)
  ) AS applicant(owner_id, id, message, app_status) ON applicant.owner_id = proj.author_id
 WHERE proj.author_id IN (9001, 9133)
   -- proj.id > 4050: giới hạn về đúng SÁU dự án MỚI ở trên (dải gốc là 4001-4050 — xem README).
   -- Thiếu điều kiện này, "proj.author_id IN (9001, 9133)" cũng khớp một dự án 9001 ĐÃ CÓ SẴN
   -- trong dải gốc (9001 là tác giả nghề nghiệp thật, không có gì đảm bảo họ chưa đứng tên dự án
   -- nào) — xác minh bằng tay trên database dev thật đã thấy chính xác ca này: ba đơn ứng tuyển
   -- giả bị chèn nhầm vào một dự án gốc không liên quan tới V108.
   AND proj.id > 4050
   AND proj.status = 'OPEN'
   AND ((applicant.app_status = 'ACCEPTED' AND pos.status = 'FILLED')
        OR (applicant.app_status = 'PENDING' AND pos.status = 'OPEN'))
   AND NOT EXISTS (
       SELECT 1 FROM socialapp.t_project_applications a
        WHERE a.position_id = pos.id AND a.applicant_id = applicant.id
   );

-- Thêm hai đơn ứng tuyển REJECTED/REMOVED cho hai dự án CLOSED/COMPLETED mới, để đủ bốn trạng
-- thái ApplicationStatus xuất hiện trên chính các dự án mới này.
INSERT INTO socialapp.t_project_applications (project_id, position_id, applicant_id, message, status, created_at, updated_at)
SELECT p.id, pos.id, cand.applicant_id, cand.message, cand.app_status,
       now() - INTERVAL '60 days', now() - INTERVAL '50 days'
  FROM socialapp.t_projects p
  JOIN socialapp.t_project_positions pos ON pos.project_id = p.id
  JOIN (VALUES
      ('Bộ công cụ kiểm tra tải cho API nội bộ', 9010, 'Đăng ký muộn khi dự án đã dừng tuyển.', 'REJECTED'::text),
      ('Module thanh toán dùng chung cho hai nền tảng Android/iOS', 9006, 'Tham gia được một thời gian rồi rút khỏi dự án.', 'REMOVED'::text)
  ) AS cand(project_title, applicant_id, message, app_status) ON cand.project_title = p.title
 WHERE p.author_id IN (9001, 9133)
   AND p.id > 4050
   AND NOT EXISTS (
       SELECT 1 FROM socialapp.t_project_applications a
        WHERE a.position_id = pos.id AND a.applicant_id = cand.applicant_id
   )
   AND pos.id = (SELECT MIN(id) FROM socialapp.t_project_positions WHERE project_id = p.id);

-- ─────────────────────────────────────────────────────────────────────────────────────────────
-- 5. DỰ ÁN ĐÃ THAM GIA — 9001/9133 nộp đơn vào vị trí của NGƯỜI KHÁC (dải dự án gốc 4001-4050),
--    đủ bốn ApplicationStatus mỗi người. Chọn động bằng row_number() thay vì đoán id vị trí, vì
--    id vị trí không nằm trong tài liệu ổn định như id-map.md.
-- ─────────────────────────────────────────────────────────────────────────────────────────────

WITH candidate_9001 AS (
    SELECT pos.id AS position_id, pos.project_id,
           row_number() OVER (ORDER BY pos.id) AS rn
      FROM socialapp.t_project_positions pos
      JOIN socialapp.t_projects proj ON proj.id = pos.project_id
     WHERE proj.id BETWEEN 4001 AND 4050
       AND proj.author_id <> 9001
       AND NOT EXISTS (
           SELECT 1 FROM socialapp.t_project_applications a
            WHERE a.position_id = pos.id AND a.applicant_id = 9001
       )
     ORDER BY pos.id
     -- Chặn ở ĐÚNG bốn đơn TỔNG CỘNG, không phải "bốn đơn MỚI mỗi lần chạy": không có vế trừ đi số
     -- đã có, mỗi lần chạy lại sẽ chọn bốn vị trí TIẾP THEO (NOT EXISTS chỉ chặn nộp trùng vào
     -- CÙNG một vị trí, không chặn việc có thêm đơn mới) — xác minh bằng tay đã thấy 9001 có 16 đơn
     -- sau vài lần chạy thay vì 4.
     LIMIT GREATEST(0, 4 - (
         SELECT COUNT(*) FROM socialapp.t_project_applications a
           JOIN socialapp.t_project_positions pos2 ON pos2.id = a.position_id
           JOIN socialapp.t_projects proj2 ON proj2.id = pos2.project_id
          WHERE a.applicant_id = 9001 AND proj2.id BETWEEN 4001 AND 4050
     ))
),
status_map AS (SELECT * FROM (VALUES (1,'PENDING'),(2,'ACCEPTED'),(3,'REJECTED'),(4,'REMOVED')) AS v(rn, status))
INSERT INTO socialapp.t_project_applications (project_id, position_id, applicant_id, message, status, created_at, updated_at)
SELECT c.project_id, c.position_id, 9001,
       'Kinh nghiệm backend của mình khớp với yêu cầu vị trí này, rất mong được xem xét.',
       sm.status, now() - INTERVAL '35 days', now() - INTERVAL '25 days'
  FROM candidate_9001 c JOIN status_map sm USING (rn);

WITH candidate_9133 AS (
    -- row_number() PHẢI cùng hướng sắp xếp với ORDER BY ... DESC LIMIT 4 bên dưới. Khác hướng thì
    -- LIMIT 4 chọn bốn dòng có pos.id LỚN NHẤT nhưng rn của chúng lại rơi vào cuối dãy đánh số
    -- tăng dần (ví dụ 132-135 thay vì 1-4) — JOIN status_map USING (rn) sau đó khớp ĐÚNG 0 dòng,
    -- và không có gì báo lỗi (INSERT 0 dòng không phải lỗi cú pháp). Xác minh bằng tay: chạy thử
    -- trên database dev thật cho đúng lỗi này trước khi sửa.
    SELECT pos.id AS position_id, pos.project_id,
           row_number() OVER (ORDER BY pos.id DESC) AS rn
      FROM socialapp.t_project_positions pos
      JOIN socialapp.t_projects proj ON proj.id = pos.project_id
     WHERE proj.id BETWEEN 4001 AND 4050
       AND proj.author_id <> 9133
       AND NOT EXISTS (
           SELECT 1 FROM socialapp.t_project_applications a
            WHERE a.position_id = pos.id AND a.applicant_id = 9133
       )
     ORDER BY pos.id DESC
     -- Cùng lý do chặn tổng ở khối 9001 phía trên: không trừ đi số đã có thì mỗi lần chạy lại chọn
     -- thêm bốn vị trí mới thay vì dừng ở bốn.
     LIMIT GREATEST(0, 4 - (
         SELECT COUNT(*) FROM socialapp.t_project_applications a
           JOIN socialapp.t_project_positions pos2 ON pos2.id = a.position_id
           JOIN socialapp.t_projects proj2 ON proj2.id = pos2.project_id
          WHERE a.applicant_id = 9133 AND proj2.id BETWEEN 4001 AND 4050
     ))
),
status_map AS (SELECT * FROM (VALUES (1,'PENDING'),(2,'ACCEPTED'),(3,'REJECTED'),(4,'REMOVED')) AS v(rn, status))
INSERT INTO socialapp.t_project_applications (project_id, position_id, applicant_id, message, status, created_at, updated_at)
SELECT c.project_id, c.position_id, 9133,
       'Stack Mobile của mình khớp với yêu cầu vị trí này, rất mong được xem xét.',
       sm.status, now() - INTERVAL '38 days', now() - INTERVAL '27 days'
  FROM candidate_9133 c JOIN status_map sm USING (rn);

SELECT setval(pg_get_serial_sequence('socialapp.t_projects', 'id'),
              GREATEST((SELECT COALESCE(MAX(id), 0) FROM socialapp.t_projects), 1), true);
SELECT setval(pg_get_serial_sequence('socialapp.t_project_positions', 'id'),
              GREATEST((SELECT COALESCE(MAX(id), 0) FROM socialapp.t_project_positions), 1), true);
SELECT setval(pg_get_serial_sequence('socialapp.t_project_applications', 'id'),
              GREATEST((SELECT COALESCE(MAX(id), 0) FROM socialapp.t_project_applications), 1), true);

-- ─────────────────────────────────────────────────────────────────────────────────────────────
-- 6. SÁCH — ba đầu sách đăng bán mỗi tài khoản, cộng bốn giao dịch mua mỗi tài khoản, cộng đánh
--    giá cho cả hai chiều (người khác đánh giá sách mới đăng; 9001/9133 đánh giá sách đã mua).
--
-- SÁU QUYỂN NÀY LÀ SÁCH THẬT (cùng quy ước "SÁCH LÀ SÁCH THẬT, BÌA LÀ BÌA THẬT" của V85):
-- author_id là NGƯỜI ĐĂNG lên gian sách, không phải tác giả — same as toàn bộ danh mục V85. ISBN
-- tra qua Open Library (dùng đúng tham số ?default=false, xem crawl kèm request này), bìa tải từ
-- covers.openlibrary.org. Bản đầu của mục này BỊA tên sách tiếng Việt rồi mượn key/bìa của sách
-- 3001/3002 — sai và trùng lặp (ba quyển khác nhau cùng hiện bìa Clean Code / The Go Programming
-- Language). Bản này thay bằng sáu quyển thật cùng chủ đề, mỗi quyển một bìa thật của chính nó.
-- Nội dung file (PDF/EPUB) vẫn là tài liệu mẫu một trang — như mọi quyển KHÔNG có mặt trong
-- book-previews.json (chỉ 80 quyển gốc của V85 được crawl sẵn) — không phải trang trắng, không
-- phải bản sao có bản quyền.
-- ─────────────────────────────────────────────────────────────────────────────────────────────

INSERT INTO socialapp.t_posts (content, visibility, author_id, post_type, moderation_status, created_at, updated_at)
SELECT * FROM (VALUES
    ('Mới đăng sách: Get Your Hands Dirty on Clean Architecture — về kiến trúc Hexagonal cho hệ thống Spring Boot.', 'PUBLIC', 9001, 'BOOK', 'APPROVED', now() - INTERVAL '18 days', now() - INTERVAL '18 days'),
    ('Mới đăng sách: PostgreSQL Administration Cookbook (bản miễn phí) — vận hành PostgreSQL ở quy mô lớn.', 'PUBLIC', 9001, 'BOOK', 'APPROVED', now() - INTERVAL '23 days', now() - INTERVAL '23 days'),
    ('Mới đăng sách: RESTful Web APIs — thiết kế API bền vững cho hệ thống phân tán.', 'PUBLIC', 9001, 'BOOK', 'APPROVED', now() - INTERVAL '27 days', now() - INTERVAL '27 days'),
    ('Mới đăng sách: Android UI Development with Jetpack Compose — Jetpack Compose thực chiến.', 'PUBLIC', 9133, 'BOOK', 'APPROVED', now() - INTERVAL '19 days', now() - INTERVAL '19 days'),
    ('Mới đăng sách: Building Progressive Web Apps (bản miễn phí) — nhiều kỹ thuật offline-first áp dụng được cho cả mobile.', 'PUBLIC', 9133, 'BOOK', 'APPROVED', now() - INTERVAL '24 days', now() - INTERVAL '24 days'),
    ('Mới đăng sách: Simplifying Application Development with Kotlin Multiplatform Mobile.', 'PUBLIC', 9133, 'BOOK', 'APPROVED', now() - INTERVAL '31 days', now() - INTERVAL '31 days')
) AS v(content, visibility, author_id, post_type, moderation_status, created_at, updated_at)
WHERE NOT EXISTS (
    SELECT 1 FROM socialapp.t_posts existing
     WHERE existing.author_id = v.author_id AND existing.content = v.content
);

INSERT INTO socialapp.t_books
    (author_id, post_id, title, description, file_key, cover_image_key, preview_file_key,
     file_format, file_size_bytes, total_pages, preview_pages, price, currency, is_free,
     download_count, avg_rating, review_count, category, created_at, updated_at)
SELECT b.author_id, p.id, b.title, b.description,
       b.file_key, b.cover_key, b.preview_key, b.format,
       b.size_bytes, b.total_pages, 1, b.price, 'VND', b.is_free, 0, 0.0, 0, b.category,
       now() - INTERVAL '18 days', now() - INTERVAL '18 days'
  FROM (VALUES
      (9001, 'Mới đăng sách: Get Your Hands Dirty on Clean Architecture — về kiến trúc Hexagonal cho hệ thống Spring Boot.',
       'Get Your Hands Dirty on Clean Architecture',
       'Get Your Hands Dirty on Clean Architecture — Tom Hombergs. Tách phần lõi nghiệp vụ khỏi framework trong một ứng dụng Spring Boot mẫu, để đổi database hay đổi giao thức không đụng tới logic chính.',
       'books/9001/9781839211966.pdf', 'covers/9001/9781839211966.jpg',
       'previews/9001/9781839211966-preview.pdf', 'PDF'::text,
       3_150_000::bigint, 268, 149000::bigint, FALSE, 'BACKEND'::text),
      (9001, 'Mới đăng sách: PostgreSQL Administration Cookbook (bản miễn phí) — vận hành PostgreSQL ở quy mô lớn.',
       'PostgreSQL Administration Cookbook, 9.5/9.6 Edition',
       'PostgreSQL Administration Cookbook, 9.5/9.6 Edition — Simon Riggs, Gianni Ciolli, Gabriele Bartolini. Kinh nghiệm đánh index, giao dịch và sao lưu cho hệ thống chịu tải cao.',
       'books/9001/9781785883187.epub', 'covers/9001/9781785883187.jpg',
       'previews/9001/9781785883187-preview.epub', 'EPUB'::text,
       2_480_000::bigint, 210, 0::bigint, TRUE, 'BACKEND'::text),
      (9001, 'Mới đăng sách: RESTful Web APIs — thiết kế API bền vững cho hệ thống phân tán.',
       'RESTful Web APIs: Services for a Changing World',
       'RESTful Web APIs: Services for a Changing World — Leonard Richardson, Michael Amundsen, Sam Ruby. Đặt tên tài nguyên, xử lý lỗi nhất quán và phân trang kiểu cursor.',
       'books/9001/9781449358068.pdf', 'covers/9001/9781449358068.jpg',
       'previews/9001/9781449358068-preview.pdf', 'PDF'::text,
       4_020_000::bigint, 302, 199000::bigint, FALSE, 'BACKEND'::text),
      (9133, 'Mới đăng sách: Android UI Development with Jetpack Compose — Jetpack Compose thực chiến.',
       'Android UI Development with Jetpack Compose',
       'Android UI Development with Jetpack Compose — Thomas Künneth. Từ state hoisting tới recomposition có chọn lọc, kèm ví dụ thực tế.',
       'books/9133/9781801812160.epub', 'covers/9133/9781801812160.jpg',
       'previews/9133/9781801812160-preview.epub', 'EPUB'::text,
       3_640_000::bigint, 256, 179000::bigint, FALSE, 'MOBILE'::text),
      (9133, 'Mới đăng sách: Building Progressive Web Apps (bản miễn phí) — nhiều kỹ thuật offline-first áp dụng được cho cả mobile.',
       'Building Progressive Web Apps',
       'Building Progressive Web Apps: Bringing the Power of Native to the Browser — Tal Ater. Ghi trước, đồng bộ sau, và cách giải quyết xung đột dữ liệu khi mất mạng.',
       'books/9133/9781491961650.pdf', 'covers/9133/9781491961650.jpg',
       'previews/9133/9781491961650-preview.pdf', 'PDF'::text,
       2_910_000::bigint, 188, 0::bigint, TRUE, 'MOBILE'::text),
      (9133, 'Mới đăng sách: Simplifying Application Development with Kotlin Multiplatform Mobile.',
       'Simplifying Application Development with Kotlin Multiplatform Mobile',
       'Simplifying Application Development with Kotlin Multiplatform Mobile — Robert Nagy. Chia sẻ logic giữa Android và iOS mà không đánh đổi trải nghiệm gốc.',
       'books/9133/9781801812580.epub', 'covers/9133/9781801812580.jpg',
       'previews/9133/9781801812580-preview.epub', 'EPUB'::text,
       3_320_000::bigint, 231, 159000::bigint, FALSE, 'MOBILE'::text)
  ) AS b(author_id, post_content, title, description, file_key, cover_key, preview_key, format,
         size_bytes, total_pages, price, is_free, category)
  JOIN socialapp.t_posts p ON p.content = b.post_content
 WHERE NOT EXISTS (SELECT 1 FROM socialapp.t_books existing WHERE existing.post_id = p.id);

WITH candidate_paid_books AS (
    SELECT id, price, author_id, row_number() OVER (ORDER BY id) AS rn
      FROM socialapp.t_books
     WHERE is_free = FALSE AND author_id NOT IN (9001, 9133)
),
status_map_9001 AS (SELECT * FROM (VALUES (1,'COMPLETED'),(2,'COMPLETED'),(3,'PENDING'),(4,'FAILED')) AS v(rn, status))
INSERT INTO socialapp.t_book_purchases
    (book_id, buyer_id, amount, currency, payment_status, transaction_ref,
     gateway_transaction_no, payment_method, payment_link_id, paid_at, created_at)
SELECT cb.id, 9001, cb.price, 'VND', sm.status, 'SEED-EXTRA-' || cb.id || '-9001',
       CASE WHEN sm.status IN ('COMPLETED', 'REFUNDED') THEN 'MOMOEXTRA' || cb.id || '9001' ELSE NULL END,
       CASE WHEN sm.status IN ('COMPLETED', 'REFUNDED') THEN 'MOMO' ELSE NULL END,
       NULL,
       CASE WHEN sm.status IN ('COMPLETED', 'REFUNDED') THEN now() - INTERVAL '20 days' ELSE NULL END,
       now() - INTERVAL '25 days'
  FROM candidate_paid_books cb JOIN status_map_9001 sm USING (rn)
 WHERE cb.rn <= 4
   AND NOT EXISTS (SELECT 1 FROM socialapp.t_book_purchases bp WHERE bp.book_id = cb.id AND bp.buyer_id = 9001);

WITH candidate_paid_books AS (
    SELECT id, price, author_id, row_number() OVER (ORDER BY id DESC) AS rn
      FROM socialapp.t_books
     WHERE is_free = FALSE AND author_id NOT IN (9001, 9133)
),
status_map_9133 AS (SELECT * FROM (VALUES (1,'COMPLETED'),(2,'REFUNDED'),(3,'COMPLETED'),(4,'FAILED')) AS v(rn, status))
INSERT INTO socialapp.t_book_purchases
    (book_id, buyer_id, amount, currency, payment_status, transaction_ref,
     gateway_transaction_no, payment_method, payment_link_id, paid_at, created_at)
SELECT cb.id, 9133, cb.price, 'VND', sm.status, 'SEED-EXTRA-' || cb.id || '-9133',
       CASE WHEN sm.status IN ('COMPLETED', 'REFUNDED') THEN 'MOMOEXTRA' || cb.id || '9133' ELSE NULL END,
       CASE WHEN sm.status IN ('COMPLETED', 'REFUNDED') THEN 'MOMO' ELSE NULL END,
       NULL,
       CASE WHEN sm.status IN ('COMPLETED', 'REFUNDED') THEN now() - INTERVAL '20 days' ELSE NULL END,
       now() - INTERVAL '25 days'
  FROM candidate_paid_books cb JOIN status_map_9133 sm USING (rn)
 WHERE cb.rn <= 4
   AND NOT EXISTS (SELECT 1 FROM socialapp.t_book_purchases bp WHERE bp.book_id = cb.id AND bp.buyer_id = 9133);

-- Đánh giá, chiều 1: bạn bè để lại rating cho ba quyển sách mới đăng của mỗi tài khoản. Tra theo
-- TIÊU ĐỀ (như t_books ở trên), không đoán id.
INSERT INTO socialapp.t_book_reviews (book_id, user_id, rating, feedback, created_at, updated_at)
SELECT b.id, r.reviewer, r.rating, r.feedback,
       now() - INTERVAL '1 day' * r.age, now() - INTERVAL '1 day' * r.age
  FROM socialapp.t_books b
  JOIN (VALUES
      ('Get Your Hands Dirty on Clean Architecture', 9005, 5,
       'Tách lõi nghiệp vụ rõ ràng, áp dụng được ngay vào dự án đang làm.', 10),
      ('Get Your Hands Dirty on Clean Architecture', 9010, 4,
       'Ví dụ sát thực tế, phần đầu hơi dài dòng.', 14),
      ('Get Your Hands Dirty on Clean Architecture', 9023, 5,
       'Đọc lại lần hai vẫn thấy thêm được thứ mới.', 8),
      ('PostgreSQL Administration Cookbook, 9.5/9.6 Edition', 9044, 5,
       'Miễn phí mà chất lượng hơn nhiều sách trả phí khác.', 20),
      ('PostgreSQL Administration Cookbook, 9.5/9.6 Edition', 9048, 4,
       'Phần đánh index rất thực tế, đáng đọc.', 25),
      ('PostgreSQL Administration Cookbook, 9.5/9.6 Edition', 9065, 3,
       'Ổn nhưng chưa đủ sâu về phần sao lưu.', 30),
      ('RESTful Web APIs: Services for a Changing World', 9073, 5,
       'Đúng thứ mình cần cho dự án đang thiết kế lại API.', 5),
      ('RESTful Web APIs: Services for a Changing World', 9077, 5,
       'Phân trang kiểu cursor giải thích rất dễ hiểu.', 12),
      ('RESTful Web APIs: Services for a Changing World', 9080, 4,
       'Hợp với người đã có nền tảng, người mới sẽ hơi nặng.', 18),
      ('Android UI Development with Jetpack Compose', 9006, 5,
       'Ví dụ recomposition rất trực quan, làm theo được ngay.', 9),
      ('Android UI Development with Jetpack Compose', 9013, 4,
       'Nội dung chắc nhưng phần state hoisting hơi nhanh.', 16),
      ('Android UI Development with Jetpack Compose', 9027, 5,
       'Đáng tiền, áp dụng được vào app đang làm ở công ty.', 6),
      ('Building Progressive Web Apps', 9034, 5,
       'Miễn phí mà giải quyết đúng vấn đề mình đang gặp.', 22),
      ('Building Progressive Web Apps', 9061, 4,
       'Phần xử lý xung đột dữ liệu rất hữu ích.', 27),
      ('Building Progressive Web Apps', 9096, 3,
       'Ý tưởng hay nhưng ví dụ còn hơi đơn giản.', 33),
      ('Simplifying Application Development with Kotlin Multiplatform Mobile', 9122, 5,
       'Chia sẻ logic đa nền tảng giải thích rất rõ ràng.', 7),
      ('Simplifying Application Development with Kotlin Multiplatform Mobile', 9155, 5,
       'Đọc xong áp dụng được ngay vào việc đang làm.', 13),
      ('Simplifying Application Development with Kotlin Multiplatform Mobile', 9172, 4,
       'Hợp với người đã có nền tảng Kotlin sẵn.', 19)
  ) AS r(title, reviewer, rating, feedback, age) ON r.title = b.title
 WHERE NOT EXISTS (
     SELECT 1 FROM socialapp.t_book_reviews br
      WHERE br.book_id = b.id AND br.user_id = r.reviewer
 );

-- Đánh giá, chiều 2: 9001/9133 tự để lại rating cho các quyển họ đã MUA (giao dịch COMPLETED ở
-- trên). Rating và câu chữ chọn tất định theo (book_id, buyer_id) để chạy lại vẫn ra cùng dữ liệu.
INSERT INTO socialapp.t_book_reviews (book_id, user_id, rating, feedback, created_at, updated_at)
SELECT bp.book_id, bp.buyer_id,
       (ARRAY[3, 4, 4, 5, 5])[1 + (bp.book_id + bp.buyer_id) % 5],
       (ARRAY['Đọc xong áp dụng được ngay vào việc đang làm.',
              'Ví dụ sát thực tế, không phải kiểu bài tập trong lớp.',
              'Đáng tiền, nhất là mấy chương cuối.',
              'Hợp với người đã có nền tảng, người mới sẽ hơi nặng.'
             ])[1 + (bp.book_id + bp.buyer_id) % 4],
       now() - INTERVAL '1 day' * (5 + bp.book_id % 20), now() - INTERVAL '1 day' * (5 + bp.book_id % 20)
  FROM socialapp.t_book_purchases bp
 WHERE bp.buyer_id IN (9001, 9133)
   AND bp.payment_status = 'COMPLETED'
   AND bp.transaction_ref LIKE 'SEED-EXTRA-%'
   AND NOT EXISTS (
       SELECT 1 FROM socialapp.t_book_reviews br
        WHERE br.book_id = bp.book_id AND br.user_id = bp.buyer_id
   );

UPDATE socialapp.t_books b
   SET download_count = COALESCE(p.total, 0)
  FROM (SELECT book_id, COUNT(*) AS total
          FROM socialapp.t_book_purchases
         WHERE payment_status = 'COMPLETED' GROUP BY book_id) p
 WHERE p.book_id = b.id;

-- avg_rating/review_count được TÍNH LẠI (như V85), giới hạn ở những quyển vừa đụng tới ở trên: sáu
-- quyển mới đăng, cộng những quyển thật mà 9001/9133 vừa để lại đánh giá với tư cách người mua.
WITH touched_books AS (
    SELECT id FROM socialapp.t_books WHERE author_id IN (9001, 9133)
    UNION
    SELECT book_id FROM socialapp.t_book_reviews WHERE user_id IN (9001, 9133)
)
UPDATE socialapp.t_books b
   SET avg_rating = COALESCE(r.avg_rating, 0.0),
       review_count = COALESCE(r.total, 0)
  FROM (SELECT book_id, ROUND(AVG(rating)::numeric, 1) AS avg_rating, COUNT(*) AS total
          FROM socialapp.t_book_reviews GROUP BY book_id) r
 WHERE r.book_id = b.id AND b.id IN (SELECT id FROM touched_books);

SELECT setval('socialapp.q_books_id',
              GREATEST((SELECT COALESCE(MAX(id), 0) FROM socialapp.t_books), 1) + 1, false);
SELECT setval('socialapp.q_book_purchases_id',
              GREATEST((SELECT COALESCE(MAX(id), 0) FROM socialapp.t_book_purchases), 1), true);
SELECT setval('socialapp.q_book_reviews_id',
              GREATEST((SELECT COALESCE(MAX(id), 0) FROM socialapp.t_book_reviews), 1), true);
SELECT setval('socialapp.q_posts_id',
              GREATEST((SELECT COALESCE(MAX(id), 0) FROM socialapp.t_posts), 1) + 1, false);

-- ─────────────────────────────────────────────────────────────────────────────────────────────
-- 7. GIẢI THÍCH AI (Kho lưu trữ) — sáu bản mỗi tài khoản, đọc những bài viết CÓ THẬT nằm trong
--    dải id đã tài liệu hoá (REGULAR 100841-102597, CODE_SNIPPET 100081-100240, ARTICLE
--    100241-100360 — xem README). UNIQUE(post_id, user_id, version) nên guard bằng NOT EXISTS.
-- ─────────────────────────────────────────────────────────────────────────────────────────────

INSERT INTO socialapp.t_explanations
    (post_id, user_id, original_content, explanation_content, concepts, prerequisites,
     complexity_score, feedback_note, version, external_links, category, created_at, updated_at)
SELECT p.id, e.user_id, p.content, e.explanation, e.concepts::jsonb, e.prereq::jsonb,
       e.complexity, NULL, 1, '[]'::jsonb, e.category,
       now() - INTERVAL '14 days', now() - INTERVAL '14 days'
  FROM (VALUES
      (100850, 9001, 'Đoạn này giải thích vì sao dependency injection giúp thay thế một thành phần mà không phải sửa nơi gọi nó — mấu chốt là lập trình theo interface, không theo lớp cụ thể.',
       '["Dependency Injection","Spring Boot"]', '["Java cơ bản"]', 3, 'BACKEND'::text),
      (100860, 9001, 'Nội dung nói về cách một index tổ hợp chỉ thu hẹp vùng quét tới cột đầu tiên có điều kiện khoảng — phần còn lại của index chỉ lọc thêm chứ không thu hẹp vùng quét.',
       '["Đánh index","PostgreSQL"]', '["SQL cơ bản"]', 4, 'BACKEND'::text),
      (100870, 9001, 'Đoạn này bàn về transactional outbox — ghi sự kiện cùng transaction với dữ liệu nghiệp vụ để tránh mất sự kiện khi service crash giữa chừng.',
       '["Transactional Outbox","Kafka"]', '["Giao dịch cơ sở dữ liệu"]', 4, 'BACKEND'::text),
      (100090, 9001, 'Nội dung giải thích vì sao câu lệnh tham số hoá ngăn được chèn mã: cơ sở dữ liệu nhận SQL và giá trị người dùng qua hai kênh riêng biệt.',
       '["Chèn mã","Câu lệnh tham số hoá"]', '["SQL cơ bản"]', 2, 'BACKEND'::text),
      (100095, 9001, 'Đoạn này nói về cách giới hạn kích thước connection pool theo số lõi CPU thay vì đặt một con số tuỳ ý.',
       '["Connection Pool","Hiệu năng"]', '["Cơ sở dữ liệu quan hệ"]', 3, 'BACKEND'::text),
      (100245, 9001, 'Bài viết bàn về khi nào nên tách CQRS cho phần đọc — chỉ đáng làm khi phần đọc và phần ghi có nhu cầu mở rộng khác hẳn nhau.',
       '["CQRS","Kiến trúc phân tầng"]', '["Cơ sở dữ liệu quan hệ"]', 4, 'BACKEND'::text),
      (100880, 9133, 'Đoạn này giải thích state hoisting trong Jetpack Compose — nâng trạng thái lên component cha để giữ đúng một nguồn sự thật.',
       '["Giao diện khai báo","Jetpack Compose"]', '["Kotlin cơ bản"]', 3, 'MOBILE'::text),
      (100890, 9133, 'Nội dung nói về offline-first — ghi trước vào bộ nhớ cục bộ, đồng bộ sau khi có mạng trở lại.',
       '["Offline-first","Lưu trữ cục bộ"]', '["Vòng đời ứng dụng"]', 3, 'MOBILE'::text),
      (100900, 9133, 'Đoạn này bàn về retry có jitter — thêm độ trễ ngẫu nhiên để tránh mọi client cùng gọi lại một lúc.',
       '["Gọi mạng","Retry"]', '["Lập trình bất đồng bộ"]', 3, 'MOBILE'::text),
      (100100, 9133, 'Nội dung giải thích vì sao App Thinning trên iOS chỉ gửi đúng phần tài nguyên khớp với thiết bị đang cài.',
       '["Kích thước gói cài","iOS"]', '["Vòng đời ứng dụng"]', 2, 'MOBILE'::text),
      (100105, 9133, 'Đoạn này nói về phát hành theo tỉ lệ phần trăm — đưa bản cập nhật cho một phần nhỏ người dùng trước khi mở rộng.',
       '["Phát hành","Cờ tính năng"]', '["Vòng đời ứng dụng"]', 2, 'MOBILE'::text),
      (100250, 9133, 'Bài viết bàn về cách đo và cải thiện thời gian khởi động app bằng cách trì hoãn khởi tạo SDK không cần thiết.',
       '["Hiệu năng","Khởi động ứng dụng"]', '["Vòng đời ứng dụng"]', 3, 'MOBILE'::text)
  ) AS e(post_id, user_id, explanation, concepts, prereq, complexity, category)
  JOIN socialapp.t_posts p ON p.id = e.post_id
 WHERE NOT EXISTS (
     SELECT 1 FROM socialapp.t_explanations ex
      WHERE ex.post_id = e.post_id AND ex.user_id = e.user_id AND ex.version = 1
 );

SELECT setval('socialapp.q_explanations_id',
              GREATEST((SELECT COALESCE(MAX(id), 0) FROM socialapp.t_explanations), 1) + 1, false);

-- ─────────────────────────────────────────────────────────────────────────────────────────────
-- 8. VI PHẠM VÀ KHIẾU NẠI — ba vi phạm mỗi tài khoản (hai gắn bài REJECTED ở mục 1, một không
--    gắn bài nào), và khiếu nại cho cả sáu, rải đủ ba AppealStatus (PENDING/APPROVED/REJECTED).
--    violation_id tra NGƯỢC qua RETURNING vì t_user_violations dùng BIGSERIAL.
-- ─────────────────────────────────────────────────────────────────────────────────────────────

WITH violations_with_post AS (
    INSERT INTO socialapp.t_user_violations (user_id, post_id, post_excerpt, violation_type, severity, description, created_at)
    SELECT v.user_id, p.id, LEFT(p.content, 160), v.violation_type, v.severity, v.description, now() - INTERVAL '3 days'
      FROM (VALUES
          (9001, 'Vừa dọn xong một chuỗi liên kết quảng cáo lặp lại nhiều lần trong nhóm, mọi người cẩn thận với các đường dẫn này nhé.',
           'KEYWORD_BLACKLIST'::text, 'MEDIUM'::text, 'Nội dung chứa từ khoá nằm trong danh sách chặn.'),
          (9001, 'Đoạn cấu hình connection pool Hikari mình dùng cho service chịu tải cao, chia sẻ cho ai đang tối ưu tương tự.',
           'SPAM'::text, 'LOW'::text, 'Đăng lặp một liên kết quảng cáo trong nhiều bài liên tiếp.'),
          (9133, 'Một đường dẫn quảng cáo bị đăng lặp lại quá nhiều lần trong tuần này, xin lỗi cả nhà vì sự cố.',
           'INSULT'::text, 'MEDIUM'::text, 'Dùng lời lẽ nặng nề với người bình luận khác trong một cuộc tranh luận kỹ thuật.'),
          (9133, 'Bài viết này giải thích rất rõ về offline-first, mọi người đọc thử xem.',
           'HATE_SPEECH'::text, 'HIGH'::text, 'Nội dung công kích một nhóm người.')
      ) AS v(user_id, post_content, violation_type, severity, description)
      JOIN socialapp.t_posts p ON p.content = v.post_content
     WHERE NOT EXISTS (
         SELECT 1 FROM socialapp.t_user_violations existing
          WHERE existing.user_id = v.user_id AND existing.post_id = p.id
     )
    RETURNING id, user_id, description
),
violations_no_post AS (
    INSERT INTO socialapp.t_user_violations (user_id, post_id, post_excerpt, violation_type, severity, description, created_at)
    SELECT v2.user_id, NULL, NULL, v2.violation_type, v2.severity, v2.description, v2.created_at
      FROM (VALUES
          (9001, 'DUPLICATE_CONTENT'::text, 'LOW'::text,
           'Đăng lại gần như nguyên văn một bài đã đăng trước đó.', now() - INTERVAL '2 days'),
          (9133, 'SPAM'::text, 'LOW'::text,
           'Đăng lặp một liên kết quảng cáo trong nhiều bài liên tiếp.', now() - INTERVAL '1 days')
      ) AS v2(user_id, violation_type, severity, description, created_at)
     WHERE NOT EXISTS (
         SELECT 1 FROM socialapp.t_user_violations existing
          WHERE existing.user_id = v2.user_id AND existing.post_id IS NULL
            AND existing.violation_type = v2.violation_type AND existing.description = v2.description
     )
    RETURNING id, user_id, description
),
new_violations AS (
    SELECT * FROM violations_with_post
    UNION ALL
    SELECT * FROM violations_no_post
)
INSERT INTO socialapp.t_moderation_appeals
    (user_id, violation_id, reason, status, reviewer_id, reviewer_note, reviewed_at, created_at, updated_at)
SELECT nv.user_id, nv.id, r.reason, r.status,
       CASE WHEN r.status = 'PENDING' THEN NULL ELSE 9500 END,
       CASE WHEN r.status = 'PENDING' THEN NULL
            WHEN r.status = 'APPROVED' THEN 'Đã xem lại ngữ cảnh, gỡ vi phạm.'
            ELSE 'Giữ nguyên quyết định, nội dung vẫn vi phạm quy tắc cộng đồng.' END,
       CASE WHEN r.status = 'PENDING' THEN NULL ELSE now() - INTERVAL '1 days' END,
       now() - INTERVAL '2 days', now() - INTERVAL '1 days'
  FROM (SELECT id, user_id, row_number() OVER (ORDER BY id) AS rn FROM new_violations) nv
  JOIN (VALUES
      (0, 'Mình nghĩ đây là hiểu nhầm: liên kết đó là tài liệu của chính dự án mình, không phải quảng cáo.', 'PENDING'::text),
      (1, 'Mình đã sửa nội dung ngay sau khi nhận cảnh báo, mong được xem xét lại.', 'APPROVED'::text),
      (2, 'Câu đó mình trích lại lời người khác để phản biện, chứ không phải mình nói.', 'REJECTED'::text)
  ) AS r(idx, reason, status) ON r.idx = (nv.rn - 1) % 3
 WHERE NOT EXISTS (
     SELECT 1 FROM socialapp.t_moderation_appeals existing WHERE existing.violation_id = nv.id
 );

-- ─────────────────────────────────────────────────────────────────────────────────────────────
-- 9. Kiểm tại chỗ — nếu username của hai tài khoản mục tiêu đổi (generator chạy lại với tham số
--    khác), file này phải NỔ lúc migrate thay vì âm thầm gắn nhầm dữ liệu vào một tài khoản khác.
-- ─────────────────────────────────────────────────────────────────────────────────────────────

DO $$
DECLARE u9001 TEXT;
DECLARE u9133 TEXT;
BEGIN
    SELECT username INTO u9001 FROM socialapp.t_users WHERE id = 9001;
    SELECT username INTO u9133 FROM socialapp.t_users WHERE id = 9133;

    IF u9001 IS DISTINCT FROM 'duonghaigiang' THEN
        RAISE EXCEPTION 'Seed hong: id 9001 doi username (nay la "%"), khong con la duonghaigiang', u9001;
    END IF;
    IF u9133 IS DISTINCT FROM 'truongthithao' THEN
        RAISE EXCEPTION 'Seed hong: id 9133 doi username (nay la "%"), khong con la truongthithao', u9133;
    END IF;
END $$;

-- =============================================================================================
-- BƯỚC NGOÀI FLYWAY — cần làm thủ công sau khi migrate file này:
--
-- 1. Đồ thị bạn bè Neo4j: 40 cạnh mới đã được thêm bằng tay vào cuối
--    db/seed/friend-graph.cypher (khối "BỔ SUNG BẰNG TAY — V108"). Khởi động lại app với
--    NEO4J_SEED_ON_START=true để nạp, hoặc chạy tay:
--      docker exec -i neo4j cypher-shell -u neo4j -p <mật-khẩu> \
--        < src/main/resources/db/seed/friend-graph.cypher
--    LƯU Ý: lần generate_seed.py chạy lại tiếp theo sẽ GHI ĐÈ TOÀN BỘ file này, xoá khối bổ
--    sung — nếu muốn giữ, phải thêm 40 cạnh đó vào build_edges()/candidate list trong
--    generate_seed.py trước khi chạy lại.
--
-- 2. Chat Stream: scripts/seed/chat-plan.json đã được thêm phòng 1-1 mới và tin nhắn kèm file/
--    ảnh đính kèm cho 9001 và 9133 (xem khối "_extra_v108" trong file đó), và
--    scripts/seed/seed-stream-chat.mjs đã được sửa để đọc trường "attachments" tuỳ chọn trên mỗi
--    tin nhắn. Cần chạy (có key Stream thật, và ĐÚNG NHƯ README: --reset xoá CỨNG người dùng
--    9001-9599 trên Stream, đừng chạy vào app Stream có người dùng thật):
--      STREAM_API_KEY=... STREAM_API_SECRET=... MINIO_URL=http://localhost:9000 \
--        node scripts/seed/seed-stream-chat.mjs --reset
--
-- 3. Object MinIO của 6 quyển sách mới: seed-manifest.tsv đã có 18 dòng mới (bìa có nguồn thật
--    trỏ tới covers.openlibrary.org theo ISBN; nội dung/preview để trống vì không nằm trong
--    book-previews.json). Dev: chạy lại
--      docker compose up minio-seed-objects minio-init
--    Production: MinIOSeedObjectInitializer tự đọc manifest mới ở lần khởi động kế tiếp khi
--    minio.seed-objects-on-start=true (application-prod.yml đã bật).
-- =============================================================================================
