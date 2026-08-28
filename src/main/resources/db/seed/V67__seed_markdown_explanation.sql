-- =============================================================================================
-- S8 · Một bản giải thích có Markdown thật
--
-- Thẻ giải thích đã chuyển sang render Markdown (react-markdown + remark-gfm), nhưng cả 8 bản
-- trong V56 đều là chữ trơn — mỗi bản khoảng 295 ký tự, không có `**`, không gạch đầu dòng,
-- không tiêu đề, không khối code, không bảng. Nghĩa là đường render mới chưa từng chạy trên dữ
-- liệu seed: nó chỉ được kiểm bằng dữ liệu giả gõ tay lúc phát triển.
--
-- Không sửa V56 để tạo ca này. Hai lý do, lý do thứ hai mới là lý do thật:
--   1. V56 đã chạy ở production, đổi nội dung là đổi checksum của một migration đã áp dụng.
--   2. Chữ trơn CŨNG là một ca cần kiểm. Bản do Gemini sinh ra không phải lúc nào cũng có
--      Markdown, nên "văn bản không có cú pháp nào" phải hiện ra đúng như văn bản, không được
--      vỡ. Lấy 8 bản chữ trơn đổi thành Markdown là mất một ca đang đúng để lấy một ca đang
--      thiếu — cùng nguyên tắc đã ghi ở mục S7 của V65.
--
-- Bản dưới đây cố ý dùng đủ bảy loại phần tử mà thẻ giải thích ánh xạ: tiêu đề `##` và `###`,
-- **in đậm**, danh sách `*`, danh sách đánh số, `code trong dòng`, khối code ba dấu nháy có tên
-- ngôn ngữ, bảng GFM, và một liên kết. Thiếu bất kỳ loại nào thì đúng nhánh render đó vẫn chưa
-- được nhìn thấy lần nào.
--
-- Gắn vào bài 5302 (tác giả 9002) cho người đọc 9001 — tài khoản demo. Cặp này chắc chắn còn
-- trống: V56 chạy trước V65 nên không bản giải thích nào trỏ tới dải bài 5301-5314, và ràng
-- buộc UNIQUE(post_id, user_id, version) vì thế không thể đụng.
--
-- Không cần setval: hàng này để id tự sinh từ q_explanations_id chứ không cấp tay như V65.
-- =============================================================================================

INSERT INTO socialapp.t_explanations
    (post_id, user_id, original_content, explanation_content, concepts, prerequisites,
     external_links, complexity_score, feedback_note, version, created_at, updated_at)
SELECT p.id,
       9001,
       p.content,
       $md$## Bài này nói về cái gì

Tác giả đang mô tả **correlation id** — một mã định danh duy nhất gắn vào mỗi request ngay tại
cửa ngõ, rồi đi kèm request đó qua mọi dịch vụ phía sau.

Vấn đề mà nó giải quyết không phải là *thiếu log*. Log vẫn đầy đủ. Vấn đề là năm tập log của năm
dịch vụ không có gì chung để nối lại, nên khi một request hỏng, không ai ghép được các mảnh của
nó lại thành một dòng thời gian.

### Ba khái niệm cần phân biệt

* `trace id` — định danh cho **cả hành trình** của một request, xuyên suốt mọi dịch vụ.
* `span id` — định danh cho **một chặng** trong hành trình đó, ví dụ một lời gọi HTTP.
* `correlation id` — tên gọi chung, thường dùng khi hệ thống chưa theo chuẩn tracing nào.

Ở mức đang bàn trong bài, ba thứ này dùng thay nhau được. Khi nào bạn cần đo *thời gian từng
chặng* chứ không chỉ *nối các dòng log lại*, lúc đó sự khác nhau mới bắt đầu quan trọng.

### Trong Spring Boot thì nó trông thế nào

Thư viện log của Java gọi cơ chế này là MDC — một `Map` gắn theo luồng, và mọi dòng log phát ra
từ luồng đó tự động mang theo các khoá trong map.

```java
@Component
public class CorrelationIdFilter extends OncePerRequestFilter {

  private static final String HEADER = "X-Correlation-Id";

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {

    String correlationId =
        Optional.ofNullable(request.getHeader(HEADER))
            .filter(value -> !value.isBlank())
            .orElseGet(() -> UUID.randomUUID().toString());

    MDC.put("correlationId", correlationId);
    response.setHeader(HEADER, correlationId);
    try {
      chain.doFilter(request, response);
    } finally {
      // Bắt buộc. Luồng được tái sử dụng từ pool, nên bỏ dòng này là request sau thừa hưởng
      // correlation id của request trước — sai còn tệ hơn không có.
      MDC.remove("correlationId");
    }
  }
}
```

### Cái giá và cái nhận lại

| Hạng mục | Trước | Sau |
| --- | --- | --- |
| Tìm lại một request hỏng | đọc chéo 4 cửa sổ terminal | một lệnh tìm kiếm |
| Thời gian trung bình | khoảng 30 phút | dưới 1 phút |
| Chi phí thêm | không | 1 header, ~20 dòng code |

Ba điều cần nhớ, theo thứ tự quan trọng:

1. **Nhận** id từ header nếu người gọi đã có, đừng sinh mới — nếu không thì chuỗi đứt ngay tại
   dịch vụ đầu tiên.
2. **Truyền tiếp** id đó trong mọi lời gọi đi ra, kể cả lời gọi tới hàng đợi.
3. **Xoá** khỏi MDC ở khối `finally`, vì lý do đã ghi trong đoạn code trên.

Đọc thêm: [tài liệu MDC của Logback](https://logback.qos.ch/manual/mdc.html) giải thích chính xác
phần "gắn theo luồng" hoạt động ra sao, và vì sao nó không tự đi theo khi bạn chuyển việc sang
một luồng khác.$md$,
       '["Correlation ID","MDC","Distributed tracing","Structured logging"]'::jsonb,
       '["Biết Servlet Filter chạy ở đâu trong vòng đời request","Đã từng đọc log của nhiều dịch vụ cùng lúc"]'::jsonb,
       '[{"title":"Logback: Mapped Diagnostic Context","url":"https://logback.qos.ch/manual/mdc.html","reason":"Mô tả chính xác cơ chế gắn theo luồng, phần hay bị hiểu nhầm nhất."},{"title":"OpenTelemetry: Traces","url":"https://opentelemetry.io/docs/concepts/signals/traces/","reason":"Bước tiếp theo khi cần đo thời gian từng chặng chứ không chỉ nối log."}]'::jsonb,
       2,
       NULL,
       1,
       p.created_at + INTERVAL '1 day',
       p.created_at + INTERVAL '1 day'
  FROM socialapp.t_posts p
 WHERE p.id = 5302;
