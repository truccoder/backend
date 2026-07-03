# Kịch bản test module Friendships

Base URL: `http://localhost:8080`

## 1. Chuẩn bị

Migration [`V20__seed_test_users.sql`](../../src/main/resources/db/migration/V20__seed_test_users.sql) seed sẵn 5 user, mật khẩu đều là `12345678`:

| email | username | id (dự kiến trên DB sạch) |
|---|---|---|
| alice@test.com | alice | 1 |
| bob@test.com | bob | 2 |
| carol@test.com | carol | 3 |
| david@test.com | david | 4 |
| eve@test.com | eve | 5 |

ID chỉ đúng nếu đây là 5 dòng đầu tiên trong `t_users`. Nếu DB đã có data trước đó, tra lại ID thật:

```bash
docker exec -it postgres psql -U postgres -d socialapp -c "SELECT id, email FROM socialapp.t_users ORDER BY id;"
```

## 2. Giới hạn cần biết trước khi test

Module `friendships` ([FriendshipController.java](../../src/main/java/com/socialapp/friendships/controller/FriendshipController.java)) hiện **không có endpoint GET nào** — không xem được danh sách bạn bè, danh sách lời mời đang chờ, hay ID của friend request vừa tạo. Mọi response của `send/cancel/accept/reject` đều là `200 OK` rỗng (void).

Để lấy `requestId` dùng cho accept/reject/cancel, tra trực tiếp trong Postgres:

```bash
docker exec -it postgres psql -U postgres -d socialapp -c "SELECT id, requester_id, addressee_id, status FROM socialapp.t_friend_requests ORDER BY id DESC LIMIT 5;"
```

Quan hệ bạn bè sau khi accept được lưu trong Neo4j (không có API đọc), có thể kiểm tra qua Neo4j Browser tại `http://localhost:7474` (user `neo4j` / pass `neo4j_password`):

```cypher
MATCH (u1:User {userId: 1})-[:FRIENDS_WITH]-(u2:User) RETURN u1, u2
```

## 3. Đăng nhập lấy token

```bash
curl -X POST http://localhost:8080/v1/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"alice@test.com","password":"12345678"}'
```

```bash
curl -X POST http://localhost:8080/v1/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"bob@test.com","password":"12345678"}'
```

Lưu `accessToken` trả về, dùng cho header `Authorization: Bearer <token>` ở các bước dưới. Trong các lệnh mẫu, thay `$ALICE_TOKEN`, `$BOB_TOKEN`, ... bằng token thật (hoặc set biến môi trường / Postman environment variable).

---

## HAPPY PATH

### Kịch bản A — Gửi lời mời, addressee accept → thành bạn

1. Alice (id=1) gửi lời mời kết bạn tới Bob (id=2):

```bash
curl -X POST http://localhost:8080/v1/api/friendships/requests/2 \
  -H "Authorization: Bearer $ALICE_TOKEN"
```
Kỳ vọng: `200 OK`, body rỗng.

2. Tra `requestId` vừa tạo:

```bash
docker exec -it postgres psql -U postgres -d socialapp -c "SELECT id, requester_id, addressee_id, status FROM socialapp.t_friend_requests WHERE requester_id=1 AND addressee_id=2 ORDER BY id DESC LIMIT 1;"
```

3. Bob (addressee) accept request (giả sử `requestId=1`):

```bash
curl -X POST http://localhost:8080/v1/api/friendships/requests/1/accept \
  -H "Authorization: Bearer $BOB_TOKEN"
```
Kỳ vọng: `200 OK`. Sau bước này, `t_friend_requests.status` chuyển `ACCEPTED` và quan hệ `FRIENDS_WITH` được tạo trong Neo4j giữa userId 1 và 2.

4. Verify lại trạng thái request:

```bash
docker exec -it postgres psql -U postgres -d socialapp -c "SELECT status FROM socialapp.t_friend_requests WHERE id=1;"
```
Kỳ vọng: `ACCEPTED`.

### Kịch bản B — Gửi lời mời, addressee reject

1. Carol (id=3) gửi lời mời tới David (id=4):

```bash
curl -X POST http://localhost:8080/v1/api/friendships/requests/4 \
  -H "Authorization: Bearer $CAROL_TOKEN"
```

2. Tra `requestId`, sau đó David reject (giả sử `requestId=2`):

```bash
curl -X POST http://localhost:8080/v1/api/friendships/requests/2/reject \
  -H "Authorization: Bearer $DAVID_TOKEN"
```
Kỳ vọng: `200 OK`, status chuyển `REJECTED`, không tạo quan hệ trong Neo4j.

### Kịch bản C — Requester tự huỷ lời mời đã gửi

1. Eve (id=5) gửi lời mời tới Alice (id=1):

```bash
curl -X POST http://localhost:8080/v1/api/friendships/requests/1 \
  -H "Authorization: Bearer $EVE_TOKEN"
```

2. Tra `requestId`, Eve tự huỷ (giả sử `requestId=3`):

```bash
curl -X DELETE http://localhost:8080/v1/api/friendships/requests/3 \
  -H "Authorization: Bearer $EVE_TOKEN"
```
Kỳ vọng: `200 OK`, status chuyển `CANCELLED`.

---

## EDGE CASES / NEGATIVE PATH

### Không có token → 401

```bash
curl -i -X POST http://localhost:8080/v1/api/friendships/requests/2
```
Kỳ vọng: `401 Unauthorized`.

### Tự gửi lời mời cho chính mình → 400

```bash
curl -i -X POST http://localhost:8080/v1/api/friendships/requests/1 \
  -H "Authorization: Bearer $ALICE_TOKEN"
```
Kỳ vọng: `400 Bad Request`, message `"You cannot send a friend request to yourself"`.

### Gửi lời mời tới user không tồn tại → 404

```bash
curl -i -X POST http://localhost:8080/v1/api/friendships/requests/9999 \
  -H "Authorization: Bearer $ALICE_TOKEN"
```
Kỳ vọng: `404 Not Found`.

### Gửi lời mời trùng khi đã có pending request → 400

Gửi lại request A bước 1 (Alice → Bob) lần thứ 2:

```bash
curl -i -X POST http://localhost:8080/v1/api/friendships/requests/2 \
  -H "Authorization: Bearer $ALICE_TOKEN"
```
Kỳ vọng: `400 Bad Request`, message `"A pending friend request already exists between these users"`.

### Gửi lời mời khi đã là bạn → 400

Sau khi kịch bản A hoàn tất (Alice & Bob đã là bạn), Alice gửi lại lời mời cho Bob:
```bash
curl -i -X POST http://localhost:8080/v1/api/friendships/requests/2 \
  -H "Authorization: Bearer $ALICE_TOKEN"
```
Kỳ vọng: `400 Bad Request`, message `"You are already friends with this user"`.

### Người không phải addressee cố accept → 403

Alice (requester, không phải addressee) cố accept chính request cô vừa gửi:
```bash
curl -i -X POST http://localhost:8080/v1/api/friendships/requests/1/accept \
  -H "Authorization: Bearer $ALICE_TOKEN"
```
Kỳ vọng: `403 Forbidden`, message `"Only the addressee can accept this friend request"`.

### Người không phải requester cố cancel → 403

Bob (addressee, không phải requester) cố cancel request của Alice:
```bash
curl -i -X DELETE http://localhost:8080/v1/api/friendships/requests/1 \
  -H "Authorization: Bearer $BOB_TOKEN"
```
Kỳ vọng: `403 Forbidden`, message `"Only the requester can cancel this friend request"`.

### Accept/reject request đã xử lý xong (không còn PENDING) → 400

Accept lại request đã `ACCEPTED` ở kịch bản A:
```bash
curl -i -X POST http://localhost:8080/v1/api/friendships/requests/1/accept \
  -H "Authorization: Bearer $BOB_TOKEN"
```
Kỳ vọng: `400 Bad Request`, message chứa `"Friend request is no longer pending"`.

### RequestId không tồn tại → 404

```bash
curl -i -X POST http://localhost:8080/v1/api/friendships/requests/9999/accept \
  -H "Authorization: Bearer $ALICE_TOKEN"
```
Kỳ vọng: `404 Not Found`.
