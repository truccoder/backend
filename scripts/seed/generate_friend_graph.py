#!/usr/bin/env python3
"""Sinh đồ thị bạn bè cho bộ seed dev — ra ĐỒNG THỜI hai file, từ một tập cạnh duy nhất.

    src/main/resources/db/seed/V52__seed_social_graph.sql   (Postgres: nhật ký lời mời kết bạn)
    docker/neo4j/seed/friend-graph.cypher                   (Neo4j: quan hệ bạn bè thật)

Vì sao phải sinh tự động thay vì viết tay hai file:

Quan hệ bạn bè được lưu ở HAI nơi với hai vai trò khác nhau. Neo4j giữ cạnh FRIENDS_WITH và là
thứ mà `areFriends`, danh sách bạn bè, gợi ý kết bạn thực sự đọc. Postgres `t_friend_requests`
chỉ là nhật ký lời mời. Hai bên lệch nhau thì không có gì báo lỗi cả — chỉ là hồ sơ hiện "đã là
bạn" trong khi danh sách bạn bè lại không có người đó, hoặc ngược lại. Viết tay 200+ cạnh ở hai
cú pháp khác nhau thì chuyện lệch là chắc chắn xảy ra, nên tập cạnh được định nghĩa đúng một lần
ở đây rồi in ra hai định dạng.

Đầu ra tất định: random.Random(SEED) với hằng số cố định, nên chạy lại cho ra y hệt và `git diff`
sạch nếu không đổi tham số.

    python scripts/seed/generate_friend_graph.py
"""

import random
import sys
from pathlib import Path

SEED = 20260818
ROOT = Path(__file__).resolve().parents[2]

# Phải khớp với dải id và cụm vai trò trong V51__seed_users.sql.
CLUSTERS = {
    "BACKEND":   range(9001, 9011),
    "FRONTEND":  range(9011, 9019),
    "FULLSTACK": range(9019, 9025),
    "MOBILE":    range(9025, 9031),
    "DEVOPS":    range(9031, 9037),
    "DATA_ML":   range(9037, 9043),
    "SECURITY":  range(9043, 9047),
    "QA":        range(9047, 9053),
    "OTHER":     range(9053, 9059),
}
# 9059/9060 là tài khoản ADMIN vận hành — cố ý không có quan hệ bạn bè nào.

ALL_USERS = [uid for members in CLUSTERS.values() for uid in members]

# Xác suất hai người CÙNG cụm là bạn. Cố ý dưới 1.0: nếu cụm nào cũng là mesh đầy đủ thì không
# còn cặp "chưa là bạn nhưng có bạn chung" nào, và API gợi ý kết bạn sẽ không có gì để xếp hạng.
INTRA_CLUSTER_P = 0.55
# Số cạnh bắc sang cụm khác cho mỗi người.
CROSS_EDGES_PER_USER = (1, 3)


def build_edges(rng):
    """Trả về tập cạnh vô hướng, chuẩn hoá thành tuple (nhỏ, lớn)."""
    edges = set()

    for members in CLUSTERS.values():
        members = list(members)

        # 1. Xương sống: nối thành chuỗi để cụm chắc chắn liên thông. Không có bước này, một
        #    người có thể rơi vào trạng thái không bạn bè và biến mất khỏi mọi gợi ý.
        for a, b in zip(members, members[1:]):
            edges.add((a, b))

        # 2. Bù thêm cạnh ngẫu nhiên trong cụm.
        for i, a in enumerate(members):
            for b in members[i + 1:]:
                if rng.random() < INTRA_CLUSTER_P:
                    edges.add((min(a, b), max(a, b)))

    # 3. Cạnh liên cụm — thứ tạo ra "bạn của bạn" xuyên ngành nghề.
    for uid in ALL_USERS:
        own_cluster = next(c for c, m in CLUSTERS.items() if uid in m)
        outsiders = [u for u in ALL_USERS if u not in CLUSTERS[own_cluster]]
        for peer in rng.sample(outsiders, rng.randint(*CROSS_EDGES_PER_USER)):
            edges.add((min(uid, peer), max(uid, peer)))

    return sorted(edges)


def build_non_friend_pairs(rng, edges, count):
    """Các cặp CHƯA là bạn — dùng cho lời mời PENDING/REJECTED/CANCELLED và cho chặn."""
    edge_set = set(edges)
    pairs = set()
    guard = 0
    while len(pairs) < count and guard < count * 200:
        guard += 1
        a, b = rng.sample(ALL_USERS, 2)
        pair = (min(a, b), max(a, b))
        if pair not in edge_set:
            pairs.add(pair)
    return sorted(pairs)


def write_sql(edges, pending, rejected, cancelled, blocks):
    lines = [
        "-- =============================================================================================",
        "-- Nhật ký lời mời kết bạn + danh sách chặn.",
        "--",
        "-- SINH TỰ ĐỘNG bởi scripts/seed/generate_friend_graph.py — đừng sửa tay.",
        "-- Sửa tham số trong script rồi chạy lại; script ghi đè cả file này lẫn",
        "-- docker/neo4j/seed/friend-graph.cypher để hai bên không bao giờ lệch nhau.",
        "--",
        "-- LƯU Ý QUAN TRỌNG: bảng này KHÔNG phải nơi app quyết định hai người có phải bạn hay không.",
        "-- Nguồn sự thật là cạnh FRIENDS_WITH trong Neo4j (FriendshipRepository). Bảng này chỉ là",
        "-- nhật ký lời mời, phục vụ màn hình 'lời mời đã gửi / đã nhận'. Nạp file SQL này mà quên nạp",
        "-- file cypher tương ứng thì danh sách bạn bè sẽ rỗng dù lịch sử lời mời đầy đủ:",
        "--",
        "--   docker exec -i neo4j cypher-shell -u neo4j -p <mật-khẩu> \\",
        "--     < docker/neo4j/seed/friend-graph.cypher",
        "-- =============================================================================================",
        "",
        f"-- {len(edges)} quan hệ đã thành bạn (ACCEPTED), khớp 1-1 với cạnh FRIENDS_WITH trong Neo4j.",
        "INSERT INTO socialapp.t_friend_requests (requester_id, addressee_id, status, created_at, updated_at) VALUES",
    ]

    def rows(pairs, status, swap=False):
        out = []
        for a, b in pairs:
            req, addr = (b, a) if swap else (a, b)
            out.append(
                f"    ({req}, {addr}, '{status}', now() - INTERVAL '{(req % 90) + 3} days',"
                f" now() - INTERVAL '{(req % 45) + 1} days')"
            )
        return out

    lines.append(",\n".join(rows(edges, "ACCEPTED")) + ";")
    lines += [
        "",
        f"-- {len(pending)} lời mời đang chờ. Chỉ số uq_friend_requests_pending_pair (V33) bắt buộc mỗi",
        "-- cặp chỉ có tối đa MỘT dòng PENDING, nên các cặp dưới đây đôi một khác nhau.",
        "INSERT INTO socialapp.t_friend_requests (requester_id, addressee_id, status, created_at, updated_at) VALUES",
    ]
    lines.append(",\n".join(rows(pending, "PENDING")) + ";")
    lines += [
        "",
        f"-- {len(rejected)} lời mời bị từ chối và {len(cancelled)} lời mời người gửi tự huỷ.",
        "-- Cả hai trạng thái này không bị ràng buộc bởi chỉ số partial ở trên.",
        "INSERT INTO socialapp.t_friend_requests (requester_id, addressee_id, status, created_at, updated_at) VALUES",
    ]
    lines.append(",\n".join(rows(rejected, "REJECTED") + rows(cancelled, "CANCELLED", swap=True)) + ";")
    lines += [
        "",
        "-- Danh sách chặn. Cố ý chọn toàn cặp CHƯA là bạn: chặn một người đang là bạn là trạng thái",
        "-- mâu thuẫn mà luồng chặn thật của app không tạo ra được (chặn sẽ gỡ luôn quan hệ bạn bè).",
        "-- CHECK (blocker_id <> blocked_id) từ V46 loại sẵn trường hợp tự chặn mình.",
        "INSERT INTO socialapp.t_user_blocks (blocker_id, blocked_id, created_at) VALUES",
    ]
    lines.append(
        ",\n".join(
            f"    ({a}, {b}, now() - INTERVAL '{(a % 30) + 1} days')" for a, b in blocks
        )
        + ";"
    )
    lines.append("")

    path = ROOT / "src/main/resources/db/seed/V52__seed_social_graph.sql"
    path.write_text("\n".join(lines), encoding="utf-8")
    return path, len(edges)


def write_cypher(edges):
    lines = [
        "// ============================================================================================",
        "// Đồ thị bạn bè cho 58 tài khoản dev (userId 9001-9058) sinh bởi V51__seed_users.sql.",
        "//",
        "// SINH TỰ ĐỘNG bởi scripts/seed/generate_friend_graph.py — đừng sửa tay.",
        "//",
        "// Flyway chỉ quản Postgres, nên phần đồ thị này phải nạp riêng. Cạnh ở đây khớp 1-1 với các",
        "// dòng ACCEPTED trong V52__seed_social_graph.sql; hai file luôn được sinh cùng một lượt.",
        "//",
        "//   docker exec -i neo4j cypher-shell -u neo4j -p <mật-khẩu> \\",
        "//     < docker/neo4j/seed/friend-graph.cypher",
        "//",
        "// Toàn bộ là MERGE nên chạy lại nhiều lần vẫn an toàn.",
        "//",
        "// Cụm vai trò (khớp V51):",
    ]
    for name, members in CLUSTERS.items():
        members = list(members)
        lines.append(f"//   {name:<10} {members[0]}-{members[-1]}")
    lines += [
        "//",
        "// Mật độ trong cụm cố ý DƯỚI mức mesh đầy đủ: phải còn những cặp chưa là bạn nhưng có bạn",
        "// chung thì GET /v1/api/friendships/suggestions mới có dữ liệu để xếp hạng.",
        "// ============================================================================================",
        "",
        "// Xoá đồ thị seed cũ trước khi dựng lại, nếu không các cạnh của thế hệ seed trước sẽ nằm lẫn",
        "// vào và danh sách bạn bè không còn khớp với t_friend_requests bên Postgres.",
        "MATCH (u:User) WHERE u.userId >= 9001 AND u.userId <= 9099 DETACH DELETE u;",
        "",
        "UNWIND range(9001, 9058) AS uid",
        "MERGE (:User {userId: uid});",
        "",
    ]

    by_cluster = {}
    for a, b in edges:
        ca = next(c for c, m in CLUSTERS.items() if a in m)
        cb = next(c for c, m in CLUSTERS.items() if b in m)
        key = ca if ca == cb else "CROSS-CLUSTER"
        by_cluster.setdefault(key, []).append((a, b))

    for name in list(CLUSTERS) + ["CROSS-CLUSTER"]:
        pairs = by_cluster.get(name)
        if not pairs:
            continue
        lines.append(f"// --- {name} ({len(pairs)} cạnh) ---")
        for a, b in pairs:
            lines.append(
                f"MATCH (a:User {{userId: {a}}}), (b:User {{userId: {b}}}) "
                f"MERGE (a)-[:FRIENDS_WITH]-(b);"
            )
        lines.append("")

    path = ROOT / "docker/neo4j/seed/friend-graph.cypher"
    path.write_text("\n".join(lines), encoding="utf-8")
    return path


def main():
    # Console mặc định của Windows là cp1252, không in được tiếng Việt và sẽ ném
    # UnicodeEncodeError giữa chừng — sau khi file đã ghi xong, nên trông như script hỏng
    # trong khi thực ra đã chạy đủ. Ép stdout về UTF-8 để thông báo kết quả hiện đúng.
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")

    rng = random.Random(SEED)
    edges = build_edges(rng)

    others = build_non_friend_pairs(rng, edges, 60)
    pending, rejected, cancelled, blocks = (
        others[:26], others[26:38], others[38:46], others[46:54],
    )

    sql_path, edge_count = write_sql(edges, pending, rejected, cancelled, blocks)
    cypher_path = write_cypher(edges)

    degrees = {u: 0 for u in ALL_USERS}
    for a, b in edges:
        degrees[a] += 1
        degrees[b] += 1

    print(f"{edge_count} cạnh bạn bè, {len(ALL_USERS)} người")
    print(f"bậc: nhỏ nhất={min(degrees.values())} lớn nhất={max(degrees.values())} "
          f"trung bình={sum(degrees.values()) / len(degrees):.1f}")
    print(f"PENDING={len(pending)} REJECTED={len(rejected)} CANCELLED={len(cancelled)} "
          f"blocks={len(blocks)}")
    print(f"đã ghi {sql_path.relative_to(ROOT)}")
    print(f"đã ghi {cypher_path.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
