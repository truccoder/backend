# db/seed — dữ liệu mẫu, chạy ở CẢ dev lẫn production

Từ 2026-08-21 production cũng nạp thư mục này (cần dữ liệu demo trên môi trường thật).
Thư mục **`db/seed-dev`** thì không — xem phần cuối.

| Môi trường | `FLYWAY_LOCATIONS` |
|---|---|
| mặc định (không cấu hình) | `classpath:db/migration` — chỉ schema |
| máy dev | `classpath:db/migration,classpath:db/seed,classpath:db/seed-dev` |
| production | `classpath:db/migration,classpath:db/seed` (đặt trong `application-prod.yml`) |

## Bật seed ở máy dev

```
FLYWAY_LOCATIONS=classpath:db/migration,classpath:db/seed,classpath:db/seed-dev
```

Đặt biến này trong run configuration của IDE hoặc trong shell trước khi chạy app.

Hai phần dữ liệu nằm ngoài Flyway — đồ thị bạn bè trong Neo4j và object trong MinIO — **không còn
bước tay nào nữa**. `docker compose up` lo cả hai:

| Service | Việc nó làm |
|---|---|
| `neo4j-seed` | nạp `docker/neo4j/seed/friend-graph.cypher` sau khi Neo4j trả lời được Bolt |
| `minio-seed-objects` | đọc key từ chính file SQL rồi sinh file PDF/EPUB/PNG mẫu |
| `minio-init` | tạo 4 bucket, đặt policy công khai cho 2 bucket, tải các file đó lên |

Cả ba đều một lần rồi thoát, và chạy lại được: bucket dùng `--ignore-existing`, cypher script tự
idempotent.

TRƯỚC ĐÂY HAI BƯỚC NÀY LÀ LỆNH GÕ TAY và đó chính là vấn đề. Bỏ bước Neo4j thì danh sách bạn bè
rỗng dù lịch sử lời mời đầy đủ; bỏ bước MinIO thì gian sách trả 503 vì bucket `books` không tồn
tại (`BookStorageService.getPresignedUrl` hỏi region của bucket trước khi ký), và khi bucket đã có
mà object thì không, ba tài khoản ở `V66` hiện ảnh đại diện vỡ thay vì rơi về chữ viết tắt. Một
bước bắt buộc mà phải nhớ gọi thì sớm muộn cũng có người quên.

### Bước thứ ba, không bỏ được: dựng lại bảng tin

```bash
curl -XPOST http://localhost:8080/v1/api/admin/newsfeed/rebuild      -H "Authorization: Bearer <token của admin_one@seed.test>"
```

**Không chạy lệnh này thì `GET /v1/api/feed` rỗng với MỌI tài khoản seed**, dù database đầy bài.
Bảng tin đọc duy nhất từ Redis (`NewsfeedService.loadPostsFromCache`) và không bao giờ đọc bù từ
Postgres; đường duy nhất ghi vào Redis là `fanOutPost`, chỉ chạy khi có người đăng bài qua API.
Bài do SQL đổ vào không đi qua đường đó. Màn `/newsfeed` sẽ trống trơn trong khi `/posts/public`
vẫn đầy — triệu chứng dễ bị nhầm thành lỗi frontend.

Cùng lý do, chạy lại lệnh này sau bất kỳ lần nào mất Redis.

### Sau khi deploy một thay đổi có thêm trường vào bài viết

`FeedPostDataDto` được cache 7 ngày. Bản JSON cũ trong Redis không có trường mới, nên bài đang
nằm trong cache sẽ trả `null` ở trường đó cho tới hết TTL — `authorUsername` là ca gần nhất. Xoá
cache bài rồi dựng lại:

```bash
docker exec -i redis redis-cli --scan --pattern 'feedpost:*'   | xargs -r docker exec -i redis redis-cli DEL
```

(Tiền tố thật đọc ở `POST_CACHE_KEY_PREFIX` trong `PostScoringService`.)

## Tài khoản

| | |
|---|---|
| Dải id | `9001`–`9060` |
| Mật khẩu tài khoản thường | `12345678` |
| Mật khẩu 2 tài khoản ADMIN | `SocialApp@Admin2026` |
| Email | `<username>@seed.test` |
| Quản trị viên | `admin_one@seed.test`, `admin_two@seed.test` (id 9059, 9060) |

**Hai mật khẩu, và cả hai đều nằm trong repo.** `V51` gán mật khẩu bằng một `CASE`: tài khoản
thường dùng `12345678`, hai tài khoản ADMIN dùng `SocialApp@Admin2026`.

Việc tách ra chỉ chặn được một thứ: `12345678` là chuỗi đầu tiên mọi bot dò mật khẩu thử mà không
cần đọc repo. Nó **không** chặn được người đọc được repo — họ vào được cả hai. Vì bộ seed này chạy
cả trên production, muốn đóng hẳn đường đó thì đổi mật khẩu hai tài khoản ADMIN qua API ngay sau
khi seed xong.

Sinh hash mới cho mật khẩu khác (chỉ cần docker), rồi bỏ dấu `:` ở đầu chuỗi kết quả:

```bash
docker run --rm httpd:alpine htpasswd -bnBC 10 "" 'mat-khau-cua-ban'
```

Email dùng TLD `.test` — RFC 2606 dành riêng cho thử nghiệm, không định tuyến được và không ai
đăng ký được. Ở production điều đó có nghĩa là các tài khoản này **không nhận được mail**, nên
không khôi phục mật khẩu qua email được — đổi mật khẩu rồi quên là mất đường vào chúng.

Đó cũng là lý do không dùng tên miền thật. Thế hệ seed trước dùng `@test.com` và
`@socialapp.com`, cả hai đều do người khác sở hữu: ai kiểm soát hòm thư ở đó có thể bấm "quên mật
khẩu" để chiếm tài khoản.

Cụm vai trò (khớp `friend-graph.cypher`):

| Vai trò | Id | | Vai trò | Id |
|---|---|---|---|---|
| BACKEND | 9001–9010 | | DATA_ML | 9037–9042 |
| FRONTEND | 9011–9018 | | SECURITY | 9043–9046 |
| FULLSTACK | 9019–9024 | | QA | 9047–9052 |
| MOBILE | 9025–9030 | | OTHER | 9053–9058 |
| DEVOPS | 9031–9036 | | ADMIN | 9059–9060 |

Vài trạng thái đặc biệt để thử các nhánh xử lý:

- `backend_ngoc_quan` (9007) — **đang bị cấm**, đủ 2 vi phạm, lệnh cấm 7 ngày còn hiệu lực
- `mobile_huu_nghia` (9028) — đã từng bị cấm, lệnh cấm đã hết hạn
- 9007, 9013, 9028, 9051 — `email_verified = false`, dùng thử luồng xác thực email
- 9003, 9014, 9029, 9058 — đăng nhập qua GITHUB, có bản ghi thống kê GitHub
- 9008, 9021, 9039 — đăng nhập qua GOOGLE

## Các file

| File | Nội dung |
|---|---|
| `V50__seed_reset.sql` | Dọn thế hệ seed cũ. Database sạch thì là no-op. |
| `V51__seed_users.sql` | 60 tài khoản, hồ sơ nghề nghiệp, tuỳ chọn thông báo |
| `V52__seed_social_graph.sql` | 240 quan hệ bạn bè, 26 lời mời chờ, 8 chặn — **sinh tự động** |
| `V53__seed_posts.sql` | 169 bài đủ 8 `PostType`, 30 hashtag, gắn thẻ người |
| `V54__seed_engagement.sql` | 2278 cảm xúc, 741 bình luận (có lồng nhau), RSVP, bài nộp quiz |
| `V55__seed_bookstore.sql` | 20 sách, 257 đánh giá, 215 giao dịch đủ 4 trạng thái |
| `V56__seed_knowledge.sql` | 329 bản giải thích, 76 ghi chú vault |
| `V57__seed_projects.sql` | 12 dự án, 16 vị trí tuyển, 29 đơn ứng tuyển |
| `V58__seed_roadmaps.sql` | 5 lộ trình, 44 nút, 144 bản ghi tiến độ |
| `V59__seed_moderation.sql` | 167 log kiểm duyệt, vi phạm, lệnh cấm, khiếu nại |
| `V60__seed_reputation_and_notifications.sql` | 2367 sự kiện uy tín, 984 thông báo |
| `V61__seed_trending_and_github.sql` | 12 tin xu hướng, thống kê GitHub |
| `V65__seed_demo_fixtures.sql` | Fixture cho S1-S7: bài dài, snippet dài/đủ ngôn ngữ, các ca bình luận 0/1/2/6, cảm xúc `INSIGHT`/`CLAP`, cảm xúc cho bình luận, một sách có tệp thất lạc |
| `V67__seed_markdown_explanation.sql` | Một bản giải thích AI có đủ bảy kiểu phần tử Markdown (S8) |
| `V70__seed_comment_mentions.sql` | Ba bình luận có `@handle` + thông báo `USER_MENTIONED` tương ứng, gồm một ca âm |

**Hai file trong `db/migration` sửa dữ liệu mà `db/seed` vừa đổ vào**, nên đọc chúng cùng lúc với
bảng trên:

- `V72__add_post_id_to_notifications.sql` điền `post_id` cho các thông báo `COMMENT` của `V70` —
  không có nó thì thông báo nhắc tên hiện ra nhưng bấm không đi đâu.
- `V73__comments_are_like_only.sql` đổi 7 hàng `INSIGHT`/`CLAP`/`LOVE` mà `V65` ghi vào
  `t_comment_reactions` thành `LIKE`, vì bình luận chỉ được thích. **Đừng xoá chúng** — phân bố
  lệch 5/3/1/1/1 là thứ duy nhất để kiểm "hai bình luận nổi nhất", và `SeedMigrationTest` assert
  đúng điều đó. Các hàng `INSIGHT`/`CLAP` trên `t_post_reactions` ngay phía trên trong cùng file
  thì giữ nguyên: luật chỉ-LIKE là của bình luận, bài viết vẫn đủ bảy cảm xúc.

Và một file ở thư mục riêng, **không** chạy ở production:

| File | Nội dung |
|---|---|
| `db/seed-dev/V63__seed_dev_tokens.sql` | 3 personal access token — credential dùng được ngay |
| `db/seed-dev/V66__seed_dev_avatars.sql` | Ảnh đại diện cho 3 tài khoản — URL tuyệt đối trỏ localhost |

Kết quả: **35/40 bảng** có dữ liệu (34 nếu không nạp `db/seed-dev`) — `t_comment_reactions` là
bảng mới, thêm ở `V64`.

## Năm bảng cố ý để trống

Không phải bỏ sót:

- `t_refresh_tokens`, `t_password_reset_tokens`, `t_magic_link_tokens`,
  `t_email_verification_tokens` — vật phẩm tạm của luồng xác thực, sống vài phút tới vài ngày.
  Một refresh token nằm sẵn trong file SQL là một credential dùng được đã commit vào repo, đúng
  loại vấn đề mà bộ seed cũ mắc phải với mật khẩu admin.
- `t_google_calendar_tokens` — chứa token OAuth thật của Google, không bịa được. Điền giá trị
  giả thì tài khoản hiện "đã kết nối" nhưng mọi lần đồng bộ đều thất bại, và người thử tính năng
  sẽ đi tìm lỗi trong code.

## File sinh tự động — đừng sửa tay

`V52__seed_social_graph.sql` và `docker/neo4j/seed/friend-graph.cypher` đều do
`scripts/seed/generate_friend_graph.py` sinh ra, từ **một** tập cạnh duy nhất:

```bash
python scripts/seed/generate_friend_graph.py
```

Quan hệ bạn bè nằm ở hai nơi với hai vai trò khác nhau: Neo4j giữ cạnh `FRIENDS_WITH` và là thứ
app thực sự đọc; Postgres chỉ giữ nhật ký lời mời. Hai bên lệch nhau thì **không có gì báo lỗi**
— chỉ là hồ sơ hiện "đã là bạn" trong khi danh sách bạn bè không có người đó. Vì vậy chúng được
sinh cùng một lượt thay vì viết tay hai lần.

## `db/seed-dev` — chỉ máy dev, KHÔNG BAO GIỜ ở production

`V63__seed_dev_tokens.sql` tạo 3 personal access token dùng được ngay cho
`/v1/api/knowledge/sync/**` (endpoint này `permitAll` ở Spring Security và tự xác thực bằng chính
token đó). Đó là lý do nó nằm ở thư mục riêng: nội dung demo thì vô hại ở mọi môi trường, còn một
API token thì không.

| Token | Tài khoản | Quyền |
|---|---|---|
| `sk_seed_dev_vault_token_alpha` | 9003 | BIDIRECTIONAL, không hết hạn |
| `sk_seed_dev_vault_token_beta` | 9021 | WRITE_ONLY, còn 90 ngày |
| `sk_seed_dev_vault_token_gamma` | 9039 | BIDIRECTIONAL, **đã hết hạn** |

Đừng bao giờ thêm `classpath:db/seed-dev` vào `FLYWAY_LOCATIONS` của production.

## Quy ước khi thêm dữ liệu

- **Không sửa file đã apply lên production — kể cả comment.** Flyway tính checksum trên toàn bộ
  nội dung file, dòng `--` cũng tính, và `application-prod.yml` bật `validate-on-migrate: true`.
  Thêm đúng 5 dòng ghi chú vào `V61` (commit d6f6dd1) đã làm production không khởi động được:
  `Migration checksum mismatch for migration version 61`. Ghi chú về dữ liệu seed thì đặt ở chỗ
  code đọc nó — Javadoc của repository, hoặc file README này — chứ không đặt vào file `.sql` đã
  chạy. Nếu buộc phải sửa nội dung, thêm file version mới thay vì sửa file cũ.
- Số version tiếp tục từ `V74`. Ba thư mục `db/migration`, `db/seed` và `db/seed-dev` dùng CHUNG
  một dãy version, nên không được giẫm số của nhau: `V62`, `V64`, `V68`, `V71`, `V72`, `V73` là
  schema; `V63`, `V66`, `V69` là `db/seed-dev`; `V65`, `V67`, `V70` là `db/seed`. Thêm file mới ở
  bất kỳ thư mục nào thì lấy số kế tiếp còn trống rồi cập nhật dòng này.
- `V65` phải đứng sau `V54`: số bình luận của bốn bài kiểm ca 0/1/2/6 phải CHÍNH XÁC, mà `V54`
  rải bình luận theo phép chia dư — nó chạy sau thì bài "đúng 2 bình luận" có thể thành ba.
- Seed phải đứng sau mọi migration schema **tạo bảng mà seed ghi vào**. Một migration số cao hơn
  seed chỉ an toàn khi nó tạo bảng mới (như `V62`); nếu nó sửa bảng mà seed đã đổ dữ liệu thì
  phải đánh số thấp hơn dải seed.
- Id tường minh theo dải: người dùng `9001+`, bài viết `5001+`, bình luận `6001+`/`7001+`,
  sách `3001+`, dự án `4001+`, lộ trình `2001+`, hashtag `1001+`. File nào cấp id tường minh thì
  **bắt buộc** gọi `setval` ở cuối, nếu không bản ghi đầu tiên tạo qua API sẽ đụng khoá chính —
  lỗi chỉ lộ ra khi có người bấm nút, không phải lúc nạp seed.
- Khi lấy mẫu ngẫu nhiên bằng phép chia dư, điều kiện lọc và biểu thức chọn giá trị phải dùng
  **hai modulo nguyên tố cùng nhau**. Dùng chung một modulo thì hai biểu thức tương quan và tập
  kết quả chỉ rơi vào vài nhánh — đã xảy ra ở `V55`, làm mất hẳn hai trạng thái thanh toán.
- **Không đặt credential dùng được vào `db/seed`.** Thư mục đó chạy trên production. Token, khoá
  API, hay bất cứ thứ gì đăng nhập được mà không cần mật khẩu thì thuộc về `db/seed-dev`.
- Không seed cột trỏ tới object MinIO trừ khi `docker/minio/generate-seed-objects.py` có quét
  file đó (xem `SOURCES` trong script). Banner dự án để `NULL` vì lý do này. Một cột trỏ tới
  object không tồn tại thì tệ hơn `NULL`: URL vẫn dựng được nên trình duyệt hiện ảnh vỡ, chứ
  không rơi về fallback.

## Database đã lỡ chạy seed cũ

`flyway_schema_history` vẫn còn V20/V21/V25/V29/V30 trong khi file không còn resolve được.
`spring.flyway.ignore-migration-patterns: "*:missing"` trong `application.yml` xử lý việc đó, và
`V50__seed_reset.sql` dọn các hàng dữ liệu cũ. Không cần sửa bảng history bằng tay.

Với **production**, các tài khoản do seed cũ tạo ra vẫn còn trong database — chạy
`scripts/prod/remediate-seed-accounts.sql` để rà và vô hiệu hoá chúng.
