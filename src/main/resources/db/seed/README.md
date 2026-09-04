# `db/seed` — dữ liệu demo

Thư mục này chứa **dữ liệu**, không chứa schema. Schema nằm ở `db/migration`.

Bộ seed chạy ở **cả máy dev lẫn production** (quyết định 2026-08-21). Từ 2026-08-28 không còn thư
mục `db/seed-dev` nữa — xem [Vì sao không còn `db/seed-dev`](#vì-sao-không-còn-dbseed-dev).

```
FLYWAY_LOCATIONS=classpath:db/migration,classpath:db/seed
```

Đặt biến này trong run configuration của IDE hoặc trong shell trước khi chạy app. **Production
khai đúng dòng này**, không hơn không kém.

## Bốn phần dữ liệu nằm ngoài Flyway

Không còn bước gõ tay nào, và **cả bốn phần do chính ứng dụng lo** nên chúng đi theo ứng dụng tới
mọi môi trường — kể cả production, nơi không có `docker-compose.yml` của repo này chạy.

| Ai làm | Việc |
|---|---|
| `Neo4jSeedInitializer` (ứng dụng) | nạp `db/seed/friend-graph.cypher` mỗi khi `NEO4J_SEED_ON_START=true` — file tự dọn dải 9001–9599 rồi `MERGE` lại |
| `NewsfeedSeedInitializer` (ứng dụng) | xoá sạch khoá `feed:*` rồi fan-out lại, mỗi khi `NEWSFEED_REBUILD_ON_START=true` |
| `MinIOBucketInitializer` (ứng dụng) | tạo cả **năm** bucket (thêm `job-descriptions` từ V105), đặt policy công khai cho hai bucket ảnh — có **thử lại** vài lần nếu MinIO chưa sẵn sàng |
| `MinIOSeedObjectInitializer` (ứng dụng) | đọc `db/seed/seed-manifest.tsv`, lấy ảnh (ưu tiên **ảnh nướng sẵn trong image**, rồi mới tải mạng, rồi mới sinh ô màu) và nạp lên MinIO, mỗi khi `MINIO_SEED_OBJECTS_ON_START=true` — bỏ qua object đã có ảnh thật; ghi đè ô màu cũ khi `MINIO_SEED_OBJECTS_REPLACE_PLACEHOLDERS=true`. File PDF/EPUB của sách được dựng bốn trang từ `db/seed/book-previews.json`, và một file sách một-trang của thế hệ trước cũng được coi là ô màu nên sẽ bị thay |

`minio-seed-objects` + `minio-init` trong `docker-compose.yml` vẫn còn nhưng **chỉ là đường dev**:
chúng làm đúng việc của `MinIOSeedObjectInitializer` (cộng cache ảnh ở `docker/minio/.cache/` để
lần `up` sau chạy offline). Production không chạy compose của repo này nên đi qua lớp Java.

Tất cả đều chạy lại được, nhưng **theo kiểu nạp đè chứ không phải bỏ qua**: bucket dùng
`--ignore-existing`, ba bộ khởi tạo còn lại dọn/kiểm phần dữ liệu của mình trước khi dựng lại
(`MinIOSeedObjectInitializer` kiểm từng object, đã có thì bỏ qua). Trước đây Neo4j/newsfeed đều
bỏ qua khi thấy dữ liệu đã có, và đó chính là lý do một bộ seed mới nạp xong mà danh sách bạn bè
lẫn bảng tin vẫn là của thế hệ trước.

> **Lần nạp ảnh ĐẦU TIÊN trên một MinIO trắng mất khoảng 5–6 phút, và đó không phải treo.**
> Đo thực tế: 1.139 object, 979 cái cần tải từ mạng, **971 thành công (99,2%)** trong 340 giây.
> Ở máy dev, script in tiến độ mỗi 200 object và cache ảnh vào `docker/minio/.cache/` (lần `up`
> thứ hai chỉ mất 11 giây). Trên production, `MinIOSeedObjectInitializer` chạy nền và ghi log
> dòng tổng kết; các lần khởi động sau chỉ là ~1.100 lượt `statObject` rồi bỏ qua.
>
> Số luồng tải cố ý để **4** ở cả hai đường. Đo trên 120 object: 4 luồng được 120/120 ảnh thật,
> 12 luồng còn 101/120, 24 luồng chỉ còn 24/120 — nút thắt là giới hạn tốc độ theo nguồn, không
> phải băng thông. Nhanh hơn để nhận về một bộ ô màu thì nhanh để làm gì.

**Vì sao cả bốn phần nằm trong ứng dụng chứ không phải trong compose.** Trước đây chúng là service
của `docker-compose.yml`. Máy dev vì thế luôn đúng, còn production — chạy compose của repo
`DATN-infra`, nơi không có service tương ứng — thì không: đồ thị bạn bè **rỗng** dù Postgres có hàng
nghìn lời mời đã chấp nhận, gian sách trả **503** vì bucket `books` chưa từng được tạo, và mọi
avatar / ảnh bìa / ảnh bài viết **404** (trình duyệt hiện ảnh vỡ, tệ hơn để NULL). Tất cả đều hỏng
im lặng ở đúng nơi không ai nhìn. Một cơ chế nằm trong ứng dụng thì đi theo ứng dụng; một service
trong compose chỉ có ở nơi người ta nhớ chép nó sang.

## Bước thứ ba: dựng lại bảng tin

Bảng tin đọc **duy nhất** từ Redis và không bao giờ đọc bù từ Postgres. Bỏ qua bước này thì `/feed`
trống trong khi `/posts/public` đầy — rất dễ nhầm thành lỗi frontend.

Ở máy dev, để ứng dụng tự làm:

```bash
NEO4J_SEED_ON_START=true NEWSFEED_REBUILD_ON_START=true \
  FLYWAY_LOCATIONS=classpath:db/migration,classpath:db/seed \
  ./gradlew bootRun
```

**Bật kèm `NEO4J_SEED_ON_START` chứ đừng bật một mình.** Fan-out đọc đồ thị bạn bè trong Neo4j; đồ
thị rỗng — hoặc còn là của thế hệ seed cũ — thì bài chỉ tới được người được gắn thẻ và bảng tin gần
như trống, trong khi log vẫn báo `processed=2586` y như một lần chạy thành công. Con số cần nhìn là
*số bảng tin*: khoảng 500 là đúng, vài chục nghĩa là đồ thị chưa sẵn sàng.

`NEWSFEED_REBUILD_ON_START` **xoá sạch khoá `feed:*` rồi mới fan-out lại**, nên bật thường trực ở
máy dev là an toàn và chạy lại nhiều lần vẫn ra một kết quả. Nó *không* còn bỏ qua khi Redis đã có
bảng tin: `rebuildAll` chỉ `ZADD` thêm, nên không dọn trước thì id của những bài mà `V80` vừa xoá
nằm lại trong sorted set vĩnh viễn. Mặc định **tắt**, và production **không** dùng cờ này — fan-out
toàn bộ ở mỗi lần khởi động là cái giá lớn cho một thứ production hầu như không cần.

**Production dùng `NEWSFEED_REBUILD_IF_EMPTY` (mặc định `true` trong `application-prod.yml`).** Cờ
này chỉ dựng lại — trên luồng nền, **không dọn** — khi `SCAN feed:*` ra đúng 0 khoá lúc khởi động.
`feed:*` rỗng trên production nghĩa là database vừa nạp seed hoặc mất Redis; cả hai đều cần đúng
một lần dựng lại. Deploy bình thường chỉ tốn một lượt `SCAN`. Tương tự, `NEO4J_SEED_ON_START` cũng
mặc định `true` trên prod (toàn `MERGE`, ~vài giây) nên đồ thị bạn bè luôn sẵn sàng trước khi
fan-out đọc tới. Nghĩa là **một deploy sạch trên prod không cần bước tay nào cho bảng tin** — xem
`scripts/prod/fix-seed-media.md`.

Cách thủ công vẫn còn, dùng khi cần dựng lại mà không khởi động lại (ví dụ demo cách lần seed quá
một tuần, xem cạm bẫy TTL bên dưới):

```bash
curl -XPOST http://localhost:8080/v1/api/admin/newsfeed/rebuild \
  -H "Authorization: Bearer <token của admin>"
```

> **Cạm bẫy về thời gian.** `POST_CACHE_TTL` là **7 ngày** còn `FEED_TTL` là 30 ngày, và `hasMore`
> được đếm từ id chứ không từ post. Sau 7 ngày, payload hết hạn trong khi id vẫn nằm trong sorted
> set: feed **rụng dần bài mà vẫn báo còn trang sau**. Nếu buổi demo cách lần seed quá một tuần,
> **chạy lại `newsfeed/rebuild`** trước khi lên sân khấu.

## Tài khoản demo

| id | Vai | Mật khẩu |
|---|---|---|
| **9001** | Cao thủ — 95 bài, hạng `Expert` | `12qwaszx` |
| **9002** | Người mới — chưa viết bài nào, 0 điểm | `12qwaszx` |
| **9499** | ADMIN phụ | `1234qwer` |
| **9500** | ADMIN chính | `1234qwer` |

Email theo mẫu `<username>@elitenexus.test`. Username tra ở `scripts/seed/id-map.md`.

**TLD `.test` là bắt buộc, không phải tuỳ chọn.** RFC 2606 dành riêng `.test` cho thử nghiệm: nó
không định tuyến được và không ai đăng ký được. Bộ seed này chạy trên production với luồng đặt lại
mật khẩu đang hoạt động, nên một tên miền **có thật** ở đây — kể cả `.vn` của chính dự án — đồng
nghĩa với: ai kiểm soát hòm thư ở tên miền đó chiếm được cả 500 tài khoản. Thế hệ seed trước đã
phải bỏ `@test.com` và `@socialapp.com` vì đúng lý do này.

Hai hash BCrypt được `SeedPasswordHashTest` kiểm bằng chính `BCryptPasswordEncoder` mà đường đăng
nhập dùng. Hash sai không làm migration đỏ và không làm test nào khác đỏ — chỗ phát hiện tự nhiên
của lỗi đó là buổi bảo vệ đồ án.

**Trên production, đổi mật khẩu hai tài khoản ADMIN qua API ngay sau khi seed xong.**

## Dải id

| Thực thể | Dải |
|---|---|
| `t_users` | 9001–9500 |
| `t_hashtags` | 1001–1120 thường, 1201+ dấu fixture |
| `t_roadmaps` | 2001–2012 |
| `t_books` | 3001–3080 |
| `t_projects` | 4001–4050 |
| `t_posts` | 100001–102600, cộng 102998/102999 fixture |
| `t_comments` | 200001+ |
| `t_explanations` | 300001+ |

Bảng đầy đủ, gồm cả id của từng bài fixture, nằm ở **`scripts/seed/id-map.md`** — file đó sinh tự
động cùng lượt với bộ seed, và là thứ dùng để cập nhật `DATN-frontend/docs/demo-script.md`. Kịch
bản demo trích dẫn id trên sân khấu; quên cập nhật là bấm vào một id không còn tồn tại, giữa buổi
bảo vệ.

## Vì sao dãy seed bắt đầu ở V80

Flyway phân giải **một dãy version duy nhất** cho tất cả các location. Trùng số giữa `db/seed` và
`db/migration` là **lỗi khởi động** (`Found more than one migration with version N`), không phải
cảnh báo bỏ qua được.

Tính tới 2026-08-28, `db/migration` đã dùng tới `V78` và `db/seed` từng có `V75`, `V79`. Số cao
nhất đang tồn tại là **79** → bộ seed hiện tại bắt đầu ở **V80**. Hai số 75 và 79 bỏ trống sau khi
xoá hai file đó, và điều đó không sao: Flyway không đòi dãy liền mạch.

Dãy này đã bị chiếm mất **hai lần trong ba ngày**, nên `generate_seed.py` tự quét cả hai thư mục
trước khi ghi và **dừng lại** nếu số nó định dùng đã có ở đâu đó. Đừng gỡ kiểm tra đó.

## Sinh lại bộ seed

```bash
python scripts/seed/generate_seed.py
```

Script này sinh `V81`–`V90`, `db/seed/friend-graph.cypher`, `scripts/seed/chat-plan.json`,
`db/seed/seed-manifest.tsv` và `scripts/seed/id-map.md`. `V80` (reset) và `V92` (fixture) viết
tay. `V91` bỏ trống — xem "Vì sao không seed tin xu hướng" bên dưới.

### Nội dung vài trang đầu của sách

`db/seed/book-previews.json` là **đầu vào**, không phải đầu ra của generator, và được commit:

```bash
python scripts/seed/crawl_book_previews.py   # chạy khi đổi book_catalog.py, hoặc muốn lấy lại dữ liệu mới
pip install -r scripts/seed/requirements.txt
python scripts/seed/fetch_real_previews.py   # nạp đè văn xuôi thật cho 10 quyển có nguồn hợp pháp
python scripts/seed/generate_seed.py
```

Script crawl gọi Open Library **một lần lúc soạn** để lấy **dữ kiện thư mục** của từng ISBN — tiêu
đề phụ, nhà xuất bản, năm, số trang, chủ đề và **mục lục** — rồi dựng sẵn bốn trang cho mỗi quyển:
bìa lót, mục lục, hai trang mở đầu chương một (văn xuôi máy sinh, vì Open Library không cho trích
nguyên văn). Kết quả cache ở `scripts/seed/.cache/openlibrary/` nên lần chạy sau không cần mạng;
xoá thư mục đó để lấy lại dữ liệu mới.

`fetch_real_previews.py` chạy tiếp theo, thay văn xuôi máy sinh đó bằng **văn bản thật** cho 10
quyển có nguồn xem trước hợp pháp đã xác minh tay (sách toàn văn CC/MIT tác giả tự đăng, hoặc sample
chapter PDF chính thức của Pearson/InformIT — xem `REAL_SOURCES` trong file). 70 quyển còn lại không
có nguồn thật thì mượn đoạn văn thật của một trong 10 quyển trên (`pooledFrom` ghi rõ mượn từ ISBN
nào) thay vì giữ văn xuôi máy sinh. Cache ở `scripts/seed/.cache/real-previews/`.

Ba bên đọc file này và **không bên nào gọi mạng**: `generate_seed.py` lấy `totalPages` cho
`t_books.total_pages` và **số phần tử của `pages`** cho `t_books.preview_pages`;
`docker/minio/generate-seed-objects.py` và `MinIOSeedObjectInitializer` sắp chữ ra PDF/EPUB. Vì
`preview_pages` bằng đúng số trang có thật trong file, giao diện không thể quảng cáo "xem thử 12
trang" trên một file 4 trang.

**Văn xuôi trong hai trang chương là do máy sinh**, lấy chủ đề thật của quyển sách làm nguyên liệu
— không phát hành lại nội dung có bản quyền. API cũng trả `excerpts` (trích nguyên văn); crawler cố
ý không dùng. Thế hệ trước sinh đúng **một trang A4 chỉ mang tên file**, nên bấm "Xem thử" trên một
quyển có bìa thật lại mở ra một trang trắng.

Đổi số trang, số đoạn hay cách hành văn thì sửa `CHAPTER_PAGES` / `PARAGRAPHS_PER_PAGE` /
`OPENERS`–`MIDDLES`–`CLOSERS` trong crawler, chạy lại **cả hai** script; chỉ chạy crawler mà quên
generator là `preview_pages` trong SQL lệch với file thật.

Đầu ra **tất định**: chạy lại cho `git diff` sạch nếu không đổi tham số. Đó không phải chi tiết
phong cách — nó là thứ khiến việc sửa một dòng trong bộ seed review được, thay vì mỗi lần sinh lại
là một diff 120.000 dòng.

### Ba lớp tự canh trong generator

Cả ba đều canh những thứ **không có test nào bắt được**:

1. **Trùng số version** — xem mục trên.
2. **Chuỗi `${...}` lọt vào SQL.** Flyway thay placeholder ở tầng *đọc file*, trước khi parse SQL,
   nên một placeholder chưa khai giết migration **kể cả khi nó nằm trong một dòng comment**. Đã xảy
   ra một lần ở `V51`, và một lần nữa trong lúc dựng bộ này — nội dung một câu hỏi quiz vô tình
   chứa `${...}`. Chỉ `${minioUrl}` được phép.
3. **Thiếu `SET LOCAL statement_timeout = 0`.** `application.yml` đặt `statement_timeout = 15s` lên
   pool Hikari, và **Flyway dùng chung DataSource đó**. `SeedMigrationTest` mở JDBC thô nên không đi
   qua Hikari — seed sẽ **xanh ở CI rồi chết ở production** giữa lúc migrate. `V81`–`V90` **luôn**
   phải có dòng đó, cộng thêm mọi file sinh trên 2.000 hàng; generator từ chối xuất file nếu thiếu.
   (`V87` và `V89` nhẹ hơn ngưỡng nên không tự kích hoạt — chúng nằm trong danh sách bắt buộc để
   lệnh kiểm `grep -L … V8[2-9]*.sql V90*.sql` không báo đỏ ở hai file hoàn toàn lành, vì lần sau
   người ta sẽ sửa lệnh grep chứ không sửa file.)

`SET LOCAL` chứ **không** `SET`: Flyway bọc mỗi migration trong một giao dịch nên `LOCAL` tự hết
hiệu lực khi commit. `SET` trần nới trần cho cả connection sau khi nó về pool, tức vô hiệu hoá một
lớp bảo vệ có chủ đích ở một chỗ hoàn toàn không liên quan.

## Hai điều dễ bị tưởng là lỗi

**Xác minh kỹ năng qua GitHub luôn trả `false`.** Không tài khoản nào được liên kết GitHub và
`t_github_stats` để trống hẳn (quyết định 25/08). `SkillVerificationService.verifyViaExternalApi`
tra bảng đó, không có hàng nào thì nhánh này luôn trả `false` và **mọi yêu cầu xác minh kỹ năng rơi
về duyệt tay**. Với buổi demo đây lại là điều tốt — hàng đợi quản trị có việc thật. Đừng đi tìm lỗi
trong hàm đó.

**Các chủ đề ở Kho lưu trữ lệch hẳn nhau, và `CAREER` chỉ có đúng một hàng.** `category` của bản
giải thích bám nội dung thật của từng bộ `concepts`, mà bốn trong mười hai bộ nói về phía máy chủ —
nên `BACKEND` đông hơn hẳn phần còn lại. Rải chúng ra chín tab cho cân sẽ tạo một màn hình demo đẹp
hơn và một cơ sở dữ liệu nói dối. Hàng `CAREER` duy nhất là **cố ý**: đó là ca chứng minh bộ lọc
thật sự lọc, và vì nó không nằm ở trang đầu nên cũng là ca kiểm phân trang duy nhất đáng giá ở màn
này.

## Vì sao không seed tin xu hướng

`t_trending_items` **không** có dữ liệu seed (từng có, ở `V91`, đã bỏ). `TrendingCrawlScheduler`
crawl HN/dev.to/GitHub Trending mỗi giờ và tự lấp bảng ngay khi BE chạy — kể cả trên production.
33 tin "seed-N" trỏ `tin-tuc.example.test` chỉ là dữ liệu giả nằm chờ bị ghi đè trong giờ đầu, và
trong lúc chờ nó hiện ngay trên trang chủ như dữ liệu mẫu chưa dọn — production seed xong mà chưa
kịp crawl lần đầu thì người dùng thấy thẳng link `.test` không bấm được.

`/v1/api/trending` trả rỗng cho tới khi crawler chạy lần đầu tiên. Với buổi demo: chạy app đủ lâu
trước khi lên sân khấu, hoặc gọi thủ công job crawl nếu cần trending có ngay. Số `V91` để trống,
không dồn `V92` xuống — xem "Vì sao dãy seed bắt đầu ở V80" phía trên, dãy version không cần liền
mạch.

## MoMo trong buổi demo

`MomoProperties.requestType` mặc định là **`payWithATM`** — màn hình thẻ, dễ quay video. Trên
sandbox nó đậu ở resultCode 7002 vĩnh viễn và không bao giờ tự settle, nên hãy gọi
`POST /v1/api/payments/{transactionRef}/dev-settle` để đưa đơn về `COMPLETED`.

> **`dev-settle` không còn mang `@Profile("dev")` (quyết định 2026-09-04)** — nó tồn tại ở mọi
> profile, kể cả production, để buổi demo không phụ thuộc `SPRING_PROFILES_ACTIVE` đang là gì.
> Đánh đổi: bất kỳ người dùng đã đăng nhập nào cũng tự đánh dấu đơn mua của họ là `COMPLETED` mà
> không trả tiền — free sách, ở mọi môi trường triển khai. Nếu vẫn muốn né rủi ro này thì đặt
> `MOMO_REQUEST_TYPE=captureWallet` để luồng mua tự settle thật trên sandbox, không cần endpoint
> này nữa.

Bộ seed có **đúng một** giao dịch `PENDING` mới tinh, để chạy được nhánh từ chối mua lại
(`PENDING_PAYMENT_STALE_MINUTES = 15`). Nó cố ý nằm ở một quyển **không** thuộc kịch bản demo:
một hàng `PENDING` mới trên quyển đang trình bày sẽ chặn người demo bấm mua.

## Đồ thị bạn bè: hai kho, một tập cạnh

`V82__seed_social_graph.sql` và `docker/neo4j/seed/friend-graph.cypher` được sinh **cùng một lượt,
từ một tập cạnh duy nhất**.

Quan hệ bạn bè nằm ở hai nơi với hai vai trò khác nhau: Neo4j giữ cạnh `FRIENDS_WITH` và là thứ app
thực sự đọc; Postgres chỉ giữ nhật ký lời mời. Hai bên lệch nhau thì **không có gì báo lỗi** — chỉ
là hồ sơ hiện "đã là bạn" trong khi danh sách bạn bè không có người đó.

## Vì sao không còn `db/seed-dev`

Thư mục đó ra đời ngày 2026-08-21 để giữ ba personal access token — credential dùng được ngay cho
`/v1/api/knowledge/sync/**`, một endpoint `permitAll` tự xác thực bằng chính chuỗi token. Nghĩa là
ai đọc repo là ghi được vào vault của tài khoản tương ứng.

Nay ba token đó **bỏ hẳn, không chuyển đi đâu cả**: `POST /v1/api/tokens` cho người dùng tự tạo
token trong vài giây, nên ba token seed sẵn chỉ tiết kiệm được ngần ấy thời gian, đổi lại là ba
credential nằm công khai trong repo. Bỏ token đi thì lý do tách thư mục biến mất theo.

**Mất hai fixture, ghi lại để không ai tưởng là bỏ sót:** token `WRITE_ONLY` và token **đã hết
hạn** (dùng để chạy nhánh từ chối của `PersonalAccessTokenService.verify`). API không tạo được token
hết hạn, nên nhánh đó từ nay chỉ còn unit test canh.

Hệ quả: `t_personal_access_tokens` là một trong **7 bảng cố ý để trống** — cùng 4 bảng token xác
thực (`V71` xoá sạch chúng ở mỗi lần migrate), `t_google_calendar_tokens`, và `t_github_stats`.

## Quy ước khi thêm dữ liệu

- **Không sửa file đã apply lên production — kể cả comment.** Flyway tính checksum trên toàn bộ nội
  dung file, dòng `--` cũng tính, và `application-prod.yml` bật `validate-on-migrate: true`. Thêm
  đúng 5 dòng ghi chú vào `V61` (commit `d6f6dd1`) đã làm production không khởi động được. Cần đổi
  dữ liệu thì thêm file mới với số version cao hơn.
- **Đừng sửa tay `V81`–`V90`.** Chúng sinh tự động; sửa tay sẽ bị ghi đè ở lần chạy generator kế
  tiếp. Sửa `scripts/seed/generate_seed.py` rồi chạy lại.
- **Bộ seed đã được RE-BASELINE ngày 2026-08-30.** `V88` nay mang `parent_node_id` (cây lộ trình),
  và toàn bộ `V81`–`V92` được sinh lại một lượt — nên **checksum của chúng khác với bản `f9aeea7`
  đang chạy trên production**. Deploy nào ship bản này PHẢI drop schema production trước khi
  migrate, nếu không `validate-on-migrate` chặn khởi động ở `V88`. Các bước cụ thể (kèm sao lưu):
  **`scripts/prod/rebaseline-seed.sql`**. Trước 2026-08-30, cây lộ trình nằm ở `V95` — một `UPDATE`
  chạy sau `V88` — vì lúc đó chưa re-baseline được; `V95` nay đã xoá.
- **Lấy mẫu ngẫu nhiên phải dùng hai modulo nguyên tố cùng nhau** cho điều kiện lọc và cho biểu thức
  chọn giá trị. Dùng chung một modulo làm kết quả chỉ rơi vào vài nhánh — đã xảy ra ở `V55` cũ, làm
  mất hẳn hai trạng thái thanh toán.
- **Không đặt credential dùng được vào thư mục này.** Nó chạy trên production.
- **Không seed cột trỏ tới object MinIO trừ khi manifest có khai key đó.** Một cột trỏ tới object
  không tồn tại thì **tệ hơn `NULL`**: URL vẫn dựng được nên trình duyệt hiện ảnh vỡ, chứ không rơi
  về fallback. `banner_url` của dự án để `NULL` vì lý do này.
- **Mỗi loại ảnh mới là năm chỗ phải sửa cùng lúc**: nguồn trong `generate_seed.py` (`want_image` +
  `BUCKET_OF_PREFIX`), thư mục prefix, dòng `mc cp` trong `docker-compose.yml`, và
  `BUCKET_OF_PREFIX` + `placeholder()` trong `MinIOSeedObjectInitializer.java` (đường production).
  Bỏ sót một chỗ thì hỏng im lặng.
- **URL ảnh phải có đủ segment bucket**: `<minio.url>/<bucket>/<key>`, đúng như `MediaService`
  dựng. Manifest chỉ giữ `<key>` — `minio-init` (dev) và `MinIOSeedObjectInitializer` (prod) đều
  nạp object với key nguyên vẹn vào bucket tra ở `BUCKET_OF_PREFIX`, còn `want_image` ghép
  `<bucket>` vào URL. Thiếu khúc bucket thì MinIO trả **403**, URL vẫn hợp lệ, và không có gì báo
  lỗi.
- **Giá trị của cột `@Enumerated(EnumType.STRING)` phải là hằng CÓ THẬT của enum Java.** Những cột
  ấy là `varchar` không có `CHECK`, nên một nhãn tự chế đi qua Flyway êm ru rồi nổ ở Hibernate lúc
  **đọc** — tức ở tầng ứng dụng, sau khi seed đã xanh. Ngày 28/08 có sáu cột dính cùng lúc, và hai
  cái đắt nhất: `explanation_style = 'ANALOGY'` (enum viết `ANALOGY_HEAVY`) làm **256/500 tài
  khoản không đăng nhập được**, còn `t_notifications.channel = 'IN_APP'` (enum chỉ có
  `PUSH`/`EMAIL`/`BOTH`) làm `GET /notifications` trả **500 cho mọi tài khoản**. Cả sáu đều là
  chuỗi nghe rất hợp lý — đó chính là lý do không ai đọc ra khi review. Ca kiểm
  `everyEnumeratedColumnParsesBackIntoItsJavaEnum` trong `SeedMigrationTest` quét việc này bằng
  reflection nên **cột enum mới tự vào tầm ngắm**, không phải nhớ cập nhật danh sách.
  Và khi enum của ứng dụng không có giá trị mình cần: **bộ seed đi theo ứng dụng**, không thêm
  hằng vào enum cho tiện — mọi hằng mới đều nới hợp đồng OpenAPI mà frontend sinh client từ đó.
- **Hình dạng bên trong cột `jsonb` phải khớp kiểu Java mà entity khai.** Cùng một lớp lỗi với
  enum, khác cơ chế: Postgres chỉ đòi JSON *hợp lệ*, nên một mảng chuỗi nằm ở chỗ đáng lẽ là mảng
  đối tượng vẫn chèn được và mọi assertion SQL vẫn xanh. `t_explanations.external_links` từng mang
  `["https://…"]` trong khi kiểu là `List<ExternalLink>` (`{title, url, reason}`), và cả màn Kho
  lưu trữ trả 500. Bẫy riêng ở đây: **`t_vault_notes.links` đúng là `List<String>`** — hai cột
  cùng tên gọi "links", hai kiểu khác nhau. Ca kiểm
  `everyJsonColumnDeserialisesIntoItsJavaType` đọc **mọi hàng** của **mọi cột `jsonb`** và thử
  dựng lại đúng kiểu Java, cũng bằng reflection.

## Thời gian chạy

Toàn bộ seed nạp trong khoảng **25 giây** trên Testcontainers. `SeedMigrationTest` nằm trong
`./gradlew build` bình thường và cộng ngần ấy vào mỗi lần build — build "đứng" một lúc ở đúng một
test là **bình thường, không phải treo**.

Đổi lấy việc không phát hiện một file seed hỏng ở production là đánh đổi đúng: không một test nào
khác chạy bộ seed.

## Database đã lỡ chạy seed cũ

`V80__seed_reset.sql` dọn sạch cả hai thế hệ trước (dải id 9001–9599, các tên miền email cũ, và ba
bảng tham chiếu `t_users` **không** có `ON DELETE CASCADE`: `t_comments.author_id`,
`t_projects.author_id`, `t_project_applications.applicant_id`). Trên database sạch nó là no-op.

Muốn làm lại từ đầu hoàn toàn:

```bash
docker compose down -v      # -v cho named volume minio-seed-objects
rm -rf .docker-data         # và cái này cho BỐN kho dữ liệu — xem ghi chú ngay dưới
python scripts/seed/generate_seed.py
docker compose up -d
NEO4J_SEED_ON_START=true NEWSFEED_REBUILD_ON_START=true \
  FLYWAY_LOCATIONS=classpath:db/migration,classpath:db/seed \
  ./gradlew bootRun
```

**`docker compose down -v` một mình không xoá dữ liệu nào cả.** Postgres, Neo4j, Redis và MinIO
đều là *bind mount* dưới `./.docker-data/`; named volume duy nhất trong cả `docker-compose.yml` là
`minio-seed-objects`, và `-v` chỉ dọn đúng cái đó. Không có `rm -rf .docker-data` thì cả bốn kho
giữ nguyên dữ liệu của thế hệ seed trước. Postgres không lộ ra vì `V80` tự dọn; ba kho kia thì
không có ai dọn hộ.

**Hai biến môi trường, không phải một.** `NEO4J_SEED_ON_START` nạp đồ thị bạn bè — Flyway không
quản Neo4j. Thiếu nó thì `/friendships` rỗng trong khi hồ sơ vẫn hiện "đã là bạn", và vì fan-out
bảng tin đọc chính đồ thị đó nên `/feed` cũng gần như trống theo. Cả hai cờ đều nạp đè: chúng dọn
dữ liệu cũ của mình trước khi dựng lại, nên chạy lại nhiều lần vẫn ra một kết quả.

### Chat (Stream)

Dữ liệu chat **không** nằm trong `docker compose up` vì mỗi lần chạy tiêu quota SaaS thật:

```bash
STREAM_API_KEY=... STREAM_API_SECRET=... MINIO_URL=http://localhost:9000 \
  node scripts/seed/seed-stream-chat.mjs --reset
```

**`--reset` là bắt buộc mỗi khi nạp lại seed, không phải tuỳ chọn.** Id người dùng phía Stream
chính là id số bên Postgres, và các thế hệ seed đều nằm trong dải 9001+ nên trùm lên nhau: bỏ
`--reset` thì người mới id 9005 thừa kế nguyên phòng và tin nhắn của người cũ id 9005 — đúng
triệu chứng "đổi tài khoản mà chat vẫn y như cũ". `V80` không với tới Stream được, và cũng không
có bước nào khác trong repo chạm tới nó.

`--reset` **xoá cứng** người dùng 9001–9599 cùng phòng của họ trên Stream. App Stream phải bật
*permanent user deletion* (xoá mềm không giải phóng id, nên script sẽ dừng và báo thay vì âm thầm
lùi về xoá mềm). **Đừng chạy vào một app Stream có người dùng thật.**

`MINIO_URL` đổ vào `${minioUrl}` trong `chat-plan.json`, đúng vai trò mà
`spring.flyway.placeholders.minioUrl` làm cho các file `.sql`. Mặc định đã là
`http://localhost:9000` nên máy dev có thể bỏ qua; sai giá trị thì avatar trong chat vỡ mà không
có gì báo.

Script đọc `scripts/seed/chat-plan.json` (sinh cùng lượt với `V82`), nên phòng 1-1 chỉ tồn tại
giữa những người **đã là bạn** — một sai lệch mà Stream không bao giờ báo, vì với Stream đó là một
phòng hợp lệ. Chạy thử không cần key: `node scripts/seed/seed-stream-chat.mjs --reset --dry-run`.
