-- Backfills the t_friend_requests audit trail (ACCEPTED) for nguyen.truc@test.com (userId 9001,
-- Backend Developer — see V29). Records nguyen.truc as friends with:
--   - 2 people sharing the same job title (Backend Developer): 9002, 9003
--   - 2 people from every OTHER job title: Frontend (9006, 9007), DevOps (9011, 9012),
--     Data Scientist (9015, 9016), Mobile Developer (9019, 9020), QA Engineer (9023, 9024)
--
-- This table is only a request/audit log — it is NOT what the app checks to decide whether two
-- users are friends (that's the Neo4j graph). The matching FRIENDS_WITH relationships must be
-- created separately via docker/neo4j/seed/friend-graph-nguyen-truc.cypher.
INSERT INTO socialapp.t_friend_requests (requester_id, addressee_id, status)
VALUES (9001, 9002, 'ACCEPTED'), -- Backend Trần Khôi (same job title)
       (9001, 9003, 'ACCEPTED'), -- Backend Phạm Minh (same job title)
       (9001, 9006, 'ACCEPTED'), -- Frontend Huỳnh Lan
       (9001, 9007, 'ACCEPTED'), -- Frontend Lê Hà
       (9001, 9011, 'ACCEPTED'), -- DevOps Bùi Mai
       (9001, 9012, 'ACCEPTED'), -- DevOps Đỗ Chi
       (9001, 9015, 'ACCEPTED'), -- Data Dương Long
       (9001, 9016, 'ACCEPTED'), -- Data Lý Kiên
       (9001, 9019, 'ACCEPTED'), -- Mobile Tô Trang
       (9001, 9020, 'ACCEPTED'), -- Mobile Mai Hiếu
       (9001, 9023, 'ACCEPTED'), -- QA Lương Sơn
       (9001, 9024, 'ACCEPTED'); -- QA Đoàn Giang
