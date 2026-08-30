-- =============================================================================================
-- Xếp hạng theo độ liên quan: một cột tsvector sinh sẵn cho mỗi bảng tìm được.
--
-- ── Vấn đề cột này giải quyết ──────────────────────────────────────────────────────────────
-- Tìm kiếm tới trước migration này là substring: f_unaccent(lower(col)) LIKE '%...%'. Nó trả
-- về đúng hàng nhưng không biết hàng nào liên quan hơn hàng nào, nên mỗi nhánh phải mượn một
-- thứ tự vay tạm -- sách theo avg_rating, người theo full_name, bài theo created_at. Hệ quả là
-- một cuốn sách tên đúng y hệt từ người dùng gõ vẫn xếp sau một cuốn 5 sao chỉ khớp một chữ
-- trong mô tả. LIKE cũng coi cả chuỗi là một khối: "java spring" không khớp "Spring với Java".
--
-- tsvector + ts_rank_cd trả lời được cả hai câu đó: nó tách tài liệu thành lexeme có vị trí và
-- có trọng số, nên so được độ liên quan, và tsquery ghép nhiều từ bằng AND/OR/cụm.
--
-- ── Vì sao cột sinh sẵn (STORED) chứ không phải index trên biểu thức ───────────────────────
-- CREATE INDEX ... ON (to_tsvector(...)) là đủ để LỌC, nhưng ts_rank_cd cần GIÁ TRỊ tsvector
-- để tính điểm, và index không trả giá trị về. Với index-trên-biểu-thức, Postgres phải tính
-- lại to_tsvector cho từng hàng ứng viên ở bước xếp hạng -- tức là phần đắt nhất chạy đúng số
-- lần mà index vừa tiết kiệm được. Cột STORED tính một lần lúc ghi.
--
-- Đánh đổi: ADD COLUMN ... GENERATED viết lại toàn bộ bảng và giữ ACCESS EXCLUSIVE trong lúc
-- đó. Với quy mô hiện tại (500 tài khoản, ~2.600 bài) là chuyện của vài giây; trên production
-- thì đây là migration cần cửa sổ bảo trì, và là migration duy nhất trong repo cần điều đó.
--
-- ── Vì sao GENERATED chứ không phải trigger ────────────────────────────────────────────────
-- Generated column là bảo đảm của Postgres: không tồn tại đường ghi nào bỏ qua được nó. Một
-- trigger thì bỏ qua được -- và trong repo này có đúng loại đường ghi hay bị quên: bộ seed ở
-- db/seed dùng INSERT ... ON CONFLICT hàng loạt. Vì db/seed mang version V80-V92, tức là chạy
-- SAU file này, 500 tài khoản và 2.600 bài seed tự có search_vector ngay lúc INSERT: không cần
-- backfill, không cần sửa một dòng nào trong V80-V92.
--
-- ── Vì sao 'simple' chứ không phải 'english' ───────────────────────────────────────────────
-- Postgres không có từ điển tiếng Việt. 'simple' không stem, không bỏ stopword -- chỉ tách
-- token và hạ chữ thường, đúng thứ cần khi dấu đã được f_unaccent gấp phẳng và nội dung là
-- tiếng Việt lẫn thuật ngữ Anh. Đánh đổi có ý thức: 'roadmaps' không khớp 'roadmap'. Nhánh
-- trigram của V48 gánh phần đó, và đó là lý do 6 index của V48 được GIỮ LẠI nguyên vẹn.
--
-- ── to_tsvector PHẢI có tham số regconfig ──────────────────────────────────────────────────
-- to_tsvector(text) một tham số là STABLE, không phải IMMUTABLE: nó đọc GUC
-- default_text_search_config. Generated column đòi IMMUTABLE, nên bản một tham số bị từ chối
-- thẳng. Bản hai tham số to_tsvector(regconfig, text) là IMMUTABLE. Đã đối chiếu pg_proc:
-- f_unaccent=i, setweight=i, tsvector_concat=i, jsonb_object_field_text=i.
--
-- ── coalesce là BẮT BUỘC, không phải phòng xa ──────────────────────────────────────────────
-- f_unaccent được khai STRICT ở V48, nên NULL vào là NULL ra; to_tsvector('simple', NULL) là
-- NULL; và NULL || tsvector là NULL -- toàn bộ tsvector biến thành NULL. Bỏ coalesce ở một vế
-- thôi thì mọi hàng thiếu vế đó MẤT LUÔN CẢ TIÊU ĐỀ khỏi index. Hỏng im lặng: không lỗi,
-- không cảnh báo, chỉ là hàng đó không bao giờ tìm thấy nữa. Đúng loại bẫy V48 đã ghi lại một
-- lần cho f_unaccent so với unaccent.
--
-- ── Trọng số ───────────────────────────────────────────────────────────────────────────────
-- A = danh tính (tên, tiêu đề), B = nội dung (mô tả, thân bài), C = phụ (thẻ). Trọng số mặc
-- định của ts_rank_cd là {D,C,B,A} = {0.1, 0.2, 0.4, 1.0}, giữ nguyên không truyền mảng riêng:
-- khớp tiêu đề ăn điểm gấp 2.5 lần khớp mô tả và gấp 5 lần khớp thẻ.
--
-- tags::text là mẹo có ý thức: jsonb_array_elements là set-returning nên không dùng được
-- trong generated column, còn jsonb_out thì IMMUTABLE. Parser của to_tsvector tự bỏ ngoặc
-- vuông và dấu nháy, để lại đúng các từ trong thẻ.
--
-- ── Vì sao CREATE INDEX thường, không CONCURRENTLY ─────────────────────────────────────────
-- CONCURRENTLY không chạy được trong transaction, mà Flyway bọc mỗi migration trong một
-- transaction. Và ở đây nó cũng không mua được gì: ADD COLUMN ngay phía trên đã lấy ACCESS
-- EXCLUSIVE trên cùng bảng rồi.
--
-- ── Cho người sửa file này về sau ──────────────────────────────────────────────────────────
-- Đừng sửa. Một khi migration đã apply, checksum bị đóng băng: application-prod.yml đặt
-- validate-on-migrate: true, nên nội dung đổi sẽ làm mọi lần migrate sau đó thất bại. Cần đổi
-- định nghĩa search_vector thì viết migration mới.
-- =============================================================================================

-- ── t_users ───────────────────────────────────────────────────────────────────────────────
-- Cả hai vế đều A: một cái tên và một handle đều là danh tính, không có cái nào là "nội dung".
ALTER TABLE socialapp.t_users
    ADD COLUMN search_vector tsvector
    GENERATED ALWAYS AS (
        setweight(to_tsvector('simple', coalesce(public.f_unaccent(lower(full_name)), '')), 'A') ||
        setweight(to_tsvector('simple', coalesce(public.f_unaccent(lower(username)), '')), 'A')
    ) STORED;

CREATE INDEX idx_users_search_vector ON socialapp.t_users USING gin (search_vector);

-- ── t_posts ───────────────────────────────────────────────────────────────────────────────
-- eventTitle là A và content là B: tên một sự kiện là danh tính của bài đó, thân bài là nội
-- dung. Đúng hai trường mà PostRepository.searchByContentOrEventName đang tìm hôm nay -- năm
-- khối chi tiết còn lại (quiz/poll/code/qna/article) vẫn nằm ngoài tầm tìm kiếm, y như trước.
ALTER TABLE socialapp.t_posts
    ADD COLUMN search_vector tsvector
    GENERATED ALWAYS AS (
        setweight(to_tsvector('simple',
            coalesce(public.f_unaccent(lower(event_details ->> 'eventTitle')), '')), 'A') ||
        setweight(to_tsvector('simple',
            coalesce(public.f_unaccent(lower(content)), '')), 'B')
    ) STORED;

CREATE INDEX idx_posts_search_vector ON socialapp.t_posts USING gin (search_vector);

-- ── t_books ───────────────────────────────────────────────────────────────────────────────
ALTER TABLE socialapp.t_books
    ADD COLUMN search_vector tsvector
    GENERATED ALWAYS AS (
        setweight(to_tsvector('simple', coalesce(public.f_unaccent(lower(title)), '')), 'A') ||
        setweight(to_tsvector('simple', coalesce(public.f_unaccent(lower(description)), '')), 'B')
    ) STORED;

CREATE INDEX idx_books_search_vector ON socialapp.t_books USING gin (search_vector);

-- ── t_projects ────────────────────────────────────────────────────────────────────────────
-- tags cho phép NULL (xem V74), nên vế C dựa hoàn toàn vào coalesce ở trên.
ALTER TABLE socialapp.t_projects
    ADD COLUMN search_vector tsvector
    GENERATED ALWAYS AS (
        setweight(to_tsvector('simple', coalesce(public.f_unaccent(lower(title)), '')), 'A') ||
        setweight(to_tsvector('simple', coalesce(public.f_unaccent(lower(description)), '')), 'B') ||
        setweight(to_tsvector('simple', coalesce(public.f_unaccent(lower(tags::text)), '')), 'C')
    ) STORED;

CREATE INDEX idx_projects_search_vector ON socialapp.t_projects USING gin (search_vector);

-- ── t_roadmaps ────────────────────────────────────────────────────────────────────────────
-- Bảng này ở quy mô hàng chục hàng (V76 ghi lại lý do nó không có index cho category), nên
-- index dưới đây gần như chỉ để nhất quán: planner sẽ chọn quét tuần tự và đó là lựa chọn
-- đúng. Nó tồn tại để một truy vấn duy nhất phục vụ được cả sáu bảng mà không có ngoại lệ.
ALTER TABLE socialapp.t_roadmaps
    ADD COLUMN search_vector tsvector
    GENERATED ALWAYS AS (
        setweight(to_tsvector('simple', coalesce(public.f_unaccent(lower(name)), '')), 'A') ||
        setweight(to_tsvector('simple', coalesce(public.f_unaccent(lower(description)), '')), 'B')
    ) STORED;

CREATE INDEX idx_roadmaps_search_vector ON socialapp.t_roadmaps USING gin (search_vector);

-- ── t_trending_items ──────────────────────────────────────────────────────────────────────
-- `author` ở bảng này là VARCHAR tự do từ nguồn crawl, không phải khoá ngoại tới t_users, nên
-- nó KHÔNG vào search_vector: gõ tên một người phải ra tài khoản của họ, không ra một bài trên
-- Hacker News mà họ tình cờ là tác giả.
ALTER TABLE socialapp.t_trending_items
    ADD COLUMN search_vector tsvector
    GENERATED ALWAYS AS (
        setweight(to_tsvector('simple', coalesce(public.f_unaccent(lower(title)), '')), 'A') ||
        setweight(to_tsvector('simple', coalesce(public.f_unaccent(lower(summary)), '')), 'B') ||
        setweight(to_tsvector('simple', coalesce(public.f_unaccent(lower(tags::text)), '')), 'C')
    ) STORED;

CREATE INDEX idx_trending_items_search_vector
    ON socialapp.t_trending_items USING gin (search_vector);
