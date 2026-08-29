-- =============================================================================================
-- Chủ đề của một cuốn sách, để lọc Thư viện theo tab.
--
-- ── Vì sao cột này bắt buộc phải ở BE, khác với V76/V77 ────────────────────────────────────
-- GET /v1/api/books là cursor pagination: BookRepository.findLibraryPage lấy `WHERE id < :cursor
-- ORDER BY id DESC LIMIT :limit+1`, tối đa 50 hàng một lần. FE lọc phía client chỉ lọc được
-- những hàng nó đang cầm — bấm tab "MOBILE" khi cuốn sách mobile duy nhất nằm ở trang 3 sẽ ra
-- một danh sách rỗng, và không có cách nào để FE biết là nó rỗng oan. Bộ lọc phải nằm trong
-- chính câu truy vấn cắt trang.
--
-- ── Index ──────────────────────────────────────────────────────────────────────────────────
-- (category, id DESC) chứ không phải (category): truy vấn lọc bằng category rồi sắp xếp và cắt
-- theo id giảm dần. Index chỉ có category buộc Postgres đọc hết mọi hàng của chủ đề đó rồi mới
-- sort để lấy 20 hàng đầu; thêm id vào làm cột thứ hai thì thứ tự đã nằm sẵn trong index và
-- LIMIT dừng ngay khi đủ hàng. Index cũ trên id (khoá chính) vẫn phục vụ nhánh không lọc.
--
-- ── NOT NULL DEFAULT 'OTHER' ───────────────────────────────────────────────────────────────
-- 14 cuốn seed và mọi sách người dùng đã đăng đều có trước cột này. Chúng nhận OTHER ở đây và
-- được gán chủ đề thật ở V79 — nhưng chỉ sách seed, vì chỉ chúng mới biết trước nội dung là gì.
-- Sách người dùng đã đăng ở lại OTHER: đoán chủ đề hộ tác giả bằng ILIKE trên tiêu đề là đúng
-- kiểu sai mà V74 đã ghi lại trong ghi chú của nó.
--
-- Cột nhận giá trị từ tác giả lúc đăng sách (CreateBookRequestDto.category). Không nhờ model
-- đoán như V77: ở đây có người biết chắc câu trả lời và đang đứng trước một cái form.
-- =============================================================================================

ALTER TABLE socialapp.t_books
    ADD COLUMN category VARCHAR(32) NOT NULL DEFAULT 'OTHER';

CREATE INDEX idx_books_category_id ON socialapp.t_books (category, id DESC);
