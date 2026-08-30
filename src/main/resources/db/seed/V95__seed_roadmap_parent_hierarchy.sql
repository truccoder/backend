-- =============================================================================================
-- Gắn cây cha-con cho các nút lộ trình đã seed ở V88.
--
-- KHÔNG sửa lại V88. V88 đã chạy trên production (deploy f9aeea7) với parent_node_id toàn NULL,
-- nên checksum của nó đã bị Flyway ghi vào flyway_schema_history. Một lần thử sửa thẳng file đó
-- để thêm parent_node_id (commit 9dd89ab) đã làm validate-on-migrate chặn đứng deploy kế tiếp:
-- "Migration checksum mismatch for migration version 88" — rồi rollback về f9aeea7. Bài học: một
-- migration đã apply là bất biến, kể cả migration seed; muốn đổi dữ liệu nó tạo ra thì phải bằng
-- một migration MỚI, không phải sửa lại migration cũ.
--
-- UPDATE theo id nút — nút gốc của mỗi lộ trình (parent NULL) giữ nguyên, chỉ nút con mới có
-- parent_id. parent luôn cùng roadmap_id với con, khớp ràng buộc mà SeedMigrationTest kiểm tra.
-- =============================================================================================

UPDATE socialapp.t_roadmap_nodes AS n
   SET parent_node_id = v.parent_id
  FROM (VALUES
        (4, 1), (5, 3), (6, 3), (7, 3),
        (16, 15), (17, 15), (18, 17), (19, 17), (20, 13), (22, 17),
        (27, 25), (28, 27), (30, 26), (32, 31),
        (37, 35), (38, 35), (39, 36), (40, 39), (41, 37), (43, 42),
        (47, 46), (49, 48),
        (55, 54), (56, 54), (57, 54), (58, 56), (59, 57),
        (64, 63), (66, 65),
        (73, 71), (75, 73),
        (79, 78), (80, 78), (83, 82),
        (87, 85), (89, 87),
        (92, 91), (94, 92), (95, 94),
        (99, 98), (102, 101), (103, 100)
       ) AS v(id, parent_id)
 WHERE n.id = v.id;
