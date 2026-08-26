-- =============================================================================================
-- Fixture cho các màn đã dựng — dữ liệu chạm tới những nhánh mà seed V50-V61 không chạm tới.
--
-- Khác với các file seed trước, file này KHÔNG nhằm làm database trông giống thật. Mỗi hàng ở
-- đây tồn tại để bật đúng MỘT nhánh giao diện, và mỗi nhánh đều có một ngưỡng đo được. Seed cũ
-- đầy đủ về số lượng nhưng rơi trọn vào vùng "vừa đủ để không kích hoạt": bài dài nhất 101 ký
-- tự trong khi ngưỡng kẹp nội dung là khoảng 750, đoạn code dài nhất 288 ký tự trong khi ngưỡng
-- kẹp snippet là khoảng 15 dòng. Nên không ai — kể cả người đi demo — từng nhìn thấy nút "Xem
-- thêm" ở trạng thái bật.
--
-- Ngưỡng lấy từ docs/backend-plan.md mục S1-S7 (frontend đo trên UI thật). Các hằng số đó được
-- ép bằng assert trong scripts/seed/generate_demo_fixtures.py, chứ không trông vào việc người
-- đọc file SQL này tự đếm ký tự.
--
-- Dải id dùng ở đây, tiếp sau các file trước:
--   bài viết   5301-5314   (V53 dùng tới 5205)
--   bình luận  8001-8013   (V54 sinh động 6001+ cho bình luận gốc và 7001+ cho trả lời)
--   người dùng 9061        (V51 dùng 9001-9060)
--   sách       3021        (V55 dùng 3001-3020)
--
-- File này phải chạy SAU V64 vì nó ghi vào t_comment_reactions, và sau V54 vì số bình luận của
-- bốn bài dưới đây phải CHÍNH XÁC — V54 rải bình luận theo phép chia dư nên nếu nó chạy sau thì
-- bài "đúng 2 bình luận" có thể thành ba.
-- =============================================================================================

-- ── S6 · Một tài khoản có họ tên rất dài ───────────────────────────────────────────────────
-- Để kiểm việc cắt chữ ở dòng danh tính trên thanh trên cùng. Không nằm trong dải vai trò
-- 9001-9060 nên không có cạnh nào trong friend-graph.cypher — đúng ý: tài khoản này chỉ để
-- kiểm cách hiển thị một cái tên, không tham gia vào đồ thị xã hội.
--
-- Mật khẩu "12345678", cùng hash với các tài khoản thường ở V51.
INSERT INTO socialapp.t_users
    (id, email, password, username, full_name, profile_picture_url,
     email_verified, role, auth_provider, provider_id, elite_score, created_at, updated_at) VALUES
    (9061, 'other_ten_rat_dai@seed.test',
     '$2a$10$CgEPj7qoEq29zS464iIuYOlRm0IqNtk7gQ9KG5qKTC5T2F8eiJzSa',
     'other_ten_rat_dai',
     'Nguyễn Hoàng Thị Minh Phương Anh Thư Diễm Quỳnh',
     NULL, TRUE, 'USER', 'LOCAL', NULL, 0,
     now() - INTERVAL '30 days', now() - INTERVAL '30 days');

-- ── S1-S4 · Bài viết ───────────────────────────────────────────────────────────────────────
--   5301  nội dung 1742 ký tự  → "Xem thêm" PHẢI hiện (ngưỡng kẹp 280px ≈ 750 ký tự)
--   5302  nội dung  642 ký tự  → "Xem thêm" PHẢI KHÔNG hiện; đây là ca dưới ngưỡng, thiếu nó
--                                thì không phân biệt được "kẹp đúng" với "kẹp mọi thứ"
--   5303  snippet 39 dòng      → kẹp snippet 320px ≈ 15 dòng
--   5304  snippet có một dòng 348 ký tự không xuống dòng → cuộn ngang BÊN TRONG vùng đã kẹp
--   5305-5309  đủ ngôn ngữ tô màu, kèm hai ca biên:
--                plaintext  → phải ra chữ trơn, không màu
--                zig        → ngoài danh sách grammar của highlight.js. Cột language là String
--                             tự do phía backend nên đây là ca CÓ THẬT, không phải giả định
--   5310  không bình luận, không cảm xúc → nút "Xem bình luận", con số ở dạng không bấm được
--   5311  đúng 1 bình luận
--   5312  đúng 2 bình luận → không được hiện "Xem tất cả" như thể còn nữa
--   5313  6 bình luận, trong đó một cái 462 ký tự → line-clamp-2
--   5314  xem phần chặn bên dưới
INSERT INTO socialapp.t_posts
    (id, content, visibility, author_id, post_type, moderation_status, code_snippet_details,
     created_at, updated_at) VALUES
    (5301, 'Ghi lại đầy đủ một buổi truy lỗi kéo dài bốn tiếng, vì phần khó nhất không nằm ở đoạn sửa mà ở đoạn đi tìm.

Triệu chứng ban đầu rất mơ hồ: bảng tin của một số người dùng thỉnh thoảng trống trơn, tải lại thì lại có. Không có exception nào trong log, không có request nào trả về 500, và biểu đồ thời gian phản hồi phẳng lì. Đúng loại lỗi tệ nhất — thứ không để lại dấu vết nào.

Bước đầu tiên là ngừng đoán. Thay vì đọc code, mình dựng lại đúng chuỗi thao tác của một người dùng đã báo lỗi: đăng nhập, mở bảng tin, cuộn xuống, mở một bài, quay lại. Lặp hai mươi lần thì bắt được một lần trống. Có cách tái hiện rồi thì mọi thứ còn lại chỉ là thời gian.

Thủ phạm nằm ở khoảng cách giữa hai lời gọi. Bảng tin đọc danh sách id từ một sorted set, rồi đọc nội dung từng bài bằng một lệnh multi-get. Giữa hai lời gọi đó có một khoảng trống, và nếu bản ghi nội dung hết hạn đúng trong khoảng trống ấy thì danh sách id vẫn còn nhưng nội dung thì không. Code cũ lọc bỏ giá trị null rồi trả về những gì còn lại — nên với một người có ít bài trong bảng tin, mất vài bản ghi là ra trang trắng.

Thứ làm mình mất nhiều thời gian nhất không phải là tìm ra chỗ đó, mà là tin rằng nó có thật. Xác suất rơi trúng khoảng trống ấy nhỏ tới mức lần đầu nhìn thấy mình đã gạt đi. Bài học rút ra: khi một giả thuyết giải thích được đúng cả ba đặc điểm của triệu chứng — không thường xuyên, không có lỗi, tải lại thì hết — thì đừng gạt nó chỉ vì thấy khó xảy ra.

Cách sửa thì ngắn: đặt thời gian sống của bản ghi nội dung dài hơn thời gian sống của danh sách id, để danh sách luôn hết hạn trước. Ba dòng cấu hình. Nhưng mình vẫn viết lại toàn bộ hành trình ở đây, vì lần sau gặp một lỗi im lặng tương tự, thứ giúp được sẽ là quy trình này chứ không phải ba dòng đó.', 'PUBLIC', 9001, 'REGULAR', 'APPROVED', NULL, now() - INTERVAL '3 days', now() - INTERVAL '3 days'),
    (5302, 'Một mẹo nhỏ về đọc log mà mình ước có ai đó nói với mình sớm hơn.

Khi một request đi qua năm dịch vụ, thứ khiến việc lần theo trở nên bất khả thi không phải số lượng dòng log, mà là việc chúng không có gì chung để nối lại với nhau. Thêm một mã định danh duy nhất cho mỗi request ngay tại cửa ngõ, rồi truyền nó xuống mọi lời gọi phía sau, biến năm tập log rời rạc thành một dòng thời gian duy nhất.

Chi phí là một header và vài dòng cấu hình. Thứ nhận lại là mỗi lần đi tìm lỗi rút từ nửa tiếng đọc chéo bốn cửa sổ terminal xuống còn đúng một lệnh tìm kiếm, và người trực ca sau đọc lại được nguyên vẹn thứ người trực ca trước đã nhìn thấy.', 'PUBLIC', 9002, 'REGULAR', 'APPROVED', NULL, now() - INTERVAL '4 days', now() - INTERVAL '4 days'),
    (5303, 'Bộ tính tổng đơn hàng mình tách ra để kiểm thử được từng khoản một.', 'PUBLIC', 9005, 'CODE_SNIPPET', 'APPROVED', '{"language": "java", "code": "package com.example.orders;\n\nimport java.math.BigDecimal;\nimport java.util.List;\nimport java.util.Objects;\n\n/** Tính tổng một đơn hàng, tách riêng từng khoản để kiểm thử được từng phần. */\npublic final class OrderTotalCalculator {\n\n  private final TaxTable taxTable;\n  private final ShippingPolicy shippingPolicy;\n\n  public OrderTotalCalculator(TaxTable taxTable, ShippingPolicy shippingPolicy) {\n    this.taxTable = Objects.requireNonNull(taxTable);\n    this.shippingPolicy = Objects.requireNonNull(shippingPolicy);\n  }\n\n  public OrderTotal calculate(Order order) {\n    BigDecimal subtotal = subtotalOf(order.lines());\n    BigDecimal discount = discountOf(order, subtotal);\n    BigDecimal taxable = subtotal.subtract(discount);\n    BigDecimal tax = taxTable.rateFor(order.shipTo()).multiply(taxable);\n    BigDecimal shipping = shippingPolicy.costFor(order, taxable);\n    return new OrderTotal(subtotal, discount, tax, shipping, taxable.add(tax).add(shipping));\n  }\n\n  private BigDecimal subtotalOf(List<OrderLine> lines) {\n    return lines.stream()\n        .map(line -> line.unitPrice().multiply(BigDecimal.valueOf(line.quantity())))\n        .reduce(BigDecimal.ZERO, BigDecimal::add);\n  }\n\n  private BigDecimal discountOf(Order order, BigDecimal subtotal) {\n    if (order.coupon() == null) {\n      return BigDecimal.ZERO;\n    }\n    return order.coupon().appliedTo(subtotal);\n  }\n}"}'::jsonb, now() - INTERVAL '5 days', now() - INTERVAL '5 days'),
    (5304, 'Truy vấn tổng hợp mình hay dùng để soi bài nào đang được tương tác nhiều.', 'PUBLIC', 9003, 'CODE_SNIPPET', 'APPROVED', '{"language": "sql", "code": "-- Mot dong rat rong, de kiem cuon ngang ben trong vung da kep\nSELECT p.id, p.content, u.username, u.full_name, COUNT(r.user_id) AS reaction_count, COUNT(DISTINCT c.id) AS comment_count, MAX(c.created_at) AS last_comment_at FROM t_posts p LEFT JOIN t_users u ON u.id = p.author_id LEFT JOIN t_post_reactions r ON r.post_id = p.id LEFT JOIN t_comments c ON c.post_id = p.id GROUP BY p.id, u.username, u.full_name\nORDER BY reaction_count DESC;"}'::jsonb, now() - INTERVAL '6 days', now() - INTERVAL '6 days'),
    (5305, 'Script đẩy sách lên MinIO, chạy trước mỗi lần demo.', 'PUBLIC', 9031, 'CODE_SNIPPET', 'APPROVED', '{"language": "shell", "code": "#!/usr/bin/env bash\nset -euo pipefail\n\nBUCKET=books\nfor f in ./dist/*.pdf; do\n  echo \"uploading $(basename \"$f\")\"\n  mc cp --quiet \"$f\" \"seedminio/$BUCKET/\"\ndone\necho done"}'::jsonb, now() - INTERVAL '7 days', now() - INTERVAL '7 days'),
    (5306, 'File cấu hình gói của bản web, để đây cho ai cần đối chiếu phiên bản.', 'PUBLIC', 9011, 'CODE_SNIPPET', 'APPROVED', '{"language": "json", "code": "{\n  \"name\": \"elite-nexus-web\",\n  \"private\": true,\n  \"scripts\": {\n    \"dev\": \"next dev\",\n    \"build\": \"next build\",\n    \"test\": \"vitest run\"\n  },\n  \"dependencies\": {\n    \"next\": \"15.1.0\",\n    \"react\": \"19.0.0\"\n  }\n}"}'::jsonb, now() - INTERVAL '8 days', now() - INTERVAL '8 days'),
    (5307, 'Phần CSS của thẻ bài viết sau khi thống nhất lại khoảng cách.', 'PUBLIC', 9012, 'CODE_SNIPPET', 'APPROVED', '{"language": "css", "code": ".post-card {\n  display: grid;\n  gap: 12px;\n  padding: 16px;\n  border-radius: 12px;\n  background: var(--surface);\n}\n\n.post-card__content {\n  max-height: 280px;\n  overflow: hidden;\n}"}'::jsonb, now() - INTERVAL '9 days', now() - INTERVAL '9 days'),
    (5308, 'Danh sách việc trước buổi bảo vệ, dán nguyên văn không định dạng.', 'PUBLIC', 9047, 'CODE_SNIPPET', 'APPROVED', '{"language": "plaintext", "code": "Danh sach viec can lam truoc buoi bao ve\n\n1. Nap seed va chay lai fan-out bang tin\n2. Doi mat khau hai tai khoan quan tri\n3. Kiem lai luong mua sach tu dau den cuoi\n4. Chuan bi phuong an khong co mang"}'::jsonb, now() - INTERVAL '10 days', now() - INTERVAL '10 days'),
    (5309, 'Thử Zig cuối tuần, để đây đoạn hello world cho ai tò mò.', 'PUBLIC', 9025, 'CODE_SNIPPET', 'APPROVED', '{"language": "zig", "code": "const std = @import(\"std\");\n\npub fn main() !void {\n    const stdout = std.io.getStdOut().writer();\n    try stdout.print(\"xin chao\\n\", .{});\n}"}'::jsonb, now() - INTERVAL '11 days', now() - INTERVAL '11 days'),
    (5310, 'Có ai đang dùng pgvector trong dự án thật chưa, cho mình xin một lời khuyên.', 'PUBLIC', 9004, 'REGULAR', 'APPROVED', NULL, now() - INTERVAL '2 days', now() - INTERVAL '2 days'),
    (5311, 'Đổi sang virtual threads được hai tuần, chưa gặp vấn đề gì đáng kể.', 'PUBLIC', 9006, 'REGULAR', 'APPROVED', NULL, now() - INTERVAL '12 days', now() - INTERVAL '12 days'),
    (5312, 'Một câu hỏi phỏng vấn mình thấy hay: khi nào thì KHÔNG nên đánh index.', 'PUBLIC', 9009, 'REGULAR', 'APPROVED', NULL, now() - INTERVAL '13 days', now() - INTERVAL '13 days'),
    (5313, 'Kể chuyện lần đầu trực sự cố ngoài giờ, và những gì mình học được sau đó.', 'PUBLIC', 9010, 'REGULAR', 'APPROVED', NULL, now() - INTERVAL '14 days', now() - INTERVAL '14 days'),
    (5314, 'Nhóm mình đang cân nhắc bỏ monorepo, ai đã đi qua rồi cho xin kinh nghiệm.', 'PUBLIC', 9019, 'REGULAR', 'APPROVED', NULL, now() - INTERVAL '15 days', now() - INTERVAL '15 days');

-- ── S4 · Bình luận ─────────────────────────────────────────────────────────────────────────
-- created_at của mỗi bình luận được tính từ tuổi của chính bài nó thuộc về, nên không có bình
-- luận nào ra đời trước bài, và không có trả lời nào đứng trước bình luận cha của nó khi sắp
-- theo thời gian (findByPostIdOrderByCreatedAtAsc).
INSERT INTO socialapp.t_comments
    (id, post_id, author_id, content, parent_id, created_at, updated_at) VALUES
    (8001, 5311, 9001, 'Bên mình cũng vậy, chỉ lưu ý chỗ ThreadLocal thôi.', NULL, now() - INTERVAL '286 hours', now() - INTERVAL '286 hours'),
    (8002, 5312, 9003, 'Khi bảng nhỏ và ghi nhiều hơn đọc.', NULL, now() - INTERVAL '310 hours', now() - INTERVAL '310 hours'),
    (8003, 5312, 9010, 'Và khi cột có độ chọn lọc thấp, ví dụ cột trạng thái chỉ hai giá trị.', NULL, now() - INTERVAL '307 hours', now() - INTERVAL '307 hours'),
    (8004, 5313, 9057, 'Cảm ơn bạn đã kể lại, phần hậu kiểm hữu ích nhất.', NULL, now() - INTERVAL '334 hours', now() - INTERVAL '334 hours'),
    (8005, 5313, 9002, 'Mình từng ở đúng ca này nên viết dài một chút. Điều làm mọi thứ tệ đi không phải là sự cố, mà là việc không ai biết ai đang xử lý cái gì: ba người cùng vào một máy chủ, hai người cùng khởi động lại một dịch vụ, và không ai ghi lại thao tác nào. Sau lần đó nhóm mình đặt ra một luật duy nhất — mỗi sự cố có đúng một người điều phối, và người đó không gõ lệnh nào cả, chỉ ghi và phân việc. Nghe thì phí một người, nhưng thời gian xử lý trung bình giảm gần một nửa.', NULL, now() - INTERVAL '332 hours', now() - INTERVAL '332 hours'),
    (8006, 5313, 9005, 'Nhóm mình cũng vừa dựng quy trình trực, đang thiếu đúng phần ghi chép.', NULL, now() - INTERVAL '330 hours', now() - INTERVAL '330 hours'),
    (8007, 5313, 9009, 'Có mẫu biên bản sự cố nào bạn thấy dùng được không?', NULL, now() - INTERVAL '328 hours', now() - INTERVAL '328 hours'),
    (8008, 5313, 9031, 'Ủng hộ ý một người điều phối không gõ lệnh, rất đúng.', NULL, now() - INTERVAL '326 hours', now() - INTERVAL '326 hours'),
    (8009, 5313, 9047, 'Mình lưu lại để đưa vào tài liệu nội bộ, cảm ơn bạn.', NULL, now() - INTERVAL '324 hours', now() - INTERVAL '324 hours'),
    (8010, 5314, 9057, 'Bọn mình bỏ monorepo năm ngoái, hỏi gì mình trả lời.', NULL, now() - INTERVAL '358 hours', now() - INTERVAL '358 hours'),
    (8011, 5314, 9019, 'Phần CI có phải viết lại nhiều không bạn?', 8010, now() - INTERVAL '356 hours', now() - INTERVAL '356 hours'),
    (8012, 5314, 9021, 'Mình cũng quan tâm chỗ chia sẻ code dùng chung.', 8010, now() - INTERVAL '354 hours', now() - INTERVAL '354 hours'),
    (8013, 5314, 9011, 'Bên mình thì ngược lại, gộp vào monorepo và thấy dễ thở hơn.', NULL, now() - INTERVAL '351 hours', now() - INTERVAL '351 hours');

-- ── S4 · Ca "hai bình luận đầu tiên đều là trả lời" ────────────────────────────────────────
-- Khối xem trước chỉ được lấy bình luận GỐC. Ca này khó dựng một cách trung thực: một trả lời
-- luôn ra đời sau bình luận cha của nó, nên sắp theo thời gian thì dòng đầu tiên luôn là một
-- bình luận gốc — trừ khi bình luận gốc đó bị LỌC ĐI.
--
-- Nên ca này được dựng bằng đúng cơ chế lọc có sẵn: 9001 (tài khoản demo) chặn 9057, và 9057 là
-- người viết bình luận gốc đầu tiên của bài 5314. Với người xem 9001, luồng bình luận bắt đầu
-- bằng 8011 và 8012 — cả hai đều có parent_id. Với mọi người xem khác thì không, và đó cũng là
-- dữ liệu đúng: chặn là một chiều khi hành động, hai chiều khi lọc, và chỉ ảnh hưởng người chặn.
INSERT INTO socialapp.t_user_blocks (blocker_id, blocked_id, created_at) VALUES
    (9001, 9057, now() - INTERVAL '20 days');

-- ── S5 · Cảm xúc ───────────────────────────────────────────────────────────────────────────
-- Nút cảm xúc hiển thị ĐÚNG loại mà người đang đăng nhập đã chọn, nên seed phải có sẵn cho tài
-- khoản demo một cảm xúc KHÁC 'LIKE' — nếu mọi thứ đều là LIKE thì không phân biệt được "nút
-- hiện đúng lựa chọn" với "nút luôn hiện LIKE".
--
-- INSIGHT là một trong hai giá trị mới thêm cùng CLAP; chưa hàng nào trong seed cũ dùng tới,
-- nên nhánh hiển thị của chúng chưa từng chạy.
--
-- Bài 5310 cố ý không có hàng nào ở đây: cần một bài 0 cảm xúc để kiểm con số ở dạng không bấm
-- được.
INSERT INTO socialapp.t_post_reactions (user_id, post_id, reaction_type, created_at) VALUES
    (9001, 5303, 'INSIGHT', now() - INTERVAL '4 days'),
    (9001, 5313, 'CLAP',    now() - INTERVAL '9 days'),
    (9002, 5301, 'INSIGHT', now() - INTERVAL '2 days'),
    (9005, 5301, 'CLAP',    now() - INTERVAL '2 days'),
    (9010, 5301, 'LIKE',    now() - INTERVAL '1 days'),
    (9003, 5303, 'CLAP',    now() - INTERVAL '4 days'),
    (9011, 5307, 'LOVE',    now() - INTERVAL '8 days');

-- ── B14 · Cảm xúc cho bình luận ────────────────────────────────────────────────────────────
-- Bảng này vừa được tạo ở V64 và chưa có hàng nào. Không có dữ liệu ở đây thì likeCount của mọi
-- bình luận là 0, và "hai bình luận nổi nhất" — thứ mà cột likeCount sinh ra để phục vụ — vẫn
-- không phân biệt được với "hai bình luận đầu tiên".
--
-- Số lượt cố ý lệch nhau rõ: 8005 (bình luận dài) nhiều nhất, rồi 8008, rồi 8004. Xếp hạng bằng
-- một cột mà mọi hàng bằng nhau thì không kiểm được gì.
INSERT INTO socialapp.t_comment_reactions (user_id, comment_id, reaction_type, created_at) VALUES
    (9001, 8005, 'INSIGHT', now() - INTERVAL '13 days'),
    (9003, 8005, 'CLAP',    now() - INTERVAL '13 days'),
    (9005, 8005, 'LIKE',    now() - INTERVAL '12 days'),
    (9009, 8005, 'INSIGHT', now() - INTERVAL '12 days'),
    (9010, 8005, 'LOVE',    now() - INTERVAL '11 days'),
    (9002, 8008, 'CLAP',    now() - INTERVAL '11 days'),
    (9005, 8008, 'LIKE',    now() - INTERVAL '11 days'),
    (9047, 8008, 'INSIGHT', now() - INTERVAL '10 days'),
    (9002, 8004, 'LIKE',    now() - INTERVAL '13 days'),
    (9010, 8003, 'INSIGHT', now() - INTERVAL '12 days'),
    (9002, 8013, 'LIKE',    now() - INTERVAL '14 days');

-- ── S7 · Một quyển sách trỏ tới object không tồn tại ───────────────────────────────────────
-- Nhánh "kho lưu trữ hỏng" (503) không dựng lại được sau khi bucket MinIO đã được tạo: mọi
-- quyển trong seed đều có file thật do minio-seed-objects/minio-init nạp lên. Quyển này thì không —
-- file_key trỏ tới một object cố ý không tồn tại, nên bấm tải hoặc xem thử sẽ đi đúng vào
-- nhánh lỗi mà frontend đã dịch sẵn thông điệp nhưng chưa bao giờ chạy thật.
--
-- KHÔNG sửa file_key của một quyển đang chạy được để tạo ca này: làm thế là mất một ca đang
-- đúng để lấy một ca đang thiếu.
INSERT INTO socialapp.t_books
    (id, author_id, post_id, title, description, file_key, cover_image_key, preview_file_key,
     file_format, file_size_bytes, total_pages, preview_pages, price, currency, is_free,
     download_count, avg_rating, review_count, created_at, updated_at) VALUES
    (3021, 9005, NULL, 'Quyển sách có tệp đã thất lạc',
     'Bản ghi trong database còn nguyên nhưng tệp trên kho lưu trữ thì không. Đây là fixture cho nhánh lỗi kho lưu trữ, không phải sách thật.',
     'books/9005/seed-object-khong-ton-tai.pdf', NULL, NULL,
     'PDF', 1048576, 100, 10, 99000, 'VND', FALSE, 0, 0.0, 0,
     now() - INTERVAL '20 days', now() - INTERVAL '20 days');

-- ── Đẩy sequence lên quá vùng id vừa cấp tay ───────────────────────────────────────────────
-- Bắt buộc, và là loại lỗi chỉ lộ ra khi có người bấm nút chứ không phải lúc nạp seed: bản ghi
-- đầu tiên tạo qua API sẽ đụng khoá chính nếu sequence vẫn đứng ở giá trị cũ.
SELECT setval('socialapp.q_posts_id',    (SELECT MAX(id) FROM socialapp.t_posts),    true);
SELECT setval('socialapp.q_comments_id', (SELECT MAX(id) FROM socialapp.t_comments), true);
SELECT setval('socialapp.q_users_id',    (SELECT MAX(id) FROM socialapp.t_users),    true);
SELECT setval('socialapp.q_books_id',    (SELECT MAX(id) FROM socialapp.t_books),    true);
