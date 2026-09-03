# Chữa nội dung seed trên production (ảnh ô màu / mất ảnh / bảng tin trống)

Runbook cho triệu chứng: **deploy lâu, ảnh seed là ô màu solid, một số nơi mất ảnh hẳn, tab Bạn bè
và Kỹ năng của `/newsfeed` không có post nào**. Đi kèm `rebaseline-seed.sql` (drop schema) — chạy
trong cùng một cửa sổ bảo trì.

---

## 1. Do đâu

| Triệu chứng | Nguyên nhân gốc |
|---|---|
| Deploy lâu (10–15 phút, rồi rollback) | Bộ seed đã **re-baseline 2026-08-30** nhưng production chưa drop schema → `validate-on-migrate` thấy checksum V81–V92 lệch → migration chết ở V88 → hết vòng health 900s → rollback. (Xem `rebaseline-seed.sql`.) Trên DB sạch thì migrate + seed qua Supabase pooler mất 3–8 phút — đó là **một lần**, các deploy sau ~1 phút. |
| Ảnh là ô màu solid | `MinIOSeedObjectInitializer` tải ~980 ảnh từ DiceBear/Picsum/Pravatar/Open Library **ngay lúc VPS khởi động**. Dịch vụ công cộng bóp băng thông IP datacenter → timeout → nhét ô màu. Và ô màu, một khi đã nằm trong MinIO, **được bỏ qua vĩnh viễn** ở mọi lần khởi động sau. |
| Mất ảnh hẳn (img vỡ, không cả ô màu) | `minio.url` prod trỏ URL công khai qua Caddy. `MinIOBucketInitializer` đặt policy public-read **qua đúng endpoint đó**; lần khởi động đầu Caddy chưa lên → lệnh fail, **không retry** → `profile-pictures`/`post-media` vẫn private → ảnh 403. Cộng thêm: doubled-slash `//` nếu `MINIO_PUBLIC_URL` có `/` cuối (V102 sửa, nhưng chỉ khi migration chạy được), và nạp ảnh dở dang khi container bị giết giữa vòng rollback. |
| Tab Bạn bè / Kỹ năng của `/newsfeed` trống | Bảng tin đọc **duy nhất** từ Redis (RAM). Bài seed `INSERT` thẳng vào Postgres, không đi qua đường đăng bài → **chưa từng fan-out**. `application-prod.yml` bật `MINIO_SEED_OBJECTS_ON_START` nhưng **không** bật `NEO4J_SEED_ON_START` / `NEWSFEED_REBUILD_ON_START` → đồ thị bạn bè Neo4j rỗng **và** `feed:*` rỗng. Tab Kỹ năng chỉ là tab Bạn bè lọc theo hashtag nên trống theo. (Thêm bẫy TTL: `post:*` sống 7 ngày, `feed:*` 30 ngày — quá hạn thì id còn mà payload mất, feed hiện 0 bài.) |

---

## 2. Đã sửa trong code (đi kèm runbook này)

- **Dockerfile**: stage `seed-objects` chạy `generate-seed-objects.py` **ngay trên runner** (đường
  ra các nguồn ảnh sạch) và copy kết quả vào `/app/seed-objects`. Production đọc thẳng từ đó —
  không còn tự tải ~980 ảnh từ VPS.
- **`minio.internal-url`** (`MINIO_INTERNAL_URL`): app gọi MinIO qua mạng nội bộ, không qua Caddy.
  Việc tạo bucket + đặt policy không còn phụ thuộc reverse proxy. URL ký sẵn của sách vẫn ký theo
  `minio.url` công khai (client thứ hai, chỉ để ký).
- **`MinIOBucketInitializer`**: chạy nền, **thử lại 6 lần** cách nhau 5s nếu MinIO chưa sẵn sàng.
- **`MinIOSeedObjectInitializer`**: đọc ảnh nướng sẵn trước → tải mạng → sinh ô màu; timeout tải
  8s → **20s** + thử lại một lần; `minio.seed-objects-replace-placeholders=true` cho phép **ghi đè
  ô màu cũ** bằng ảnh thật (một object nhỏ hơn 2000 byte mà manifest có khai nguồn = ô màu; ảnh
  thật đã lưu luôn ≥ 2000 byte nên không có dương tính giả).
- **`application-prod.yml`**: `neo4j.seed-on-start` nay mặc định `true` (toàn `MERGE`, ~vài giây)
  và `newsfeed.rebuild-if-empty` nay mặc định `true` — chỉ dựng lại bảng tin (nền, không dọn) khi
  `SCAN feed:*` ra đúng 0 khoá. Nghĩa là một deploy sạch không cần bước tay nào cho bảng tin.
  `newsfeed.rebuild-on-start` (đường dev, luôn xoá-rồi-dựng) không đổi.

---

## 3. Cấu hình cần đặt bên `DATN-infra`

Trong `.env` của production và truyền vào service `backend` ở `docker-compose.prod.yml`:

```dotenv
# MỚI — địa chỉ nội bộ, tên service MinIO trong compose (thường là "minio") + cổng API.
MINIO_INTERNAL_URL=http://minio:9000

# Bỏ dấu "/" cuối. Guard trong code đã strip, nhưng sửa cả secret cho sạch.
MINIO_PUBLIC_URL=https://files.elitenexus.id.vn

# Đã là mặc định của profile prod — liệt kê ở đây cho rõ.
MINIO_SEED_OBJECTS_ON_START=true
MINIO_SEED_OBJECTS_REPLACE_PLACEHOLDERS=true
```

Nếu `docker-compose.prod.yml` truyền env vào backend theo danh sách tường minh (không phải
`env_file` toàn bộ), nhớ **thêm `MINIO_INTERNAL_URL` vào danh sách đó**.

---

## 4. Runbook — xoá sạch, làm lại

> Bạn xác nhận xoá được prod DB. Các bước dưới xoá cả DB lẫn object MinIO seed.

### 4.0 — Sao lưu
Theo `rebaseline-seed.sql` STEP 0 (`pg_dump --schema=socialapp`). Giữ file tới khi demo xong.

### 4.1 — Drop schema Postgres
`rebaseline-seed.sql` STEP 1 (kiểm) → STEP 2 (`DROP SCHEMA socialapp CASCADE` trong transaction).

### 4.2 — Xoá object MinIO cũ (ô màu + ảnh dở)
Trên VPS, trong network của compose:

```bash
docker compose -f docker-compose.prod.yml exec -T minio sh -c '
  mc alias set local http://localhost:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" &&
  mc rm --recursive --force --quiet local/profile-pictures local/post-media local/book-covers local/books
'
```

Bucket giữ nguyên, chỉ xoá nội dung. Lần khởi động sau initializer nạp lại từ ảnh nướng sẵn.
(Nếu không chạy được `mc` trong image MinIO thì xoá thẳng thư mục data: `rm -rf` phần
`profile-pictures/ post-media/ book-covers/ books/` dưới bind mount của MinIO — xem
`docker-compose.prod.yml`.)

### 4.3 — Deploy
Push lên `main` (hoặc "Run workflow"). CI giờ có thêm stage `seed-objects` (~+3 phút một lần,
sau đó cache). VPS pull image mới, migrate từ V1 + seed (~5–8 phút, trong ngân sách 900s của
`deploy.yml`).

### 4.4 — Kiểm chứng
```bash
# Ảnh: phải "nướng sẵn" gần hết, "tải mới" và "ô màu" ~0.
docker compose -f docker-compose.prod.yml logs backend | grep "seed-objects-on-start: xong"
# → ... tải lên 1141 (1141 nướng sẵn, 0 tải mới, 0 ô màu; trong đó 0 ghi đè ô màu cũ) ...

# Bucket: phải "world-readable", KHÔNG có "Could not prepare".
docker compose -f docker-compose.prod.yml logs backend | grep -E "world-readable|Could not prepare MinIO"

# HTTP: 200, image/png, > 2 KB (ô màu < 1 KB).
curl -sI https://files.elitenexus.id.vn/profile-pictures/avatars/9001/avatar.png
curl -sI https://files.elitenexus.id.vn/post-media/posts/100001/1.jpg
```

Nếu vẫn còn ô màu ở đâu đó: `MINIO_SEED_OBJECTS_REPLACE_PLACEHOLDERS=true` đã bật sẵn, nên
**restart backend một lần** là initializer thử thay chúng bằng ảnh nướng sẵn:
```bash
docker compose -f docker-compose.prod.yml restart backend
```

### 4.5 — Sau deploy
Theo `rebaseline-seed.sql` STEP 4: đổi mật khẩu 2 admin seed (9499, 9500) qua API; seed Neo4j
(`NEO4J_SEED_ON_START=true` + restart); rebuild newsfeed (`POST /v1/api/admin/newsfeed/rebuild`);
seed Stream chat (`node scripts/seed/seed-stream-chat.mjs --reset`).
