# Bảng ID mốc của bộ seed

SINH TỰ ĐỘNG bởi `scripts/seed/generate_seed.py` — đừng sửa tay.

Dùng file này để cập nhật `DATN-frontend/docs/demo-script.md` **cùng lượt** với mỗi lần
sinh lại bộ seed. Kịch bản demo trích dẫn id trên sân khấu; quên cập nhật là bấm vào một
id không còn tồn tại, giữa buổi bảo vệ.

## Tài khoản

| id | username | vai | ghi chú |
|---|---|---|---|
| 9001 | `duonghaigiang` | cao thủ | Dương Hải Giang |
| 9002 | `hoangthanhlong` | người mới | Hoàng Thanh Long |
| 9499 | `lygiahoa` | admin phụ | Lý Gia Hoà |
| 9500 | `hokimdiep` | admin chính | Hồ Kim Diệp |

Mật khẩu: tài khoản thường `12qwaszx`, ADMIN `1234qwer`.
Email: `<username>@elitenexus.test`

## Cặp demo: gợi ý kết bạn ("vì sao gợi ý người này")

9133 và 9224 CHƯA là bạn nhưng đứng #1 trong danh sách gợi ý của nhau — mỗi người đăng
nhập sẽ thấy người kia kèm đủ ba lý do: cùng vai trò MOBILE, tech stack khớp 100%
(Kotlin/Swift/Flutter/Dart/Firebase/Jetpack Compose), và hashtag trùng trên bài PUBLIC.
Có sẵn một lời mời PENDING (9133 → 9224) để demo luôn bước chấp nhận, và một lượt bình
luận + thích qua lại trên bài của 9133 (id 102143) cùng một đơn ứng tuyển ACCEPTED của
9224 vào một vị trí Mobile có sẵn (dự án 4012, vị trí 31) để demo thêm bảng tin/matchmaking
trên cùng hai tài khoản này.

| id | username | vai | ghi chú |
|---|---|---|---|
| 9133 | `truongthithao` | Kỹ sư Mobile, MID | Trương Thị Thảo |
| 9224 | `ngotrungkhoa` | Kỹ sư Mobile, SENIOR | Ngô Trung Khoa |

## Dải id

| Thực thể | Dải |
|---|---|
| `t_users` | 9001–9500 |
| `t_hashtags` | 1001–1120 (thường), 1201+ (dấu fixture) |
| `t_roadmaps` | 2001–2012 |
| `t_books` | 3001–3080 |
| `t_projects` | 4001–4050 |
| `t_posts` | 100001–102600 (+102998, 102999 fixture) |
| `t_comments` | 200001+ |
| `t_explanations` | 300001+ |

## Bài fixture (tìm bằng hashtag, không bằng id)

| Hashtag | id hiện tại | Ca kiểm |
|---|---|---|
| `fixture_zero_comments` | 100841 | bài không có bình luận nào |
| `fixture_one_comment` | 100842 | đúng một bình luận gốc |
| `fixture_two_comments` | 100843 | đúng hai bình luận gốc |
| `fixture_many_comments` | 100844 | từ năm bình luận gốc trở lên |
| `fixture_zero_reactions` | 100847 | bài không có cảm xúc nào |
| `fixture_long_content` | (tra theo dấu) | bài từ 1200 ký tự |
| `fixture_medium_content` | (tra theo dấu) | bài 550–750 ký tự |
| `fixture_missing_image` | (tra theo dấu) | ảnh trỏ vào object không tồn tại |
| `fixture_blocked_thread` | 102998 | sau khi lọc chặn, hai bình luận cũ nhất đều là trả lời |
| `fixture_mixed_levels` | 102999 | luồng bình luận có nhiều hạng uy tín |

## Lộ trình

| id | Tên | Số nút |
|---|---|---|
| 2001 | Backend cho người mới | 12 |
| 2002 | Frontend hiện đại | 11 |
| 2003 | DevOps thực dụng | 11 |
| 2004 | Dữ liệu và học máy | 10 |
| 2005 | An toàn ứng dụng | 9 |
| 2006 | Kiểm thử và chất lượng | 9 |
| 2007 | Kỹ sư Mobile | 8 |
| 2008 | Fullstack cân bằng | 7 |
| 2009 | Nền tảng khoa học máy tính | 6 |
| 2010 | Phát triển sự nghiệp | 7 |
| 2011 | Kiến trúc phần mềm | 7 |
| 2012 | Sản phẩm cho kỹ sư | 6 |

## Sách mốc

| id | Tựa | Giá |
|---|---|---|
| 3001 | Clean Code | miễn phí |
| 3002 | The Go Programming Language | 249.000₫ |
| 3003 | Clean Architecture | 199.000₫ |
| 3004 | Effective Java | 249.000₫ |
| 3005 | Refactoring | 149.000₫ |
