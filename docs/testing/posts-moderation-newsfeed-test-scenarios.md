# Kịch bản test module Posts, Moderation, Newsfeed

Base URL: `http://localhost:8080`

## 0. Lưu ý quan trọng — đọc trước khi test

1. **Moderation mặc định BẬT** (`moderation.enabled: true` trong `application.yml`). Mọi post có `content` sẽ được chấm điểm qua Google Perspective API (text) và Cloud Vision API (ảnh).
2. Local dev **không có API key thật** (`PERSPECTIVE_API_KEY`, `GOOGLE_APPLICATION_CREDENTIALS` đều rỗng) → lời gọi ra Google sẽ lỗi (invalid key) → code fallback về score mặc định `toxicity=0.5` ([TextModerationService.java:98-106](../../src/main/java/com/socialapp/moderation/ai/TextModerationService.java#L98-L106)). Vì `reviewThreshold` mặc định = `0.5` ([ModerationProperties.java:22](../../src/main/java/com/socialapp/moderation/config/ModerationProperties.java#L22)), **mọi post có nội dung text sẽ luôn rơi vào `PENDING_REVIEW`**, không tự động `APPROVED`. Post ở trạng thái này **không** được fan-out vào newsfeed.
3. Việc chấm điểm chạy **bất đồng bộ** (`@Async`) và gọi API thật ra Internet nên có độ trễ vài giây (nếu máy có mạng) hoặc lâu hơn (nếu không có mạng, phải đợi timeout). Nên đợi 5-10s sau khi tạo post rồi mới kiểm tra trạng thái/duyệt bài.
4. `AdminModerationController` (`/v1/api/admin/moderation/**`) **không kiểm tra role admin** — bất kỳ user đã login nào cũng gọi được. Đây là một vấn đề phân quyền đáng lưu ý riêng, không phải trọng tâm của bộ test này, nhưng nghĩa là bạn có thể dùng token của Alice hoặc Bob để duyệt bài mà không cần tài khoản admin.
5. `PostController`/`CommentController` không có endpoint GET nào để đọc lại post/comment — muốn lấy `postId`/`commentId` phải tra trực tiếp Postgres (lệnh cho ở từng bước).
6. Muốn bỏ qua toàn bộ bước duyệt bài để test nhanh happy-path Post → Newsfeed: sửa `moderation.enabled: false` trong [application.yml](../../src/main/resources/application.yml) rồi restart app — post sẽ `APPROVED` và fan-out ngay lập tức, không cần bước "Duyệt bài" ở Happy Path 1 bên dưới.

## 1. Chuẩn bị

- Dùng user Alice (id=1) và Bob (id=2), **đã là bạn bè** (chạy xong Kịch bản A trong [friendships-test-scenarios.md](friendships-test-scenarios.md)) — newsfeed chỉ fan-out bài `PUBLIC`/`FRIENDS` cho tác giả + bạn bè + user được tag ([NewsfeedService.java:85-90](../../src/main/java/com/socialapp/newsfeed/service/NewsfeedService.java#L85-L90)), nên bắt buộc phải kết bạn trước khi kiểm tra "bạn thấy bài viết trên feed của bạn mình".
- Login lấy `$ALICE_TOKEN`, `$BOB_TOKEN` (xem phần 3 trong friendships-test-scenarios.md).

---

## HAPPY PATH

### Happy Path 1 — Tạo post → chờ duyệt → xuất hiện trên newsfeed của bạn bè

**Bước 1.** Alice tạo 1 post PUBLIC, không ảnh:

```bash
curl -i -X POST http://localhost:8080/v1/api/posts \
  -H "Authorization: Bearer $ALICE_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "content": "Hello from Alice, first post!",
    "visibility": "PUBLIC",
    "postType": "REGULAR"
  }'
```
Kỳ vọng: `200 OK`, body rỗng.

**Bước 2.** Tra `postId` vừa tạo và trạng thái moderation (đợi vài giây trước khi chạy):

```bash
docker exec -it postgres psql -U postgres -d socialapp -c "SELECT id, author_id, content, moderation_status FROM socialapp.t_posts ORDER BY id DESC LIMIT 1;"
```
Kỳ vọng ban đầu: `moderation_status = PENDING_MODERATION`, sau vài giây chuyển thành `PENDING_REVIEW` (theo lưu ý mục 0.2).

**Bước 3.** Xem danh sách post đang chờ duyệt (dùng token bất kỳ user nào đã login):

```bash
curl -X GET http://localhost:8080/v1/api/admin/moderation/pending \
  -H "Authorization: Bearer $ALICE_TOKEN"
```
Kỳ vọng: `200 OK`, mảng chứa post vừa tạo với `currentStatus: PENDING_REVIEW`.

**Bước 4.** Duyệt (approve) bài — `decision` phải nhỏ hơn `LIKELY` (dùng `VERY_UNLIKELY` hoặc `UNLIKELY`) để được approve (giả sử `postId=1`):

```bash
curl -i -X POST http://localhost:8080/v1/api/admin/moderation/posts/1/review \
  -H "Authorization: Bearer $ALICE_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"decision":"VERY_UNLIKELY","feedback":"looks fine"}'
```
Kỳ vọng: `200 OK`. `t_posts.moderation_status` chuyển thành `APPROVED`, post được fan-out vào Redis feed của Alice + Bob (bạn bè).

**Bước 5.** Bob xem newsfeed, kỳ vọng thấy post của Alice:

```bash
curl -X GET "http://localhost:8080/v1/api/feed?page=1&size=10" \
  -H "Authorization: Bearer $BOB_TOKEN"
```
Kỳ vọng: `200 OK`, `posts` chứa post vừa duyệt (`postId=1`, `authorFullName: "Alice Nguyen"`).

**Bước 6.** Alice cũng thấy post của chính mình trên feed (tác giả luôn có post của mình trong feed):

```bash
curl -X GET "http://localhost:8080/v1/api/feed?page=1&size=10" \
  -H "Authorization: Bearer $ALICE_TOKEN"
```

### Happy Path 2 — Comment vào post

Comment không phụ thuộc trạng thái moderation của post (chỉ cần post tồn tại), nên có thể test song song, không cần đợi bước duyệt ở trên.

**Bob comment vào post của Alice** (`postId=1`):

```bash
curl -i -X POST http://localhost:8080/v1/api/posts/1/comments \
  -H "Authorization: Bearer $BOB_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"content": "Nice post!"}'
```
Kỳ vọng: `200 OK`.

Tra `commentId` vừa tạo:
```bash
docker exec -it postgres psql -U postgres -d socialapp -c "SELECT id, post_id, author_id, content, parent_id FROM socialapp.t_comments ORDER BY id DESC LIMIT 1;"
```

**Alice reply vào comment của Bob** (giả sử `commentId=1`):
```bash
curl -i -X POST http://localhost:8080/v1/api/posts/1/comments \
  -H "Authorization: Bearer $ALICE_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"content": "Thanks Bob!", "parentId": 1}'
```

**Bob sửa lại comment của mình:**
```bash
curl -i -X PUT http://localhost:8080/v1/api/posts/1/comments/1 \
  -H "Authorization: Bearer $BOB_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"content": "Nice post, Alice!"}'
```

**Bob xoá comment của mình:**
```bash
curl -i -X DELETE http://localhost:8080/v1/api/posts/1/comments/1 \
  -H "Authorization: Bearer $BOB_TOKEN"
```

### Happy Path 3 — Reaction vào post

**Bob thả reaction LOVE vào post của Alice:**
```bash
curl -i -X PUT http://localhost:8080/v1/api/posts/1/reactions \
  -H "Authorization: Bearer $BOB_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"reactionType": "LOVE"}'
```
Kỳ vọng: `200 OK`.

**Bob đổi reaction sang LIKE (upsert — gọi lại API cũ với type khác):**
```bash
curl -i -X PUT http://localhost:8080/v1/api/posts/1/reactions \
  -H "Authorization: Bearer $BOB_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"reactionType": "LIKE"}'
```

**Bob gỡ reaction:**
```bash
curl -i -X DELETE http://localhost:8080/v1/api/posts/1/reactions \
  -H "Authorization: Bearer $BOB_TOKEN"
```

### Happy Path 4 — Sửa và xoá post

**Alice sửa post của mình** (post sẽ quay lại `PENDING_MODERATION` → cần duyệt lại, và bị gỡ khỏi feed trong lúc chờ duyệt — xem [PostService.java:113-121](../../src/main/java/com/socialapp/posts/service/PostService.java#L113-L121)):
```bash
curl -i -X PUT http://localhost:8080/v1/api/posts/1 \
  -H "Authorization: Bearer $ALICE_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "content": "Hello from Alice, edited!",
    "visibility": "PUBLIC"
  }'
```
Lặp lại bước 2-4 của Happy Path 1 để duyệt lại post đã sửa.

**Alice xoá post:**
```bash
curl -i -X DELETE http://localhost:8080/v1/api/posts/1 \
  -H "Authorization: Bearer $ALICE_TOKEN"
```
Kỳ vọng: `200 OK`, post bị xoá khỏi `t_posts` và khỏi feed của mọi người liên quan.

### Happy Path 5 — Tạo post loại EVENT

```bash
curl -i -X POST http://localhost:8080/v1/api/posts \
  -H "Authorization: Bearer $ALICE_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "content": "Join our meetup!",
    "visibility": "PUBLIC",
    "postType": "EVENT",
    "eventDetails": {
      "eventTitle": "Team Meetup",
      "eventDescription": "Quarterly sync",
      "startTime": "2026-08-01T09:00:00+07:00",
      "endTime": "2026-08-01T11:00:00+07:00",
      "timezone": "Asia/Ho_Chi_Minh",
      "location": "District 1, HCMC",
      "maxAttendees": 20
    }
  }'
```
Kỳ vọng: `200 OK`, giống Happy Path 1, cần duyệt để lên feed.

### Happy Path 6 — Tag bạn bè vào post

Content **bắt buộc** phải có placeholder `@[<vị trí trong mảng taggedUserIds>]` tương ứng ([PostService.java:139-174](../../src/main/java/com/socialapp/posts/service/PostService.java#L139-L174)):

```bash
curl -i -X POST http://localhost:8080/v1/api/posts \
  -H "Authorization: Bearer $ALICE_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "content": "Hanging out with @[0] today!",
    "visibility": "FRIENDS",
    "taggedUserIds": [2]
  }'
```
Kỳ vọng: `200 OK`. Sau khi duyệt, post cũng xuất hiện trên feed của Bob (id=2) dù không phải bạn bè — vì được tag trực tiếp (`fanOutPost` cộng thêm `taggedUserIds`).

---

## EDGE CASES / NEGATIVE PATH

### Không có token → 401
```bash
curl -i -X POST http://localhost:8080/v1/api/posts \
  -H "Content-Type: application/json" \
  -d '{"content":"test","visibility":"PUBLIC"}'
```

### Post PRIVATE mà có tag user → 400
```bash
curl -i -X POST http://localhost:8080/v1/api/posts \
  -H "Authorization: Bearer $ALICE_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"content":"secret @[0]","visibility":"PRIVATE","taggedUserIds":[2]}'
```
Kỳ vọng: `400 Bad Request`, message `"Private posts cannot tag other users"`.

### Tag user nhưng thiếu placeholder trong content → 400
```bash
curl -i -X POST http://localhost:8080/v1/api/posts \
  -H "Authorization: Bearer $ALICE_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"content":"Hanging out today!","visibility":"PUBLIC","taggedUserIds":[2]}'
```
Kỳ vọng: `400 Bad Request`, message chứa `"Missing placeholder @[0]"`.

### Post EVENT thiếu eventDetails → 400
```bash
curl -i -X POST http://localhost:8080/v1/api/posts \
  -H "Authorization: Bearer $ALICE_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"content":"An event","visibility":"PUBLIC","postType":"EVENT"}'
```
Kỳ vọng: `400 Bad Request`, message `"Event details are required for event posts"`.

### Sửa/xoá post của người khác → 403
Bob cố sửa post của Alice (`postId=1`):
```bash
curl -i -X DELETE http://localhost:8080/v1/api/posts/1 \
  -H "Authorization: Bearer $BOB_TOKEN"
```
Kỳ vọng: `403 Forbidden`, message `"Only the author can modify this post"`.

### Post không tồn tại → 404
```bash
curl -i -X DELETE http://localhost:8080/v1/api/posts/9999 \
  -H "Authorization: Bearer $ALICE_TOKEN"
```

### Comment rỗng → 400
```bash
curl -i -X POST http://localhost:8080/v1/api/posts/1/comments \
  -H "Authorization: Bearer $BOB_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"content":""}'
```
Kỳ vọng: `400 Bad Request`, message `"Comment content must not be blank"`.

### Reply vào một reply (nested quá 1 cấp) → 400
Giả sử `commentId=2` là reply (đã có `parentId`), cố reply tiếp vào nó:
```bash
curl -i -X POST http://localhost:8080/v1/api/posts/1/comments \
  -H "Authorization: Bearer $ALICE_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"content":"double reply","parentId":2}'
```
Kỳ vọng: `400 Bad Request`, message `"Replies can only be made to top-level comments"`.

### Reaction thiếu reactionType → 400/422
```bash
curl -i -X PUT http://localhost:8080/v1/api/posts/1/reactions \
  -H "Authorization: Bearer $BOB_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{}'
```
Kỳ vọng: `422 Unprocessable Entity` (bean validation `@NotNull`).

### Gỡ reaction chưa từng thả → 404
```bash
curl -i -X DELETE http://localhost:8080/v1/api/posts/1/reactions \
  -H "Authorization: Bearer $ALICE_TOKEN"
```
Kỳ vọng: `404 Not Found`, message `"Reaction not found for this post"`.

### Nội dung post dính từ khoá bị chặn (keyword blacklist) → 400, không tạo post
Kiểm tra danh sách từ khoá tại [blacklist.txt](../../src/main/resources/moderation/blacklist.txt) rồi tạo post với 1 từ trong đó, ví dụ:
```bash
curl -i -X POST http://localhost:8080/v1/api/posts \
  -H "Authorization: Bearer $ALICE_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"content":"<từ trong blacklist.txt>","visibility":"PUBLIC"}'
```
Kỳ vọng: `400 Bad Request` với `error: "Content Violation"` — post bị chặn ngay ở rule-engine đồng bộ, **không** tạo record trong `t_posts`.

### Duyệt bài không ở trạng thái PENDING_REVIEW → lỗi
Gọi lại review cho post đã `APPROVED` (`postId=1`):
```bash
curl -i -X POST http://localhost:8080/v1/api/admin/moderation/posts/1/review \
  -H "Authorization: Bearer $ALICE_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"decision":"VERY_UNLIKELY"}'
```
Kỳ vọng: `500 Internal Server Error` (`IllegalStateException: "Post is not in PENDING_REVIEW status"` — không có handler riêng cho exception này nên rơi vào handler generic `Exception`).

### Feed page/size không hợp lệ (≤0) → 400/422
```bash
curl -i -X GET "http://localhost:8080/v1/api/feed?page=0&size=10" \
  -H "Authorization: Bearer $ALICE_TOKEN"
```
Kỳ vọng: `400/422` do vi phạm `@Positive`.
