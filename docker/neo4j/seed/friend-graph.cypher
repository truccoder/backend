// ============================================================================================
// Đồ thị bạn bè cho 58 tài khoản dev (userId 9001-9058) sinh bởi V51__seed_users.sql.
//
// SINH TỰ ĐỘNG bởi scripts/seed/generate_friend_graph.py — đừng sửa tay.
//
// Flyway chỉ quản Postgres, nên phần đồ thị này phải nạp riêng. Cạnh ở đây khớp 1-1 với các
// dòng ACCEPTED trong V52__seed_social_graph.sql; hai file luôn được sinh cùng một lượt.
//
//   docker exec -i neo4j cypher-shell -u neo4j -p <mật-khẩu> \
//     < docker/neo4j/seed/friend-graph.cypher
//
// Toàn bộ là MERGE nên chạy lại nhiều lần vẫn an toàn.
//
// Cụm vai trò (khớp V51):
//   BACKEND    9001-9010
//   FRONTEND   9011-9018
//   FULLSTACK  9019-9024
//   MOBILE     9025-9030
//   DEVOPS     9031-9036
//   DATA_ML    9037-9042
//   SECURITY   9043-9046
//   QA         9047-9052
//   OTHER      9053-9058
//
// Mật độ trong cụm cố ý DƯỚI mức mesh đầy đủ: phải còn những cặp chưa là bạn nhưng có bạn
// chung thì GET /v1/api/friendships/suggestions mới có dữ liệu để xếp hạng.
// ============================================================================================

// Xoá đồ thị seed cũ trước khi dựng lại, nếu không các cạnh của thế hệ seed trước sẽ nằm lẫn
// vào và danh sách bạn bè không còn khớp với t_friend_requests bên Postgres.
MATCH (u:User) WHERE u.userId >= 9001 AND u.userId <= 9099 DETACH DELETE u;

UNWIND range(9001, 9058) AS uid
MERGE (:User {userId: uid});

// --- BACKEND (30 cạnh) ---
MATCH (a:User {userId: 9001}), (b:User {userId: 9002}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9001}), (b:User {userId: 9005}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9001}), (b:User {userId: 9006}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9001}), (b:User {userId: 9007}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9001}), (b:User {userId: 9009}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9002}), (b:User {userId: 9003}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9002}), (b:User {userId: 9006}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9002}), (b:User {userId: 9007}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9002}), (b:User {userId: 9008}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9002}), (b:User {userId: 9009}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9003}), (b:User {userId: 9004}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9003}), (b:User {userId: 9008}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9004}), (b:User {userId: 9005}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9004}), (b:User {userId: 9006}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9004}), (b:User {userId: 9008}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9004}), (b:User {userId: 9009}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9004}), (b:User {userId: 9010}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9005}), (b:User {userId: 9006}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9005}), (b:User {userId: 9007}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9005}), (b:User {userId: 9009}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9005}), (b:User {userId: 9010}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9006}), (b:User {userId: 9007}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9006}), (b:User {userId: 9008}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9006}), (b:User {userId: 9010}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9007}), (b:User {userId: 9008}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9007}), (b:User {userId: 9009}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9007}), (b:User {userId: 9010}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9008}), (b:User {userId: 9009}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9008}), (b:User {userId: 9010}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9009}), (b:User {userId: 9010}) MERGE (a)-[:FRIENDS_WITH]-(b);

// --- FRONTEND (18 cạnh) ---
MATCH (a:User {userId: 9011}), (b:User {userId: 9012}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9011}), (b:User {userId: 9014}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9011}), (b:User {userId: 9015}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9011}), (b:User {userId: 9016}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9011}), (b:User {userId: 9018}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9012}), (b:User {userId: 9013}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9013}), (b:User {userId: 9014}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9013}), (b:User {userId: 9015}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9013}), (b:User {userId: 9016}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9013}), (b:User {userId: 9017}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9014}), (b:User {userId: 9015}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9014}), (b:User {userId: 9017}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9015}), (b:User {userId: 9016}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9015}), (b:User {userId: 9017}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9015}), (b:User {userId: 9018}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9016}), (b:User {userId: 9017}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9016}), (b:User {userId: 9018}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9017}), (b:User {userId: 9018}) MERGE (a)-[:FRIENDS_WITH]-(b);

// --- FULLSTACK (13 cạnh) ---
MATCH (a:User {userId: 9019}), (b:User {userId: 9020}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9019}), (b:User {userId: 9021}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9019}), (b:User {userId: 9022}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9019}), (b:User {userId: 9023}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9019}), (b:User {userId: 9024}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9020}), (b:User {userId: 9021}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9020}), (b:User {userId: 9022}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9021}), (b:User {userId: 9022}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9021}), (b:User {userId: 9023}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9021}), (b:User {userId: 9024}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9022}), (b:User {userId: 9023}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9022}), (b:User {userId: 9024}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9023}), (b:User {userId: 9024}) MERGE (a)-[:FRIENDS_WITH]-(b);

// --- MOBILE (12 cạnh) ---
MATCH (a:User {userId: 9025}), (b:User {userId: 9026}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9025}), (b:User {userId: 9027}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9025}), (b:User {userId: 9028}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9025}), (b:User {userId: 9029}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9025}), (b:User {userId: 9030}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9026}), (b:User {userId: 9027}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9026}), (b:User {userId: 9030}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9027}), (b:User {userId: 9028}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9027}), (b:User {userId: 9029}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9028}), (b:User {userId: 9029}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9028}), (b:User {userId: 9030}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9029}), (b:User {userId: 9030}) MERGE (a)-[:FRIENDS_WITH]-(b);

// --- DEVOPS (10 cạnh) ---
MATCH (a:User {userId: 9031}), (b:User {userId: 9032}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9031}), (b:User {userId: 9033}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9031}), (b:User {userId: 9036}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9032}), (b:User {userId: 9033}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9032}), (b:User {userId: 9036}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9033}), (b:User {userId: 9034}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9033}), (b:User {userId: 9035}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9034}), (b:User {userId: 9035}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9034}), (b:User {userId: 9036}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9035}), (b:User {userId: 9036}) MERGE (a)-[:FRIENDS_WITH]-(b);

// --- DATA_ML (11 cạnh) ---
MATCH (a:User {userId: 9037}), (b:User {userId: 9038}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9037}), (b:User {userId: 9040}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9037}), (b:User {userId: 9041}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9038}), (b:User {userId: 9039}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9038}), (b:User {userId: 9040}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9038}), (b:User {userId: 9041}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9038}), (b:User {userId: 9042}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9039}), (b:User {userId: 9040}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9039}), (b:User {userId: 9041}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9040}), (b:User {userId: 9041}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9041}), (b:User {userId: 9042}) MERGE (a)-[:FRIENDS_WITH]-(b);

// --- SECURITY (5 cạnh) ---
MATCH (a:User {userId: 9043}), (b:User {userId: 9044}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9043}), (b:User {userId: 9045}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9044}), (b:User {userId: 9045}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9044}), (b:User {userId: 9046}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9045}), (b:User {userId: 9046}) MERGE (a)-[:FRIENDS_WITH]-(b);

// --- QA (12 cạnh) ---
MATCH (a:User {userId: 9047}), (b:User {userId: 9048}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9047}), (b:User {userId: 9051}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9047}), (b:User {userId: 9052}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9048}), (b:User {userId: 9049}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9048}), (b:User {userId: 9051}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9048}), (b:User {userId: 9052}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9049}), (b:User {userId: 9050}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9049}), (b:User {userId: 9051}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9049}), (b:User {userId: 9052}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9050}), (b:User {userId: 9051}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9050}), (b:User {userId: 9052}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9051}), (b:User {userId: 9052}) MERGE (a)-[:FRIENDS_WITH]-(b);

// --- OTHER (13 cạnh) ---
MATCH (a:User {userId: 9053}), (b:User {userId: 9054}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9053}), (b:User {userId: 9055}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9053}), (b:User {userId: 9056}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9053}), (b:User {userId: 9057}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9054}), (b:User {userId: 9055}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9054}), (b:User {userId: 9056}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9054}), (b:User {userId: 9057}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9054}), (b:User {userId: 9058}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9055}), (b:User {userId: 9056}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9055}), (b:User {userId: 9057}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9056}), (b:User {userId: 9057}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9056}), (b:User {userId: 9058}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9057}), (b:User {userId: 9058}) MERGE (a)-[:FRIENDS_WITH]-(b);

// --- CROSS-CLUSTER (116 cạnh) ---
MATCH (a:User {userId: 9001}), (b:User {userId: 9019}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9001}), (b:User {userId: 9029}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9001}), (b:User {userId: 9058}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9002}), (b:User {userId: 9021}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9002}), (b:User {userId: 9028}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9002}), (b:User {userId: 9046}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9003}), (b:User {userId: 9023}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9003}), (b:User {userId: 9031}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9003}), (b:User {userId: 9032}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9003}), (b:User {userId: 9034}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9003}), (b:User {userId: 9035}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9003}), (b:User {userId: 9057}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9004}), (b:User {userId: 9017}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9004}), (b:User {userId: 9033}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9004}), (b:User {userId: 9047}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9004}), (b:User {userId: 9058}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9005}), (b:User {userId: 9021}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9005}), (b:User {userId: 9022}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9005}), (b:User {userId: 9031}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9005}), (b:User {userId: 9050}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9006}), (b:User {userId: 9014}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9006}), (b:User {userId: 9037}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9006}), (b:User {userId: 9045}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9006}), (b:User {userId: 9047}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9006}), (b:User {userId: 9050}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9007}), (b:User {userId: 9014}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9007}), (b:User {userId: 9018}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9007}), (b:User {userId: 9028}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9007}), (b:User {userId: 9035}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9008}), (b:User {userId: 9011}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9008}), (b:User {userId: 9012}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9008}), (b:User {userId: 9022}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9008}), (b:User {userId: 9024}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9008}), (b:User {userId: 9039}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9008}), (b:User {userId: 9045}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9009}), (b:User {userId: 9040}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9009}), (b:User {userId: 9041}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9009}), (b:User {userId: 9057}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9010}), (b:User {userId: 9012}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9010}), (b:User {userId: 9015}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9010}), (b:User {userId: 9017}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9011}), (b:User {userId: 9019}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9011}), (b:User {userId: 9026}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9012}), (b:User {userId: 9028}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9012}), (b:User {userId: 9034}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9012}), (b:User {userId: 9049}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9013}), (b:User {userId: 9038}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9013}), (b:User {userId: 9040}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9014}), (b:User {userId: 9031}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9014}), (b:User {userId: 9033}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9014}), (b:User {userId: 9041}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9014}), (b:User {userId: 9045}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9014}), (b:User {userId: 9050}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9015}), (b:User {userId: 9020}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9015}), (b:User {userId: 9049}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9015}), (b:User {userId: 9057}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9016}), (b:User {userId: 9025}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9016}), (b:User {userId: 9054}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9016}), (b:User {userId: 9055}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9017}), (b:User {userId: 9021}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9017}), (b:User {userId: 9047}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9017}), (b:User {userId: 9050}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9018}), (b:User {userId: 9020}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9018}), (b:User {userId: 9034}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9018}), (b:User {userId: 9046}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9018}), (b:User {userId: 9052}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9018}), (b:User {userId: 9054}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9019}), (b:User {userId: 9030}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9020}), (b:User {userId: 9030}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9020}), (b:User {userId: 9037}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9020}), (b:User {userId: 9038}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9020}), (b:User {userId: 9043}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9021}), (b:User {userId: 9042}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9021}), (b:User {userId: 9050}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9022}), (b:User {userId: 9036}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9022}), (b:User {userId: 9051}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9023}), (b:User {userId: 9031}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9023}), (b:User {userId: 9032}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9023}), (b:User {userId: 9045}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9023}), (b:User {userId: 9048}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9023}), (b:User {userId: 9055}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9024}), (b:User {userId: 9027}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9025}), (b:User {userId: 9042}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9026}), (b:User {userId: 9031}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9026}), (b:User {userId: 9035}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9026}), (b:User {userId: 9043}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9027}), (b:User {userId: 9044}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9027}), (b:User {userId: 9052}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9028}), (b:User {userId: 9033}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9028}), (b:User {userId: 9040}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9029}), (b:User {userId: 9046}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9030}), (b:User {userId: 9033}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9030}), (b:User {userId: 9043}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9031}), (b:User {userId: 9049}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9032}), (b:User {userId: 9053}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9033}), (b:User {userId: 9039}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9034}), (b:User {userId: 9051}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9036}), (b:User {userId: 9056}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9037}), (b:User {userId: 9043}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9037}), (b:User {userId: 9051}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9037}), (b:User {userId: 9054}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9038}), (b:User {userId: 9047}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9038}), (b:User {userId: 9058}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9039}), (b:User {userId: 9048}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9039}), (b:User {userId: 9051}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9039}), (b:User {userId: 9056}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9041}), (b:User {userId: 9045}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9041}), (b:User {userId: 9055}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9042}), (b:User {userId: 9043}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9042}), (b:User {userId: 9045}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9042}), (b:User {userId: 9054}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9043}), (b:User {userId: 9052}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9044}), (b:User {userId: 9051}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9046}), (b:User {userId: 9052}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9046}), (b:User {userId: 9055}) MERGE (a)-[:FRIENDS_WITH]-(b);
MATCH (a:User {userId: 9050}), (b:User {userId: 9058}) MERGE (a)-[:FRIENDS_WITH]-(b);
