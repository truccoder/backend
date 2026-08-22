-- =============================================================================================
-- Hashtag + 152 bài viết trải đủ 8 giá trị của enum PostType, kèm gắn thẻ người và hashtag.
--
-- Id được cấp TƯỜNG MINH (5001+) chứ không để sequence tự sinh, vì V54-V60 phải tham chiếu tới
-- từng bài cụ thể (bình luận vào bài nào, sách gắn với bài nào, log kiểm duyệt của bài nào).
-- Nếu để sequence cấp thì các file sau phải dò lại bài bằng cách so khớp nội dung — vừa dài dòng
-- vừa hỏng ngay khi ai đó sửa một câu chữ. Sequence được đẩy lên quá vùng này ở cuối file.
--
-- Dải id theo loại bài:
--   5001-5008  EVENT          5011-5022  CODE_SNIPPET   5031-5040  ARTICLE
--   5051-5062  QNA            5071-5078  POLL           5081-5090  LINK
--   5091-5096  BOOK           5101-5190  REGULAR
--
-- Cột `images` để NULL ở mọi bài, có chủ đích: backend không có endpoint nào upload ảnh bài viết
-- (MinIO chỉ có bucket profile-pictures, books, book-covers — xem MinIOService), nên URL trong
-- cột này là do client tự cấp. Bịa URL ở đây chỉ tạo ra một loạt ảnh vỡ.
--
-- Trạng thái kiểm duyệt cố ý không đồng nhất: /posts/public chỉ trả bài APPROVED, còn
-- /admin/moderation/pending cần bài PENDING_REVIEW mới có gì để trình bày.
-- =============================================================================================

-- ── Hashtag ────────────────────────────────────────────────────────────────────────────────
-- usage_count được tính lại từ t_post_hashtags ở cuối file, không gõ tay, để con số hiển thị
-- trên trang hashtag luôn khớp với số bài thật sự gắn thẻ đó.
INSERT INTO socialapp.t_hashtags (id, name, usage_count, created_at) VALUES
    (1001, 'java', 0, now() - INTERVAL '300 days'),
    (1002, 'springboot', 0, now() - INTERVAL '298 days'),
    (1003, 'postgresql', 0, now() - INTERVAL '295 days'),
    (1004, 'redis', 0, now() - INTERVAL '290 days'),
    (1005, 'docker', 0, now() - INTERVAL '288 days'),
    (1006, 'kubernetes', 0, now() - INTERVAL '285 days'),
    (1007, 'typescript', 0, now() - INTERVAL '280 days'),
    (1008, 'react', 0, now() - INTERVAL '278 days'),
    (1009, 'nextjs', 0, now() - INTERVAL '275 days'),
    (1010, 'tailwindcss', 0, now() - INTERVAL '270 days'),
    (1011, 'kotlin', 0, now() - INTERVAL '265 days'),
    (1012, 'flutter', 0, now() - INTERVAL '260 days'),
    (1013, 'python', 0, now() - INTERVAL '255 days'),
    (1014, 'machinelearning', 0, now() - INTERVAL '250 days'),
    (1015, 'devops', 0, now() - INTERVAL '245 days'),
    (1016, 'terraform', 0, now() - INTERVAL '240 days'),
    (1017, 'aws', 0, now() - INTERVAL '235 days'),
    (1018, 'security', 0, now() - INTERVAL '230 days'),
    (1019, 'testing', 0, now() - INTERVAL '225 days'),
    (1020, 'playwright', 0, now() - INTERVAL '220 days'),
    (1021, 'performance', 0, now() - INTERVAL '215 days'),
    (1022, 'architecture', 0, now() - INTERVAL '210 days'),
    (1023, 'career', 0, now() - INTERVAL '205 days'),
    (1024, 'opensource', 0, now() - INTERVAL '200 days'),
    (1025, 'database', 0, now() - INTERVAL '195 days'),
    (1026, 'microservices', 0, now() - INTERVAL '190 days'),
    (1027, 'graphql', 0, now() - INTERVAL '185 days'),
    (1028, 'observability', 0, now() - INTERVAL '180 days'),
    (1029, 'ux', 0, now() - INTERVAL '175 days'),
    (1030, 'productivity', 0, now() - INTERVAL '170 days');

-- ── EVENT (5001-5008) ──────────────────────────────────────────────────────────────────────
-- event_details khớp EventDetails{eventTitle,eventDescription,startTime,endTime,timezone,
-- location,onlineUrl,maxAttendees}.
--
-- startTime/endTime ghi dưới dạng chuỗi ISO-8601 có offset. Hibernate serialize OffsetDateTime
-- ra jsonb theo cấu hình Jackson của riêng nó (có thể là số epoch), nhưng chiều ĐỌC thì
-- InstantDeserializer nhận cả chuỗi ISO lẫn số — mà seed thì chỉ bao giờ được đọc. Chuỗi ISO
-- được chọn vì người đọc file này hiểu ngay đó là ngày nào.
--
-- Ba sự kiện đầu nằm ở TƯƠNG LAI để EventReminderScheduler có việc để làm và nút RSVP còn ý
-- nghĩa; các sự kiện còn lại đã diễn ra, dùng cho lịch sử.
INSERT INTO socialapp.t_posts
    (id, content, visibility, author_id, post_type, moderation_status, event_details, created_at, updated_at) VALUES
    (5001, 'Meetup Spring Boot 3 và Virtual Threads — đăng ký sớm còn chỗ nhé cả nhà.', 'PUBLIC', 9005, 'EVENT', 'APPROVED',
     '{"eventTitle":"Spring Boot 3 & Virtual Threads Meetup","eventDescription":"Chia sẻ kinh nghiệm chuyển sang virtual threads trên hệ thống thật, đo đạc trước và sau.","startTime":"2026-09-12T18:30:00+07:00","endTime":"2026-09-12T21:00:00+07:00","timezone":"Asia/Ho_Chi_Minh","location":"Dreamplex, 195 Điện Biên Phủ, Bình Thạnh, TP.HCM","onlineUrl":"https://meet.google.com/seed-spring-meetup","maxAttendees":80}'::jsonb,
     now() - INTERVAL '20 days', now() - INTERVAL '20 days'),
    (5002, 'Workshop Kubernetes cho người mới, mang laptop theo thực hành luôn.', 'PUBLIC', 9033, 'EVENT', 'APPROVED',
     '{"eventTitle":"Kubernetes Hands-on Workshop","eventDescription":"Từ pod tới ingress, dựng cụm local bằng kind rồi deploy một service thật.","startTime":"2026-09-20T09:00:00+07:00","endTime":"2026-09-20T16:30:00+07:00","timezone":"Asia/Ho_Chi_Minh","location":"Toong Coworking, 126 Nguyễn Thị Minh Khai, Quận 3, TP.HCM","onlineUrl":null,"maxAttendees":40}'::jsonb,
     now() - INTERVAL '15 days', now() - INTERVAL '15 days'),
    (5003, 'Buổi review kiến trúc mở — mang bài toán của bạn tới, cả nhóm cùng mổ xẻ.', 'PUBLIC', 9010, 'EVENT', 'APPROVED',
     '{"eventTitle":"Open Architecture Review","eventDescription":"Mỗi người 15 phút trình bày, 15 phút phản biện. Ưu tiên hệ thống đang chạy production.","startTime":"2026-10-03T19:00:00+07:00","endTime":"2026-10-03T21:30:00+07:00","timezone":"Asia/Ho_Chi_Minh","location":null,"onlineUrl":"https://meet.google.com/seed-arch-review","maxAttendees":25}'::jsonb,
     now() - INTERVAL '10 days', now() - INTERVAL '10 days'),
    (5004, 'Frontend Guild tháng này nói về Design System, mời mọi người tham gia.', 'PUBLIC', 9018, 'EVENT', 'APPROVED',
     '{"eventTitle":"Frontend Guild: Design System","eventDescription":"Xây design token thế nào để designer và developer cùng dùng được một nguồn.","startTime":"2026-07-18T18:00:00+07:00","endTime":"2026-07-18T20:00:00+07:00","timezone":"Asia/Ho_Chi_Minh","location":"Circo Coworking, 267 Nguyễn Thị Minh Khai, Quận 1, TP.HCM","onlineUrl":null,"maxAttendees":50}'::jsonb,
     now() - INTERVAL '60 days', now() - INTERVAL '60 days'),
    (5005, 'Data Night: từ notebook tới pipeline chạy hằng ngày.', 'PUBLIC', 9039, 'EVENT', 'APPROVED',
     '{"eventTitle":"Data Night: Notebook to Production","eventDescription":"Câu chuyện đưa mô hình từ notebook lên Airflow, và những chỗ vỡ trên đường đi.","startTime":"2026-06-25T18:30:00+07:00","endTime":"2026-06-25T21:00:00+07:00","timezone":"Asia/Ho_Chi_Minh","location":"UP Coworking, 1 Đại Cồ Việt, Hai Bà Trưng, Hà Nội","onlineUrl":"https://meet.google.com/seed-data-night","maxAttendees":60}'::jsonb,
     now() - INTERVAL '90 days', now() - INTERVAL '90 days'),
    (5006, 'Security Clinic — mang code lên soi cùng nhau, không phán xét.', 'FRIENDS', 9045, 'EVENT', 'APPROVED',
     '{"eventTitle":"Security Clinic","eventDescription":"Threat modeling nhanh cho một service, tìm lỗ hổng phổ biến theo OWASP Top 10.","startTime":"2026-08-08T19:00:00+07:00","endTime":"2026-08-08T21:00:00+07:00","timezone":"Asia/Ho_Chi_Minh","location":null,"onlineUrl":"https://meet.google.com/seed-sec-clinic","maxAttendees":20}'::jsonb,
     now() - INTERVAL '45 days', now() - INTERVAL '45 days'),
    (5007, 'QA Coffee Talk: đo chất lượng bằng gì ngoài số lượng test case?', 'PUBLIC', 9049, 'EVENT', 'APPROVED',
     '{"eventTitle":"QA Coffee Talk","eventDescription":"Bàn về chỉ số chất lượng thực sự phản ánh trải nghiệm người dùng.","startTime":"2026-07-05T09:30:00+07:00","endTime":"2026-07-05T11:30:00+07:00","timezone":"Asia/Ho_Chi_Minh","location":"The Coffee House Signature, Quận 1, TP.HCM","onlineUrl":null,"maxAttendees":30}'::jsonb,
     now() - INTERVAL '75 days', now() - INTERVAL '75 days'),
    (5008, 'Mobile Dev Hangout — chia sẻ về offline-first.', 'PUBLIC', 9029, 'EVENT', 'APPROVED',
     '{"eventTitle":"Mobile Dev Hangout","eventDescription":"Đồng bộ dữ liệu khi mạng chập chờn: conflict resolution và những cái bẫy.","startTime":"2026-09-27T14:00:00+07:00","endTime":"2026-09-27T17:00:00+07:00","timezone":"Asia/Ho_Chi_Minh","location":"Hive Coworking, 94 Xuân Thuỷ, Cầu Giấy, Hà Nội","onlineUrl":null,"maxAttendees":35}'::jsonb,
     now() - INTERVAL '8 days', now() - INTERVAL '8 days');

-- ── CODE_SNIPPET (5011-5022) ───────────────────────────────────────────────────────────────
-- CodeSnippetDetails{language,code}. Code viết trên một dòng logic với \n thoát trong chuỗi
-- JSON — jsonb không cho phép xuống dòng thật bên trong giá trị chuỗi.
INSERT INTO socialapp.t_posts
    (id, content, visibility, author_id, post_type, moderation_status, code_snippet_details, created_at, updated_at) VALUES
    (5011, 'Mẹo nhỏ: dùng @Transactional(readOnly = true) cho truy vấn đọc, Hibernate bỏ qua dirty checking.', 'PUBLIC', 9001, 'CODE_SNIPPET', 'APPROVED',
     '{"language":"java","code":"@Transactional(readOnly = true)\npublic List<PostDto> findPublicPosts(int limit) {\n  return postRepository.findPublicFeed(PageRequest.of(0, limit))\n      .stream().map(PostMapper::toDto).toList();\n}"}'::jsonb, now() - INTERVAL '5 days', now() - INTERVAL '5 days'),
    (5012, 'Viết custom hook để debounce input, đỡ gọi API mỗi lần gõ phím.', 'PUBLIC', 9011, 'CODE_SNIPPET', 'APPROVED',
     '{"language":"typescript","code":"export function useDebounced<T>(value: T, delay = 300): T {\n  const [debounced, setDebounced] = useState(value);\n  useEffect(() => {\n    const id = setTimeout(() => setDebounced(value), delay);\n    return () => clearTimeout(id);\n  }, [value, delay]);\n  return debounced;\n}"}'::jsonb, now() - INTERVAL '7 days', now() - INTERVAL '7 days'),
    (5013, 'Index partial trong Postgres — chỉ đánh index phần dữ liệu thật sự truy vấn.', 'PUBLIC', 9003, 'CODE_SNIPPET', 'APPROVED',
     '{"language":"sql","code":"CREATE UNIQUE INDEX uq_pending_pair\n    ON t_friend_requests (LEAST(requester_id, addressee_id),\n                          GREATEST(requester_id, addressee_id))\n WHERE status = ''PENDING'';"}'::jsonb, now() - INTERVAL '12 days', now() - INTERVAL '12 days'),
    (5014, 'Healthcheck trong docker-compose, đừng để depends_on đánh lừa.', 'PUBLIC', 9031, 'CODE_SNIPPET', 'APPROVED',
     '{"language":"yaml","code":"healthcheck:\n  test: [\"CMD-SHELL\", \"pg_isready -U postgres\"]\n  interval: 5s\n  timeout: 5s\n  retries: 20\n  start_period: 10s"}'::jsonb, now() - INTERVAL '18 days', now() - INTERVAL '18 days'),
    (5015, 'Coroutine scope đúng chỗ thì không rò rỉ.', 'PUBLIC', 9026, 'CODE_SNIPPET', 'APPROVED',
     '{"language":"kotlin","code":"class FeedViewModel(private val repo: FeedRepository) : ViewModel() {\n  val feed = repo.observeFeed()\n      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())\n}"}'::jsonb, now() - INTERVAL '22 days', now() - INTERVAL '22 days'),
    (5016, 'Một dòng pandas thay cho vòng lặp, đọc dễ hơn nhiều.', 'PUBLIC', 9038, 'CODE_SNIPPET', 'APPROVED',
     '{"language":"python","code":"daily = (df.assign(day=df.created_at.dt.floor(\"D\"))\n           .groupby([\"day\", \"source\"], as_index=False)\n           .agg(total=(\"score\", \"sum\"), n=(\"id\", \"count\")))"}'::jsonb, now() - INTERVAL '25 days', now() - INTERVAL '25 days'),
    (5017, 'Terraform module nhỏ gọn cho S3 bucket có versioning.', 'PUBLIC', 9033, 'CODE_SNIPPET', 'APPROVED',
     '{"language":"hcl","code":"resource \"aws_s3_bucket_versioning\" \"this\" {\n  bucket = aws_s3_bucket.this.id\n  versioning_configuration {\n    status = \"Enabled\"\n  }\n}"}'::jsonb, now() - INTERVAL '30 days', now() - INTERVAL '30 days'),
    (5018, 'Test API bằng Playwright, chạy nhanh hơn mở cả trình duyệt.', 'PUBLIC', 9050, 'CODE_SNIPPET', 'APPROVED',
     '{"language":"typescript","code":"test(\"trả 401 khi thiếu token\", async ({ request }) => {\n  const res = await request.get(\"/v1/api/feed\");\n  expect(res.status()).toBe(401);\n});"}'::jsonb, now() - INTERVAL '33 days', now() - INTERVAL '33 days'),
    (5019, 'Rate limit đơn giản bằng Redis INCR, không cần thư viện.', 'PUBLIC', 9006, 'CODE_SNIPPET', 'APPROVED',
     '{"language":"java","code":"Long count = redis.opsForValue().increment(key);\nif (count != null && count == 1L) {\n  redis.expire(key, window);\n}\nreturn count != null && count > limit;"}'::jsonb, now() - INTERVAL '38 days', now() - INTERVAL '38 days'),
    (5020, 'Query GraphQL lấy pinned repo của một tài khoản GitHub.', 'PUBLIC', 9058, 'CODE_SNIPPET', 'APPROVED',
     '{"language":"graphql","code":"query {\n  user(login: \"octocat\") {\n    pinnedItems(first: 6, types: REPOSITORY) {\n      nodes { ... on Repository { name stargazerCount } }\n    }\n  }\n}"}'::jsonb, now() - INTERVAL '42 days', now() - INTERVAL '42 days'),
    (5021, 'Bắt N+1 query ngay trong test, đừng đợi tới production.', 'FRIENDS', 9008, 'CODE_SNIPPET', 'APPROVED',
     '{"language":"java","code":"var stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();\nstats.setStatisticsEnabled(true);\nservice.loadFeed(userId);\nassertThat(stats.getPrepareStatementCount()).isLessThan(5);"}'::jsonb, now() - INTERVAL '48 days', now() - INTERVAL '48 days'),
    (5022, 'Regex tách slug từ tên có dấu tiếng Việt.', 'PUBLIC', 9014, 'CODE_SNIPPET', 'APPROVED',
     '{"language":"sql","code":"SELECT trim(BOTH ''-'' FROM\n         regexp_replace(lower(unaccent(full_name)), ''[^a-z0-9]+'', ''-'', ''g''))\n  FROM t_users;"}'::jsonb, now() - INTERVAL '52 days', now() - INTERVAL '52 days');

-- ── ARTICLE (5031-5040) ────────────────────────────────────────────────────────────────────
-- ArticleDetails{title,coverImage,summary}. coverImage để null vì không có nơi chứa ảnh thật.
INSERT INTO socialapp.t_posts
    (id, content, visibility, author_id, post_type, moderation_status, article_details, created_at, updated_at) VALUES
    (5031, 'Mình viết lại toàn bộ hành trình tối ưu truy vấn feed, từ 2.4s xuống 180ms.', 'PUBLIC', 9010, 'ARTICLE', 'APPROVED',
     '{"title":"Tối ưu feed từ 2.4s xuống 180ms","coverImage":null,"summary":"Ba thay đổi tạo ra gần như toàn bộ khác biệt: bỏ open-in-view, thêm fetch join đúng chỗ, và đánh index theo đúng thứ tự cột của mệnh đề ORDER BY."}'::jsonb, now() - INTERVAL '14 days', now() - INTERVAL '14 days'),
    (5032, 'Ghi chép về việc chọn giữa monolith và microservices cho đội 8 người.', 'PUBLIC', 9021, 'ARTICLE', 'APPROVED',
     '{"title":"Đội 8 người thì chưa cần microservices","coverImage":null,"summary":"Chi phí vận hành của kiến trúc phân tán rơi hết vào một đội không có người trực hệ thống. Bài viết kể lại quyết định gộp lại và những gì thu được."}'::jsonb, now() - INTERVAL '28 days', now() - INTERVAL '28 days'),
    (5033, 'Hướng dẫn dựng design token dùng chung giữa Figma và code.', 'PUBLIC', 9054, 'ARTICLE', 'APPROVED',
     '{"title":"Design token: một nguồn cho cả Figma và code","coverImage":null,"summary":"Ba tầng token nguyên thuỷ, ngữ nghĩa, và theo component. Kèm cách sinh biến CSS tự động khi designer đổi màu."}'::jsonb, now() - INTERVAL '35 days', now() - INTERVAL '35 days'),
    (5034, 'Những gì mình học được sau một năm làm SRE trực hệ thống.', 'PUBLIC', 9035, 'ARTICLE', 'APPROVED',
     '{"title":"Một năm trực hệ thống dạy tôi điều gì","coverImage":null,"summary":"Cảnh báo tốt là cảnh báo có người biết phải làm gì khi nó kêu. Phần lớn cảnh báo chúng tôi từng dựng thì không."}'::jsonb, now() - INTERVAL '40 days', now() - INTERVAL '40 days'),
    (5035, 'Tổng hợp cách đánh giá mô hình gợi ý khi chưa có dữ liệu người dùng thật.', 'PUBLIC', 9039, 'ARTICLE', 'APPROVED',
     '{"title":"Đánh giá recommender khi chưa có traffic","coverImage":null,"summary":"Offline metric nói được rất ít. Bài viết bàn về cách dựng tập kiểm thử phản ánh hành vi thật và những sai lệch hay gặp."}'::jsonb, now() - INTERVAL '46 days', now() - INTERVAL '46 days'),
    (5036, 'Checklist review bảo mật trước khi mở một endpoint ra công khai.', 'PUBLIC', 9043, 'ARTICLE', 'APPROVED',
     '{"title":"Checklist trước khi mở một endpoint công khai","coverImage":null,"summary":"Ai gọi được, gọi bao nhiêu lần, trả về những trường nào, và nếu bị crawl toàn bộ thì mất gì."}'::jsonb, now() - INTERVAL '55 days', now() - INTERVAL '55 days'),
    (5037, 'Kinh nghiệm viết test không vỡ mỗi lần refactor.', 'PUBLIC', 9052, 'ARTICLE', 'APPROVED',
     '{"title":"Test bám hành vi, đừng bám cấu trúc","coverImage":null,"summary":"Test gọi thẳng vào phương thức private hay mock từng lớp một là test sẽ vỡ mỗi lần đổi cấu trúc, dù hành vi không đổi."}'::jsonb, now() - INTERVAL '62 days', now() - INTERVAL '62 days'),
    (5038, 'Ghi chép về đường nghề: từ junior lên senior thật sự đổi gì.', 'PUBLIC', 9005, 'ARTICLE', 'APPROVED',
     '{"title":"Từ junior lên senior thật sự đổi gì","coverImage":null,"summary":"Không phải viết code nhanh hơn. Là biết chọn bài toán nào không cần giải, và giải thích được lựa chọn đó cho người khác."}'::jsonb, now() - INTERVAL '70 days', now() - INTERVAL '70 days'),
    (5039, 'Cách mình tổ chức tài liệu kỹ thuật để người mới đọc là hiểu.', 'PUBLIC', 9056, 'ARTICLE', 'APPROVED',
     '{"title":"Viết tài liệu cho người chưa có ngữ cảnh","coverImage":null,"summary":"Bắt đầu bằng vấn đề chứ không bằng giải pháp, và luôn ghi lại vì sao chọn cách này thay vì cách kia."}'::jsonb, now() - INTERVAL '80 days', now() - INTERVAL '80 days'),
    (5040, 'Bản nháp bài viết về caching, mình để chế độ riêng tư trước.', 'PRIVATE', 9001, 'ARTICLE', 'APPROVED',
     '{"title":"Caching: nháp","coverImage":null,"summary":"Chưa hoàn thiện, đang gom ý về cache stampede và jitter cho TTL."}'::jsonb, now() - INTERVAL '3 days', now() - INTERVAL '3 days');

-- ── QNA (5051-5062) ────────────────────────────────────────────────────────────────────────
-- QnaDetails{isResolved,bountyPoints,acceptedAnswerId}. acceptedAnswerId trỏ tới t_comments.id
-- nên chỉ điền cho những câu đã có bình luận được chọn — các id đó do V54 tạo, và V54 sẽ
-- cập nhật ngược lại cột này sau khi bình luận tồn tại. Ở đây acceptedAnswerId luôn là null.
INSERT INTO socialapp.t_posts
    (id, content, visibility, author_id, post_type, moderation_status, qna_details, created_at, updated_at) VALUES
    (5051, 'Hibernate của mình sinh N+1 query ở endpoint danh sách bài viết dù đã có fetch join. Có ai gặp chưa?', 'PUBLIC', 9004, 'QNA', 'APPROVED',
     '{"isResolved":true,"bountyPoints":50,"acceptedAnswerId":null}'::jsonb, now() - INTERVAL '9 days', now() - INTERVAL '9 days'),
    (5052, 'Nên đặt TTL bao nhiêu cho cache danh sách bạn bè? Dữ liệu đổi không thường xuyên lắm.', 'PUBLIC', 9007, 'QNA', 'APPROVED',
     '{"isResolved":true,"bountyPoints":30,"acceptedAnswerId":null}'::jsonb, now() - INTERVAL '16 days', now() - INTERVAL '16 days'),
    (5053, 'Next.js App Router: làm sao giữ state khi chuyển route mà không dùng thư viện ngoài?', 'PUBLIC', 9013, 'QNA', 'APPROVED',
     '{"isResolved":false,"bountyPoints":40,"acceptedAnswerId":null}'::jsonb, now() - INTERVAL '4 days', now() - INTERVAL '4 days'),
    (5054, 'Kubernetes pod bị OOMKilled nhưng metric memory lại không chạm limit. Đọc sai ở đâu nhỉ?', 'PUBLIC', 9032, 'QNA', 'APPROVED',
     '{"isResolved":true,"bountyPoints":80,"acceptedAnswerId":null}'::jsonb, now() - INTERVAL '21 days', now() - INTERVAL '21 days'),
    (5055, 'Có nên dùng UUID làm khoá chính không, hay giữ bigint tự tăng?', 'PUBLIC', 9009, 'QNA', 'APPROVED',
     '{"isResolved":false,"bountyPoints":25,"acceptedAnswerId":null}'::jsonb, now() - INTERVAL '6 days', now() - INTERVAL '6 days'),
    (5056, 'Flutter build iOS trên CI bị treo ở bước pod install, ai xử lý được chưa?', 'PUBLIC', 9030, 'QNA', 'APPROVED',
     '{"isResolved":false,"bountyPoints":35,"acceptedAnswerId":null}'::jsonb, now() - INTERVAL '11 days', now() - INTERVAL '11 days'),
    (5057, 'Mô hình dự đoán của mình chạy tốt offline nhưng lệch hẳn khi lên production. Nên soi từ đâu?', 'PUBLIC', 9041, 'QNA', 'APPROVED',
     '{"isResolved":true,"bountyPoints":100,"acceptedAnswerId":null}'::jsonb, now() - INTERVAL '26 days', now() - INTERVAL '26 days'),
    (5058, 'JWT nên để thời gian sống bao lâu, và refresh token lưu ở đâu là an toàn?', 'PUBLIC', 9046, 'QNA', 'APPROVED',
     '{"isResolved":true,"bountyPoints":60,"acceptedAnswerId":null}'::jsonb, now() - INTERVAL '31 days', now() - INTERVAL '31 days'),
    (5059, 'Test end-to-end chạy máy mình thì xanh, lên CI thì đỏ ngẫu nhiên. Cách nào truy được?', 'PUBLIC', 9048, 'QNA', 'APPROVED',
     '{"isResolved":false,"bountyPoints":45,"acceptedAnswerId":null}'::jsonb, now() - INTERVAL '13 days', now() - INTERVAL '13 days'),
    (5060, 'Chọn giữa Kafka và Redis Streams cho hàng đợi nhẹ thì nên cân nhắc gì?', 'PUBLIC', 9002, 'QNA', 'APPROVED',
     '{"isResolved":false,"bountyPoints":55,"acceptedAnswerId":null}'::jsonb, now() - INTERVAL '18 days', now() - INTERVAL '18 days'),
    (5061, 'Làm sao đo được một thay đổi UI có thật sự cải thiện trải nghiệm không?', 'PUBLIC', 9055, 'QNA', 'APPROVED',
     '{"isResolved":false,"bountyPoints":20,"acceptedAnswerId":null}'::jsonb, now() - INTERVAL '23 days', now() - INTERVAL '23 days'),
    (5062, 'Postgres của mình chạy chậm dần sau vài tuần, VACUUM thủ công thì nhanh lại. Bình thường không?', 'PUBLIC', 9037, 'QNA', 'APPROVED',
     '{"isResolved":true,"bountyPoints":70,"acceptedAnswerId":null}'::jsonb, now() - INTERVAL '36 days', now() - INTERVAL '36 days');

-- ── POLL (5071-5078) ───────────────────────────────────────────────────────────────────────
-- PollDetails{question,options[PollOption{id,text,votesCount}],allowMultipleVotes,endDate}.
INSERT INTO socialapp.t_posts
    (id, content, visibility, author_id, post_type, moderation_status, poll_details, created_at, updated_at) VALUES
    (5071, 'Khảo sát nhanh cho bài chia sẻ sắp tới.', 'PUBLIC', 9005, 'POLL', 'APPROVED',
     '{"question":"Dự án của bạn đang dùng gì để quản lý migration?","options":[{"id":1,"text":"Flyway","votesCount":34},{"id":2,"text":"Liquibase","votesCount":12},{"id":3,"text":"Tự viết script","votesCount":9},{"id":4,"text":"Không dùng gì cả","votesCount":4}],"allowMultipleVotes":false,"endDate":"2026-09-30T23:59:59+07:00"}'::jsonb, now() - INTERVAL '9 days', now() - INTERVAL '9 days'),
    (5072, 'Mọi người thấy sao?', 'PUBLIC', 9018, 'POLL', 'APPROVED',
     '{"question":"Bạn viết CSS bằng cách nào?","options":[{"id":1,"text":"Tailwind","votesCount":41},{"id":2,"text":"CSS Modules","votesCount":18},{"id":3,"text":"styled-components","votesCount":11},{"id":4,"text":"CSS thuần","votesCount":7}],"allowMultipleVotes":false,"endDate":"2026-09-25T23:59:59+07:00"}'::jsonb, now() - INTERVAL '12 days', now() - INTERVAL '12 days'),
    (5073, 'Tò mò về thói quen của mọi người.', 'PUBLIC', 9033, 'POLL', 'APPROVED',
     '{"question":"Bạn deploy lên production bao lâu một lần?","options":[{"id":1,"text":"Nhiều lần mỗi ngày","votesCount":15},{"id":2,"text":"Vài lần một tuần","votesCount":28},{"id":3,"text":"Mỗi sprint","votesCount":22},{"id":4,"text":"Hiếm khi","votesCount":6}],"allowMultipleVotes":false,"endDate":"2026-10-10T23:59:59+07:00"}'::jsonb, now() - INTERVAL '5 days', now() - INTERVAL '5 days'),
    (5074, 'Chọn được nhiều đáp án nhé.', 'PUBLIC', 9039, 'POLL', 'APPROVED',
     '{"question":"Bạn dùng công cụ nào cho pipeline dữ liệu?","options":[{"id":1,"text":"Airflow","votesCount":26},{"id":2,"text":"dbt","votesCount":19},{"id":3,"text":"Spark","votesCount":14},{"id":4,"text":"Script thủ công","votesCount":11}],"allowMultipleVotes":true,"endDate":"2026-10-05T23:59:59+07:00"}'::jsonb, now() - INTERVAL '17 days', now() - INTERVAL '17 days'),
    (5075, 'Hỏi cho vui mà cũng để chuẩn bị nội dung.', 'PUBLIC', 9049, 'POLL', 'APPROVED',
     '{"question":"Đội bạn viết test tự động ở mức nào?","options":[{"id":1,"text":"Cả unit và e2e","votesCount":31},{"id":2,"text":"Chỉ unit","votesCount":24},{"id":3,"text":"Chỉ e2e","votesCount":5},{"id":4,"text":"Chưa có","votesCount":8}],"allowMultipleVotes":false,"endDate":"2026-09-18T23:59:59+07:00"}'::jsonb, now() - INTERVAL '24 days', now() - INTERVAL '24 days'),
    (5076, 'Khảo sát cho buổi chia sẻ nội bộ.', 'FRIENDS', 9043, 'POLL', 'APPROVED',
     '{"question":"Bạn quét phụ thuộc tìm lỗ hổng bằng gì?","options":[{"id":1,"text":"Dependabot","votesCount":22},{"id":2,"text":"Snyk","votesCount":13},{"id":3,"text":"Trivy","votesCount":9},{"id":4,"text":"Chưa quét","votesCount":16}],"allowMultipleVotes":true,"endDate":"2026-09-22T23:59:59+07:00"}'::jsonb, now() - INTERVAL '29 days', now() - INTERVAL '29 days'),
    (5077, 'Bình chọn giúp mình với.', 'PUBLIC', 9026, 'POLL', 'APPROVED',
     '{"question":"Bạn làm app di động bằng gì?","options":[{"id":1,"text":"React Native","votesCount":20},{"id":2,"text":"Flutter","votesCount":27},{"id":3,"text":"Native (Kotlin/Swift)","votesCount":18},{"id":4,"text":"Kotlin Multiplatform","votesCount":6}],"allowMultipleVotes":false,"endDate":"2026-10-15T23:59:59+07:00"}'::jsonb, now() - INTERVAL '7 days', now() - INTERVAL '7 days'),
    (5078, 'Câu hỏi cuối cho loạt khảo sát tuần này.', 'PUBLIC', 9053, 'POLL', 'APPROVED',
     '{"question":"Bạn ưu tiên điều gì khi chọn công ty?","options":[{"id":1,"text":"Bài toán kỹ thuật","votesCount":29},{"id":2,"text":"Lương thưởng","votesCount":33},{"id":3,"text":"Đồng nghiệp","votesCount":25},{"id":4,"text":"Linh hoạt thời gian","votesCount":21}],"allowMultipleVotes":true,"endDate":"2026-10-20T23:59:59+07:00"}'::jsonb, now() - INTERVAL '2 days', now() - INTERVAL '2 days');

-- ── LINK (5081-5090) ───────────────────────────────────────────────────────────────────────
-- LinkDetails{url,title,description,thumbnailUrl}. thumbnailUrl để null vì không có nơi chứa ảnh.
INSERT INTO socialapp.t_posts
    (id, content, visibility, author_id, post_type, moderation_status, link_details, created_at, updated_at) VALUES
    (5081, 'Tài liệu chính thức về virtual threads, đọc kỹ phần pinning.', 'PUBLIC', 9001, 'LINK', 'APPROVED',
     '{"url":"https://openjdk.org/jeps/444","title":"JEP 444: Virtual Threads","description":"Đặc tả virtual threads trong JDK 21, kèm phần nói về pinning khi dùng synchronized.","thumbnailUrl":null}'::jsonb, now() - INTERVAL '6 days', now() - INTERVAL '6 days'),
    (5082, 'Bài giải thích index của Postgres dễ hiểu nhất mình từng đọc.', 'PUBLIC', 9003, 'LINK', 'APPROVED',
     '{"url":"https://www.postgresql.org/docs/16/indexes.html","title":"PostgreSQL: Indexes","description":"Chương về index trong tài liệu chính thức, từ B-tree tới GIN và partial index.","thumbnailUrl":null}'::jsonb, now() - INTERVAL '19 days', now() - INTERVAL '19 days'),
    (5083, 'Hướng dẫn React Server Components, cuối cùng cũng có bản dễ đọc.', 'PUBLIC', 9011, 'LINK', 'APPROVED',
     '{"url":"https://react.dev/reference/rsc/server-components","title":"React Server Components","description":"Tài liệu chính thức về server components và ranh giới client/server.","thumbnailUrl":null}'::jsonb, now() - INTERVAL '27 days', now() - INTERVAL '27 days'),
    (5084, 'OWASP Top 10 bản mới, nên đọc lại hằng năm.', 'PUBLIC', 9045, 'LINK', 'APPROVED',
     '{"url":"https://owasp.org/www-project-top-ten/","title":"OWASP Top 10","description":"Danh sách mười rủi ro bảo mật ứng dụng web phổ biến nhất.","thumbnailUrl":null}'::jsonb, now() - INTERVAL '34 days', now() - INTERVAL '34 days'),
    (5085, 'Bài viết kinh điển về thiết kế API, đọc lại vẫn thấm.', 'PUBLIC', 9010, 'LINK', 'APPROVED',
     '{"url":"https://cloud.google.com/apis/design","title":"Google API Design Guide","description":"Nguyên tắc thiết kế API nhất quán, phần đặt tên tài nguyên rất đáng đọc.","thumbnailUrl":null}'::jsonb, now() - INTERVAL '41 days', now() - INTERVAL '41 days'),
    (5086, 'Tài liệu Kubernetes về resource limit, giải thích rõ request khác limit thế nào.', 'PUBLIC', 9031, 'LINK', 'APPROVED',
     '{"url":"https://kubernetes.io/docs/concepts/configuration/manage-resources-containers/","title":"Managing Resources for Containers","description":"Phân biệt request và limit, và điều gì xảy ra khi container vượt ngưỡng.","thumbnailUrl":null}'::jsonb, now() - INTERVAL '49 days', now() - INTERVAL '49 days'),
    (5087, 'Bộ tài liệu Playwright, phần trace viewer cực kỳ hữu ích khi test đỏ ngẫu nhiên.', 'PUBLIC', 9050, 'LINK', 'APPROVED',
     '{"url":"https://playwright.dev/docs/trace-viewer","title":"Playwright Trace Viewer","description":"Xem lại từng bước của một lần chạy test đã hỏng, kèm ảnh chụp và network log.","thumbnailUrl":null}'::jsonb, now() - INTERVAL '56 days', now() - INTERVAL '56 days'),
    (5088, 'Bài về MLOps mình thấy sát thực tế nhất.', 'PUBLIC', 9042, 'LINK', 'APPROVED',
     '{"url":"https://ml-ops.org/","title":"MLOps Principles","description":"Tổng hợp nguyên tắc vận hành hệ thống machine learning trong sản xuất.","thumbnailUrl":null}'::jsonb, now() - INTERVAL '64 days', now() - INTERVAL '64 days'),
    (5089, 'Tài liệu Terraform về state, đọc trước khi làm việc nhóm.', 'PUBLIC', 9036, 'LINK', 'APPROVED',
     '{"url":"https://developer.hashicorp.com/terraform/language/state","title":"Terraform State","description":"Vì sao state tồn tại, và vì sao không nên để nó trên máy cá nhân.","thumbnailUrl":null}'::jsonb, now() - INTERVAL '72 days', now() - INTERVAL '72 days'),
    (5090, 'Bộ nguyên tắc viết commit message, ngắn mà đủ.', 'PUBLIC', 9056, 'LINK', 'APPROVED',
     '{"url":"https://cbea.ms/git-commit/","title":"How to Write a Git Commit Message","description":"Bảy quy tắc viết commit message, phần giải thích vì sao dùng thể mệnh lệnh rất thuyết phục.","thumbnailUrl":null}'::jsonb, now() - INTERVAL '85 days', now() - INTERVAL '85 days');

-- ── BOOK (5091-5096) ───────────────────────────────────────────────────────────────────────
-- Bài giới thiệu sách. Bản ghi sách tương ứng nằm ở V55 và trỏ ngược về đây qua t_books.post_id.
INSERT INTO socialapp.t_posts
    (id, content, visibility, author_id, post_type, moderation_status, created_at, updated_at) VALUES
    (5091, 'Mình vừa hoàn thành cuốn cẩm nang tối ưu Spring Boot, gom lại kinh nghiệm 6 năm làm backend.', 'PUBLIC', 9005, 'BOOK', 'APPROVED', now() - INTERVAL '50 days', now() - INTERVAL '50 days'),
    (5092, 'Cuốn sách nhỏ về React hiện đại, viết cho người đã biết cơ bản.', 'PUBLIC', 9018, 'BOOK', 'APPROVED', now() - INTERVAL '58 days', now() - INTERVAL '58 days'),
    (5093, 'Tài liệu Kubernetes thực chiến, toàn tình huống gặp thật.', 'PUBLIC', 9033, 'BOOK', 'APPROVED', now() - INTERVAL '66 days', now() - INTERVAL '66 days'),
    (5094, 'Sách về kiểm thử tự động, kèm ví dụ chạy được.', 'PUBLIC', 9049, 'BOOK', 'APPROVED', now() - INTERVAL '74 days', now() - INTERVAL '74 days'),
    (5095, 'Cẩm nang bảo mật ứng dụng web cho lập trình viên.', 'PUBLIC', 9045, 'BOOK', 'APPROVED', now() - INTERVAL '82 days', now() - INTERVAL '82 days'),
    (5096, 'Sách nhập môn kỹ thuật dữ liệu, viết bằng tiếng Việt.', 'PUBLIC', 9039, 'BOOK', 'APPROVED', now() - INTERVAL '95 days', now() - INTERVAL '95 days');

-- ── REGULAR (5101-5190) ────────────────────────────────────────────────────────────────────
-- 90 bài viết thường. Nội dung liệt kê tường minh thay vì sinh bằng cách ghép chuỗi: nội dung
-- lặp lại theo khuôn mẫu sẽ kích hoạt DUPLICATE_CONTENT của SpamDetector khi bật kiểm duyệt, và
-- một dòng thời gian toàn câu na ná nhau thì không dùng để demo được.
--
-- Tác giả và mốc thời gian suy ra từ thứ tự dòng: rải đều 58 người và 90 ngày gần nhất, đủ để
-- phân trang theo con trỏ và sắp xếp theo thời gian có ý nghĩa.
INSERT INTO socialapp.t_posts (id, content, visibility, author_id, post_type, moderation_status, created_at, updated_at)
SELECT 5100 + t.ord,
       t.content,
       -- Đa số công khai; cứ 9 bài có 1 bài chỉ bạn bè và 1 bài riêng tư, để việc lọc theo
       -- quyền riêng tư có dữ liệu cả ba nhánh.
       CASE WHEN t.ord % 9 = 0 THEN 'FRIENDS' WHEN t.ord % 17 = 0 THEN 'PRIVATE' ELSE 'PUBLIC' END,
       9001 + (t.ord * 7 % 58),
       'REGULAR',
       'APPROVED',
       now() - ((91 - t.ord) * INTERVAL '1 day') - (t.ord * INTERVAL '17 minutes'),
       now() - ((91 - t.ord) * INTERVAL '1 day') - (t.ord * INTERVAL '17 minutes')
  FROM unnest(ARRAY[
    'Hôm nay mới biết Postgres có thể tạo index trên biểu thức, không chỉ trên cột. Tiếc là biết muộn quá.',
    'Đọc lại code mình viết sáu tháng trước và không hiểu nổi mình đã nghĩ gì. Có ai như vậy không?',
    'Bỏ ra hai ngày để tối ưu một truy vấn, cuối cùng vấn đề nằm ở chỗ thiếu một dấu index.',
    'Mẹo nhỏ khi review code: đọc test trước, đọc phần cài đặt sau. Hiểu ý đồ nhanh hơn hẳn.',
    'Cuối cùng cũng dọn xong đống cảnh báo trong log. Từ 4000 dòng một giờ xuống còn 12.',
    'Có ai từng đo thử thời gian khởi động của Spring Boot sau khi bật lazy initialization chưa?',
    'Điều khó nhất khi làm việc nhóm không phải là code, mà là thống nhất được cách đặt tên.',
    'Vừa gỡ được một bug tồn tại tám tháng. Nguyên nhân là một dấu bằng thiếu.',
    'Chuyển toàn bộ script build sang Gradle Kotlin DSL, IDE gợi ý được nên đỡ tra tài liệu hẳn.',
    'Nhận ra rằng viết tài liệu cho chính mình sáu tháng sau là động lực tốt nhất để viết tài liệu.',
    'Thử dùng Testcontainers thay cho database in-memory, test chậm hơn nhưng tin được.',
    'Cache là con dao hai lưỡi. Hôm nay lưỡi bên kia quay về phía mình.',
    'Một buổi chiều ngồi vẽ lại sơ đồ luồng dữ liệu và phát hiện ra hai chỗ gọi vòng.',
    'Ai đang dùng pnpm thay npm cho monorepo? Mình đang cân nhắc chuyển.',
    'Học được cách đọc EXPLAIN ANALYZE tử tế và thấy thế giới khác hẳn.',
    'Refactor không có test là đổi tên bug chứ không phải sửa bug.',
    'Vừa dựng xong dashboard theo dõi độ trễ theo phân vị. p99 nói nhiều điều hơn trung bình.',
    'Có ai để ý là phần lớn thời gian debug là thời gian đọc, không phải thời gian sửa không?',
    'Thử viết lại một service bằng cách bắt đầu từ interface trước. Kết quả gọn hơn mình tưởng.',
    'Một ngày tốt lành là ngày CI xanh ngay lần đầu.',
    'Đổi từ REST sang GraphQL cho một màn hình phức tạp, số request giảm từ 11 xuống 1.',
    'Bài học hôm nay: đừng bao giờ tin timestamp không có timezone.',
    'Dọn dependency không dùng tới, image Docker nhẹ đi 180MB.',
    'Có ai gặp trường hợp connection pool cạn mà không thấy lỗi gì trong log không?',
    'Viết một script nhỏ tự động hoá việc mình làm tay mỗi sáng. Tiết kiệm 10 phút mỗi ngày.',
    'Nghĩ lại thì phần lớn quyết định kỹ thuật khó là do thiếu thông tin chứ không do thiếu kỹ năng.',
    'Chuyển log sang dạng có cấu trúc, tìm kiếm sự cố nhanh hơn nhiều.',
    'Học Rust được ba tuần và vẫn đang vật lộn với borrow checker. Nhưng vui.',
    'Đo thử: bật gzip cho response API giảm băng thông gần 70% cho danh sách dài.',
    'Đọc mã nguồn thư viện mình dùng hằng ngày, hoá ra đơn giản hơn tưởng tượng nhiều.',
    'Một pull request 2000 dòng thì không ai review được. Chia nhỏ ra giúp cả người viết lẫn người đọc.',
    'Thêm health check cho mọi service, và lần đầu tiên biết chính xác cái gì đang chết.',
    'Có ai dùng feature flag trong dự án nhỏ không, hay chỉ hợp với đội lớn?',
    'Tự tay dựng lại môi trường dev từ đầu để kiểm tra tài liệu onboarding. Sai bốn chỗ.',
    'Chuyển sang dùng biến môi trường cho toàn bộ cấu hình, hết cảnh sửa file rồi quên đổi lại.',
    'Hôm nay học được: retry mà không có backoff là cách hay nhất để tự tấn công chính mình.',
    'Viết test cho phần code mình sợ đụng vào nhất. Hoá ra nó không đáng sợ như vậy.',
    'Một câu hỏi hay trong review đáng giá hơn mười bình luận khen.',
    'Dùng thử devcontainer, người mới vào dự án chạy được sau 5 phút thay vì nửa ngày.',
    'Xoá 3000 dòng code chết. Ngày làm việc thoả mãn nhất tháng.',
    'Có ai từng đo chi phí thật của việc bật tracing đầy đủ trên production chưa?',
    'Đặt tên biến cho đúng khó hơn viết thuật toán. Ai cũng biết mà vẫn cứ khó.',
    'Chuyển pipeline CI sang chạy song song, thời gian từ 22 phút xuống 7 phút.',
    'Hoá ra vấn đề chậm không nằm ở database mà ở chỗ serialize JSON.',
    'Học được cách dùng git bisect để tìm commit gây lỗi. Nên biết sớm hơn.',
    'Một hệ thống dễ vận hành quan trọng hơn một hệ thống thiết kế đẹp.',
    'Thử viết lại phần xử lý ảnh bằng luồng bất đồng bộ, throughput gấp ba.',
    'Đọc bài về CAP theorem lần thứ năm và lần này mới thật sự hiểu.',
    'Có ai biết cách giảm cold start của serverless function xuống dưới 200ms không?',
    'Thêm một dòng comment giải thích vì sao, và tiết kiệm cho người sau nửa ngày.',
    'Hôm nay tự tin xoá một đoạn code vì có test bao phủ. Cảm giác rất khác.',
    'Chạy load test lần đầu và phát hiện hệ thống chết ở 300 request mỗi giây chứ không phải 3000.',
    'Chuyển từ polling sang webhook, giảm 95% lượng request vô ích.',
    'Đọc changelog trước khi nâng phiên bản. Bài học kinh điển mà vẫn hay quên.',
    'Một cái tên tốt cho hàm thay được ba dòng comment.',
    'Thử dùng Postgres làm hàng đợi cho khối lượng nhỏ. Đơn giản hơn dựng thêm hạ tầng nhiều.',
    'Ngồi vẽ sơ đồ trước khi code, tiết kiệm được hai ngày viết nhầm hướng.',
    'Có ai đang dùng OpenTelemetry trong dự án Spring Boot chưa? Cho mình xin kinh nghiệm.',
    'Cấu hình timeout cho mọi lời gọi ra ngoài. Không có timeout là chờ vô hạn.',
    'Bỏ một buổi ngồi đọc code của người khác trong đội, học được nhiều hơn đọc tài liệu.',
    'Đổi cách viết commit message, lịch sử git đọc như một câu chuyện thay vì một đống rác.',
    'Đo lại: bật connection pooling cho Redis giảm độ trễ trung bình 40%.',
    'Học được rằng phần lớn tối ưu sớm là lãng phí, nhưng chọn đúng cấu trúc dữ liệu thì không.',
    'Có ai thấy việc pair programming mệt hơn làm một mình nhưng kết quả tốt hơn hẳn không?',
    'Dựng môi trường staging giống production, số sự cố khi deploy giảm rõ rệt.',
    'Viết lại phần validate dữ liệu ở một chỗ duy nhất thay vì rải rác khắp nơi.',
    'Hôm nay phát hiện ra mình đã dùng sai một API suốt hai năm mà vẫn chạy đúng.',
    'Một trong những kỹ năng bị đánh giá thấp nhất: biết khi nào nên hỏi thay vì tự mò.',
    'Chuyển tất cả secret sang biến môi trường và quét lại lịch sử git. Tìm thấy hai cái quên xoá.',
    'Đọc lại thiết kế cũ và nhận ra quyết định lúc đó là đúng với thông tin lúc đó.',
    'Thử nghiệm A/B đầu tiên của mình cho kết quả ngược hoàn toàn với dự đoán.',
    'Có ai dùng monorepo cho cả backend lẫn frontend không? Đang cân nhắc.',
    'Thêm chỉ số theo dõi cho hàng đợi và phát hiện nó tồn đọng suốt hai tuần qua.',
    'Học cách nói không với yêu cầu ngoài phạm vi cũng là một kỹ năng kỹ thuật.',
    'Viết một công cụ nhỏ để so sánh schema giữa các môi trường. Rất đáng công.',
    'Nhận ra rằng tài liệu tốt nhất là tài liệu nằm cạnh code và được cập nhật cùng code.',
    'Có ai từng bị nhầm giữa UTC và giờ địa phương khi tính báo cáo hằng ngày chưa?',
    'Chuyển từ cron sang scheduler có trạng thái, hết cảnh job chạy trùng nhau.',
    'Dành một ngày mỗi tháng để trả nợ kỹ thuật. Đội mình duy trì được nửa năm rồi.',
    'Đọc mã nguồn Spring Framework để hiểu cách nó tự cấu hình. Học được rất nhiều.',
    'Một hệ thống không có cảnh báo là một hệ thống bạn chỉ biết hỏng khi khách hàng gọi.',
    'Thử giới hạn số lượng kết quả trả về mặc định. Đơn giản mà giảm tải đáng kể.',
    'Có ai đang dùng Neo4j cho dữ liệu quan hệ xã hội không? Cho mình xin lời khuyên.',
    'Hôm nay viết ít code nhất tuần nhưng giải quyết được nhiều vấn đề nhất.',
    'Đưa migration vào CI để mỗi PR đều kiểm tra được schema chạy sạch từ đầu.',
    'Đổi sang dùng khoá ngoại có ON DELETE CASCADE ở đúng chỗ, code dọn dẹp gọn hẳn.',
    'Nhận ra là mình đọc code nhiều gấp mười lần viết code. Nên tối ưu cho người đọc.',
    'Có ai thấy việc viết lại tài liệu API bằng tay là việc nên tự động hoá không?',
    'Chuyển tất cả về múi giờ UTC trong database, hiển thị theo giờ địa phương ở tầng giao diện.',
    'Kết thúc tuần với CI xanh, không có sự cố, và một PR đã merge. Đủ vui rồi.'
  ]) WITH ORDINALITY AS t(content, ord);

-- ── Bài có kèm quiz (5201-5205) ────────────────────────────────────────────────────────────
-- quiz_details KHÔNG phải một PostType riêng — enum PostType không có giá trị QUIZ. Bất kỳ bài
-- nào cũng có thể mang một quiz, và QuizService chỉ kiểm tra post.quizDetails có khác null hay
-- không. Ở đây gắn vào bài REGULAR và ARTICLE cho giống cách dùng thật.
--
-- QuizDetails{title,questions[QuizQuestion{question,options,correctOptionIndex,explanation}]}.
-- Ràng buộc mà PostService.validateQuizDetails áp: tiêu đề không rỗng, có ít nhất một câu hỏi,
-- mỗi câu ít nhất 2 lựa chọn, và correctOptionIndex phải nằm trong khoảng của options. Mỗi quiz
-- dưới đây có đúng 3 câu, vì QuizService bắt bài nộp phải có SỐ ĐÁP ÁN BẰNG số câu hỏi — V54
-- nộp bài dựa vào con số 3 này.
INSERT INTO socialapp.t_posts
    (id, content, visibility, author_id, post_type, moderation_status, quiz_details, created_at, updated_at) VALUES
    (5201, 'Làm thử bài kiểm tra nhỏ về giao dịch trong Spring xem mình nắm chắc tới đâu.', 'PUBLIC', 9001, 'REGULAR', 'APPROVED',
     '{"title":"Kiểm tra nhanh: @Transactional","questions":[{"question":"Mặc định Spring rollback khi gặp loại ngoại lệ nào?","options":["Mọi Exception","Chỉ RuntimeException và Error","Chỉ checked exception","Không tự rollback"],"correctOptionIndex":1,"explanation":"Mặc định chỉ rollback với unchecked exception; muốn rollback cho checked exception phải khai báo rollbackFor."},{"question":"Gọi một phương thức @Transactional từ chính bên trong cùng lớp thì sao?","options":["Vẫn mở giao dịch bình thường","Proxy bị bỏ qua nên không có giao dịch","Ném lỗi khi khởi động","Tạo giao dịch lồng nhau"],"correctOptionIndex":1,"explanation":"Giao dịch được cài qua proxy, gọi nội bộ không đi qua proxy nên annotation không có tác dụng."},{"question":"readOnly = true mang lại lợi ích gì rõ nhất?","options":["Tăng tốc ghi","Hibernate bỏ qua dirty checking","Tự động thêm index","Nén dữ liệu trả về"],"correctOptionIndex":1,"explanation":"Không cần so sánh trạng thái trước và sau nên tiết kiệm được bộ nhớ lẫn thời gian ở cuối giao dịch."}]}'::jsonb,
     now() - INTERVAL '15 days', now() - INTERVAL '15 days'),
    (5202, 'Bài kiểm tra nhỏ về index trong Postgres, ai làm đúng cả ba câu thì giỏi đấy.', 'PUBLIC', 9003, 'REGULAR', 'APPROVED',
     '{"title":"Kiểm tra nhanh: Index Postgres","questions":[{"question":"Partial index là gì?","options":["Index chỉ trên một phần hàng thoả điều kiện WHERE","Index chia nhỏ theo phân vùng","Index chỉ chứa một phần giá trị cột","Index tạm thời trong bộ nhớ"],"correctOptionIndex":0,"explanation":"Partial index chỉ lập chỉ mục cho các hàng khớp mệnh đề WHERE, nên nhỏ và rẻ hơn nhiều."},{"question":"Thứ tự cột trong index tổ hợp có quan trọng không?","options":["Không, Postgres tự sắp lại","Có, truy vấn phải dùng tiền tố trái của index","Chỉ quan trọng với index UNIQUE","Chỉ quan trọng khi có ORDER BY"],"correctOptionIndex":1,"explanation":"Index B-tree tổ hợp chỉ dùng được khi điều kiện phủ từ cột đầu tiên trở đi."},{"question":"GIN index hợp nhất với kiểu dữ liệu nào?","options":["integer","jsonb và mảng","boolean","timestamp"],"correctOptionIndex":1,"explanation":"GIN thiết kế cho giá trị chứa nhiều phần tử con như jsonb, mảng, và full-text search."}]}'::jsonb,
     now() - INTERVAL '25 days', now() - INTERVAL '25 days'),
    (5203, 'Quiz kèm bài viết về React, kiểm tra lại phần hook.', 'PUBLIC', 9011, 'ARTICLE', 'APPROVED',
     '{"title":"Kiểm tra nhanh: React Hooks","questions":[{"question":"useEffect không truyền mảng phụ thuộc thì chạy khi nào?","options":["Chỉ một lần khi mount","Sau mỗi lần render","Không bao giờ chạy","Chỉ khi unmount"],"correctOptionIndex":1,"explanation":"Thiếu mảng phụ thuộc nghĩa là effect chạy lại sau mọi lần render."},{"question":"useMemo dùng để làm gì?","options":["Ghi nhớ kết quả tính toán tốn kém","Thay thế useState","Gọi API","Quản lý route"],"correctOptionIndex":0,"explanation":"useMemo giữ lại kết quả và chỉ tính lại khi phụ thuộc thay đổi."},{"question":"Vì sao không được gọi hook trong vòng lặp hoặc câu điều kiện?","options":["Vì cú pháp không cho phép","Vì React dựa vào thứ tự gọi hook giữa các lần render","Vì sẽ gây rò rỉ bộ nhớ","Vì hook chỉ chạy trên server"],"correctOptionIndex":1,"explanation":"React khớp state với hook theo thứ tự gọi, thứ tự đổi giữa các lần render là state gắn nhầm chỗ."}]}'::jsonb,
     now() - INTERVAL '32 days', now() - INTERVAL '32 days'),
    (5204, 'Kiểm tra kiến thức Docker cơ bản, làm trong một phút thôi.', 'PUBLIC', 9031, 'REGULAR', 'APPROVED',
     '{"title":"Kiểm tra nhanh: Docker","questions":[{"question":"Lệnh nào tạo thêm một lớp mới trong image?","options":["RUN","WORKDIR","ENV","EXPOSE"],"correctOptionIndex":0,"explanation":"RUN thực thi lệnh và ghi kết quả thành một lớp mới; WORKDIR, ENV, EXPOSE chỉ là siêu dữ liệu."},{"question":"Vì sao nên copy file khai báo phụ thuộc trước rồi mới copy mã nguồn?","options":["Để image nhỏ hơn","Để tận dụng cache lớp khi mã nguồn thay đổi","Để tăng bảo mật","Không có khác biệt"],"correctOptionIndex":1,"explanation":"Phụ thuộc ít đổi hơn mã nguồn, tách ra thì lớp cài đặt phụ thuộc được dùng lại từ cache."},{"question":"Multi-stage build giải quyết vấn đề gì?","options":["Chạy nhiều container cùng lúc","Loại bỏ công cụ build khỏi image cuối","Tăng tốc mạng","Quản lý biến môi trường"],"correctOptionIndex":1,"explanation":"Chỉ copy sản phẩm build sang stage cuối, nên JDK và cache build không nằm trong image chạy thật."}]}'::jsonb,
     now() - INTERVAL '40 days', now() - INTERVAL '40 days'),
    (5205, 'Quiz về kiểm thử, mời cả nhà thử sức.', 'PUBLIC', 9049, 'REGULAR', 'APPROVED',
     '{"title":"Kiểm tra nhanh: Kiểm thử","questions":[{"question":"Test đôi khi xanh đôi khi đỏ mà không đổi code gọi là gì?","options":["Flaky test","Smoke test","Regression test","Golden test"],"correctOptionIndex":0,"explanation":"Flaky test thường do phụ thuộc thời gian, thứ tự chạy, hoặc trạng thái dùng chung giữa các test."},{"question":"Kim tự tháp kiểm thử khuyến nghị điều gì?","options":["Nhiều e2e, ít unit","Nhiều unit, ít e2e","Chỉ viết e2e","Chỉ viết unit"],"correctOptionIndex":1,"explanation":"Unit test rẻ và nhanh nên làm nền; e2e đắt và chậm nên chỉ giữ số lượng nhỏ ở đỉnh."},{"question":"Độ phủ 100% bảo đảm điều gì?","options":["Không còn lỗi","Mọi dòng đã được thực thi ít nhất một lần","Hiệu năng đạt yêu cầu","Mọi tình huống đã được kiểm tra"],"correctOptionIndex":1,"explanation":"Độ phủ chỉ đo dòng nào đã chạy qua, không đo việc có kiểm chứng kết quả đúng hay không."}]}'::jsonb,
     now() - INTERVAL '47 days', now() - INTERVAL '47 days');

-- ── Bài đang chờ kiểm duyệt / đã bị từ chối ────────────────────────────────────────────────
-- Hàng chờ ở GET /v1/api/admin/moderation/pending cần dữ liệu, nếu không màn hình quản trị trống
-- trơn. Nội dung cố tình mang dấu hiệu spam và công kích ở mức đủ nhận ra nhưng không thô tục,
-- vì đây là dữ liệu dev mà cả đội sẽ nhìn thấy hằng ngày.
INSERT INTO socialapp.t_posts (id, content, visibility, author_id, post_type, moderation_status, created_at, updated_at) VALUES
    (5191, 'SIÊU KHUYẾN MÃI hôm nay giảm 90 phần trăm, inbox ngay kẻo hết, số lượng có hạn!!!', 'PUBLIC', 9007, 'REGULAR', 'PENDING_REVIEW', now() - INTERVAL '2 days', now() - INTERVAL '2 days'),
    (5192, 'Kiếm tiền online 20 triệu một tháng không cần kinh nghiệm, liên hệ ngay số bên dưới!!!', 'PUBLIC', 9013, 'REGULAR', 'PENDING_REVIEW', now() - INTERVAL '1 day', now() - INTERVAL '1 day'),
    (5193, 'Code kiểu này thì nghỉ làm đi cho rồi, ai thuê mấy người kém cỏi như vậy không biết.', 'PUBLIC', 9028, 'REGULAR', 'PENDING_REVIEW', now() - INTERVAL '3 days', now() - INTERVAL '3 days'),
    (5194, 'Bấm vào link này để nhận quà miễn phí, nhanh tay lên chỉ còn hôm nay thôi!!!', 'PUBLIC', 9051, 'REGULAR', 'PENDING_REVIEW', now() - INTERVAL '4 days', now() - INTERVAL '4 days'),
    (5195, 'Bán tài khoản khoá học giá rẻ, ai cần inbox, có đủ mọi nền tảng.', 'PUBLIC', 9007, 'REGULAR', 'REJECTED', now() - INTERVAL '12 days', now() - INTERVAL '12 days'),
    (5196, 'Nội dung vi phạm đã bị gỡ sau khi kiểm duyệt tự động đánh dấu.', 'PUBLIC', 9028, 'REGULAR', 'REJECTED', now() - INTERVAL '20 days', now() - INTERVAL '20 days'),
    (5197, 'Bài này vừa đăng, đang đợi hệ thống chấm.', 'PUBLIC', 9022, 'REGULAR', 'PENDING_MODERATION', now() - INTERVAL '2 hours', now() - INTERVAL '2 hours'),
    (5198, 'Một bài khác cũng vừa đăng xong.', 'PUBLIC', 9040, 'REGULAR', 'PENDING_MODERATION', now() - INTERVAL '1 hour', now() - INTERVAL '1 hour');

-- ── Gắn hashtag cho bài ────────────────────────────────────────────────────────────────────
-- Mỗi bài 1-3 hashtag, chọn tất định theo id bài để chạy lại cho ra kết quả y hệt.
INSERT INTO socialapp.t_post_hashtags (post_id, hashtag_id)
SELECT p.id, 1001 + ((p.id * 3) % 30)
  FROM socialapp.t_posts p
 WHERE p.id BETWEEN 5001 AND 5299
UNION
SELECT p.id, 1001 + ((p.id * 7 + 11) % 30)
  FROM socialapp.t_posts p
 WHERE p.id BETWEEN 5001 AND 5299 AND p.id % 2 = 0
UNION
SELECT p.id, 1001 + ((p.id * 13 + 5) % 30)
  FROM socialapp.t_posts p
 WHERE p.id BETWEEN 5001 AND 5299 AND p.id % 3 = 0;

-- usage_count phải khớp số bài thật sự gắn thẻ, nếu không trang hashtag hiện một con số còn
-- danh sách bên dưới lại dài ngắn khác hẳn.
UPDATE socialapp.t_hashtags h
   SET usage_count = COALESCE(c.n, 0)
  FROM (SELECT hashtag_id, count(*) AS n FROM socialapp.t_post_hashtags GROUP BY hashtag_id) c
 WHERE h.id = c.hashtag_id;

-- ── Gắn thẻ người trong bài ────────────────────────────────────────────────────────────────
-- Khoá chính là (post_id, position) nên position phải bắt đầu từ 0 và liên tục trong từng bài.
-- Chỉ gắn thẻ ở bài công khai, và không ai tự gắn thẻ chính mình.
INSERT INTO socialapp.t_post_tags (post_id, position, tagged_user_id)
SELECT p.id, 0, 9001 + ((p.id * 11) % 58)
  FROM socialapp.t_posts p
 WHERE p.id BETWEEN 5101 AND 5190 AND p.id % 6 = 0
   AND 9001 + ((p.id * 11) % 58) <> p.author_id
UNION ALL
SELECT p.id, 1, 9001 + ((p.id * 19 + 7) % 58)
  FROM socialapp.t_posts p
 WHERE p.id BETWEEN 5101 AND 5190 AND p.id % 12 = 0
   AND 9001 + ((p.id * 19 + 7) % 58) <> p.author_id
   AND 9001 + ((p.id * 19 + 7) % 58) <> 9001 + ((p.id * 11) % 58);

-- ── Đẩy sequence qua vùng id tường minh ────────────────────────────────────────────────────
-- Bắt buộc. Không có bước này, bài viết đầu tiên tạo qua API sẽ lấy id 1 và đụng ngay khoá chính
-- với dữ liệu seed — lỗi chỉ lộ ra khi có người bấm Đăng, chứ không phải lúc nạp seed.
SELECT setval('socialapp.q_posts_id', (SELECT MAX(id) FROM socialapp.t_posts), true);
SELECT setval('socialapp.t_hashtags_id_seq', (SELECT MAX(id) FROM socialapp.t_hashtags), true);
