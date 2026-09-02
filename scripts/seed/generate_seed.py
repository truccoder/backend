#!/usr/bin/env python3
"""Sinh toàn bộ bộ seed 500 người dùng — SQL, đồ thị Neo4j, manifest ảnh, bảng ID.

    python scripts/seed/generate_seed.py

Đầu ra:

    src/main/resources/db/seed/V81…V90.sql   (V80 reset và V92 fixture viết tay)
    src/main/resources/db/seed/friend-graph.cypher
    src/main/resources/db/seed/seed-manifest.tsv
    scripts/seed/id-map.md

TẤT ĐỊNH. random.Random(SEED) với hằng số cố định: chạy lại cho ra y hệt, `git diff` sạch nếu
không đổi tham số. Đây không phải chi tiết phong cách — nó là thứ khiến việc sửa một dòng trong
bộ seed review được, thay vì mỗi lần sinh lại là một diff 120.000 dòng.

BA LỚP TỰ CANH, và cả ba đều canh những thứ KHÔNG CÓ TEST NÀO BẮT ĐƯỢC:

  1. Trùng số version. Flyway phân giải một dãy version duy nhất trên cả db/migration lẫn db/seed,
     nên một số đã bị db/migration chiếm sẽ làm app KHÔNG KHỞI ĐỘNG ĐƯỢC. Dãy này đã bị chiếm mất hai lần
     trong ba ngày (V71-V73, rồi V74/V76/V77/V78) — nên đây là kiểm tra chạy mỗi lần sinh, không
     phải một lời dặn trong tài liệu.

  2. Chuỗi ${...} lọt vào SQL. Flyway thay placeholder ở tầng ĐỌC FILE, trước khi parse SQL, nên
     một ${...} không được khai giết migration KỂ CẢ khi nó nằm trong dòng comment. Đã xảy ra
     đúng một lần ở V51.

  3. Thiếu `SET LOCAL statement_timeout = 0`. application.yml đặt statement_timeout = 15s lên
     pool Hikari, và Flyway DÙNG CHUNG DataSource đó. SeedMigrationTest mở JDBC thô nên không đi
     qua Hikari — seed sẽ XANH Ở CI rồi CHẾT Ở PRODUCTION giữa lúc migrate. Không có test nào
     trong repo phát hiện được, nên lớp chặn nằm ở đây và trong chính file SQL.
"""

import json
import random
import re
import sys
import unicodedata
from datetime import date, timedelta
from pathlib import Path

# Trên Windows, stdout của Python mặc định là cp1252 và KHÔNG mã hoá được tiếng Việt: mọi dòng
# tiến trình dưới đây sẽ ném UnicodeEncodeError giữa chừng, sau khi script đã ghi được một phần
# số file. Việc ghi file thì không dính (mọi chỗ ghi đều ép encoding="utf-8"), nên triệu chứng là
# một lần sinh hỏng dở dang mà nguyên nhân trông không liên quan gì tới nội dung.
sys.stdout.reconfigure(encoding="utf-8")
sys.stderr.reconfigure(encoding="utf-8")

sys.path.insert(0, str(Path(__file__).resolve().parent))

SEED = 20260828

# Mốc "hôm nay" của bộ seed. GIỮ CỐ ĐỊNH, KHÔNG dùng date.today(): đầu ra phải tất định (xem chú
# thích đầu file). Chỉ dùng cho vài giá trị thời gian TUYỆT ĐỐI không viết được dưới dạng
# `now() - INTERVAL` — startTime/endTime của EVENT và endDate của POLL, vốn là chuỗi ISO nằm trong
# jsonb. Mọi mốc thời gian khác vẫn tương đối theo now() lúc migrate. Regen lâu sau mốc này thì
# cập nhật hằng số rồi chạy lại — cùng tinh thần với ngày xác minh trong book_catalog.py.
TODAY = date(2026, 8, 30)

ROOT = Path(__file__).resolve().parents[2]
SEED_DIR = ROOT / "src" / "main" / "resources" / "db" / "seed"
MIGRATION_DIR = ROOT / "src" / "main" / "resources" / "db" / "migration"
# Cypher nằm CÙNG CHỖ với các file SQL mà nó phải khớp, và được đóng vào jar nên
# Neo4jSeedInitializer đọc được ở mọi môi trường — kể cả production, nơi không có
# docker-compose nào của repo này chạy.
NEO4J_SEED = SEED_DIR / "friend-graph.cypher"
# Manifest ảnh nằm CÙNG CHỖ với friend-graph.cypher và các file SQL — trong resources, đóng vào
# jar — nên MinIOSeedObjectInitializer đọc được trên production, nơi không có docker-compose nào
# của repo này chạy. Máy dev vẫn dùng chính file này qua service minio-seed-objects (mount
# src/main/resources/db/seed vào /manifest).
MINIO_MANIFEST = SEED_DIR / "seed-manifest.tsv"
ID_MAP = ROOT / "scripts" / "seed" / "id-map.md"

# ── Dãy version ────────────────────────────────────────────────────────────────────────────────
# V80 và V92 viết tay; generator sinh V81-V90.
#
# Vì sao bắt đầu ở 80: db/migration đã dùng tới V78 và db/seed đã có V75/V79. Số cao nhất đang
# tồn tại là 79. Bỏ trống 75 và 79 sau khi xoá hai file đó là chấp nhận được — Flyway không đòi
# dãy liền mạch, và out-of-order: false chỉ cấm chèn số THẤP HƠN số đã apply.
#
# BỘ SEED ĐÃ ĐƯỢC RE-BASELINE 2026-08-30: V88 mang lại parent_node_id (cây lộ trình), và mọi file
# V81-V92 được sinh lại một lượt. Deploy nào ship bản này PHẢI drop schema production trước khi
# migrate (xem scripts/prod/rebaseline-seed.sql và README) — nếu không, checksum V88 cũ lệch với
# file mới và Flyway chặn khởi động. V95 (một UPDATE gắn cây chạy sau V88) đã bị xoá: sau re-baseline
# nó thừa.
#
# V91 (seed_trending) BỎ HẲN sau đó: TrendingCrawlScheduler tự lấp bảng trong giờ đầu BE chạy, nên
# 33 tin "seed-N" trỏ tin-tuc.example.test chỉ là dữ liệu giả nằm chờ bị ghi đè — và trong lúc chờ
# nó dán nhãn "dữ liệu mẫu" ngay trên trang chủ, kể cả trên production. Số 91 để trống, không dồn
# 92 xuống: V92 (fixture, viết tay) giữ nguyên số của nó.
HAND_WRITTEN = {80: "seed_reset", 92: "seed_ui_fixtures"}
GENERATED = {
    81: "seed_users",
    82: "seed_social_graph",
    83: "seed_posts",
    84: "seed_engagement",
    85: "seed_bookstore",
    86: "seed_knowledge",
    87: "seed_projects",
    88: "seed_roadmaps",
    89: "seed_moderation",
    90: "seed_reputation_and_notifications",
}

# Ngưỡng số dòng dữ liệu mà trên đó file BẮT BUỘC phải mở trần thời gian. Đặt thấp hơn nhiều so
# với chỗ thực sự nguy hiểm (hàng chục nghìn dòng): thà thừa một dòng SET LOCAL vô hại còn hơn
# thiếu nó ở một file vừa đủ chậm để chết trên phần cứng production.
TIMEOUT_GUARD_ROWS = 2000

# Những version LUÔN mang dòng đó, kể cả khi số hàng dưới ngưỡng. V87 (~600 hàng) và V89 (~860)
# nhẹ hơn ngưỡng nên tự chúng không kích hoạt, nhưng bước kiểm chứng của plan là một lệnh grep quét
# thẳng V82–V90 và đòi KHÔNG file nào thiếu. Để ngưỡng quyết định một mình thì lệnh ấy báo đỏ ở hai
# file hoàn toàn lành — và lần sau người ta sẽ sửa cái lệnh grep thay vì sửa file, rồi lớp canh này
# mất tác dụng lúc nào không hay. V81 cũng vào danh sách: 1.480 hàng vào một bảng có chỉ mục
# trigram thì con số hàng không nói hết chi phí thật.
TIMEOUT_ALWAYS_VERSIONS = {81, 82, 83, 84, 85, 86, 87, 88, 89, 90}

TIMEOUT_PREAMBLE = """-- Flyway chạy file này qua chính pool của ứng dụng, nơi application.yml đặt
-- statement_timeout = 15s cho MỌI kết nối. Trần đó đúng cho một request người dùng và sai cho một
-- lần nạp dữ liệu hàng chục nghìn hàng. LOCAL: chỉ có hiệu lực trong giao dịch của migration này,
-- không rò sang bất kỳ kết nối nào khác của ứng dụng — dùng `SET` trần sẽ nới trần cho cả
-- connection sau khi nó được trả về pool, tức vô hiệu hoá một lớp bảo vệ có chủ đích ở một chỗ
-- hoàn toàn không liên quan.
SET LOCAL statement_timeout = 0;"""

TIMEOUT_MARKER = "SET LOCAL statement_timeout = 0;"

# Placeholder DUY NHẤT được phép trong file seed. Xem application.yml, spring.flyway.placeholders.
ALLOWED_PLACEHOLDER = "${minioUrl}"

VERSION_RE = re.compile(r"^V(\d+)__")


# ═══ Hằng số miền ══════════════════════════════════════════════════════════════════════════════

# TLD `.test` được RFC 2606 dành riêng cho thử nghiệm: không định tuyến được và không ai đăng ký
# được. KHÔNG đổi sang một tên miền có thật (kể cả .vn của chính dự án) trừ khi nhóm sở hữu nó và
# kiểm soát hòm thư ở đó: bộ seed này chạy TRÊN PRODUCTION và luồng đặt lại mật khẩu đang hoạt
# động, nên ai nhận được thư ở tên miền đó là chiếm được cả 500 tài khoản. Đây chính là lý do thế
# hệ seed trước bỏ @test.com và @socialapp.com — xem khối chú thích đầu V51.
EMAIL_DOMAIN = "elitenexus.test"

# Hash BCrypt strength 10, sinh bằng chính BCryptPasswordEncoder của app.
#
# Bộ seed chạy cả ở production, nên hai chuỗi này là mật khẩu THẬT của môi trường thật, nằm công
# khai trong repo. Đánh đổi đã cân nhắc và chấp nhận (2026-08-21): mật khẩu demo phải in được vào
# tài liệu bảo vệ đồ án. Runbook production nhắc đổi mật khẩu hai tài khoản admin qua API ngay sau
# khi seed xong — đó mới là thứ đóng lỗ này lại.
#
# Hai hash dưới đây được SeedPasswordHashTest kiểm bằng chính BCryptPasswordEncoder mà đường đăng
# nhập dùng. Đừng sửa tay: hash sai không làm migration đỏ, không làm test nào khác đỏ, và không
# có gì đọc tới nó cho đến khi một con người gõ mật khẩu vào ô đăng nhập — tức là chỗ phát hiện
# tự nhiên của lỗi này là buổi bảo vệ đồ án. Đổi mật khẩu thì đổi cả hằng số trong test đó.
PASSWORD_USER = "$2a$10$ttfkq0.2lDTzWYQnxTotx.m50oiP/bhWDZta9cx1EcjfRmFZREhoK"   # 12qwaszx
PASSWORD_ADMIN = "$2a$10$u3fdshcpY2MWYbq.ZeDsseBNhaH23uPUI72ZRmufV2e.ufb8oDjZe"  # 1234qwer

USER_ID_FIRST = 9001
USER_ID_LAST = 9500
ADMIN_IDS = (9499, 9500)
DEMO_EXPERT = 9001
DEMO_NEWCOMER = 9002

# ── Từ vựng chủ đề: MỘT nguồn duy nhất cho bốn cột jsonb ───────────────────────────────────────
#
# t_projects.tags, t_user_professional_profiles.interested_domains / known_tech_stack, và
# t_project_positions.required_skills đều được MatchmakingService + ProfileMatchScorer.skillOverlap
# so bằng phép GIAO. Sinh mỗi cột từ một kho từ riêng thì phép giao luôn rỗng,
# GET /v1/api/projects/suggested và /projects/{id}/candidates trả mảng trống, và KHÔNG CÓ GÌ BÁO
# LỖI — endpoint vẫn 200. Vì vậy cả bốn cột rút từ đúng bảng này.
#
# Giữ nguyên đúng bộ từ của V51/V75 chứ không viết lại: dữ liệu người dùng thật trên production đã
# được nhập theo bộ này, và đổi từ vựng ở đây sẽ làm hồ sơ thật ngừng khớp với dự án seed.
DOMAINS = {
    "BACKEND": ["Distributed Systems", "API Design", "Database Internals"],
    "FRONTEND": ["Design Systems", "Web Performance", "Accessibility"],
    "FULLSTACK": ["Developer Experience", "API Design", "Web Performance"],
    "MOBILE": ["Mobile UX", "Offline First", "Cross-platform"],
    "DEVOPS": ["Observability", "Infrastructure as Code", "Cost Optimization"],
    "DATA_ML": ["MLOps", "Recommendation Systems", "Data Quality"],
    "SECURITY": ["AppSec", "Threat Modeling", "Supply Chain Security"],
    "QA": ["Test Automation", "Performance Testing", "Quality Culture"],
    "OTHER": ["Product Discovery", "User Research", "Growth"],
}

TECH_STACK = {
    "BACKEND": ["Java", "Spring Boot", "PostgreSQL", "Redis", "Docker", "Kafka"],
    "FRONTEND": ["TypeScript", "React", "Next.js", "TailwindCSS", "Vite", "Zustand"],
    "FULLSTACK": ["TypeScript", "Node.js", "React", "PostgreSQL", "Docker", "GraphQL"],
    "MOBILE": ["Kotlin", "Swift", "Flutter", "Dart", "Firebase", "Jetpack Compose"],
    "DEVOPS": ["Kubernetes", "Terraform", "AWS", "Docker", "Prometheus", "GitHub Actions"],
    "DATA_ML": ["Python", "PyTorch", "Pandas", "Spark", "Airflow", "DuckDB"],
    "SECURITY": ["Burp Suite", "OWASP ZAP", "Wireshark", "Python", "Nmap", "Semgrep"],
    "QA": ["Playwright", "Selenium", "JUnit", "Postman", "k6", "Cypress"],
    "OTHER": ["Figma", "Notion", "Jira", "Amplitude", "Miro"],
}

# Bao nhiêu người mỗi vai. Cố ý KHÔNG chia đều: bảng xếp hạng, gợi ý kết bạn và matchmaking đều
# thú vị hơn khi các nhóm lệch cỡ nhau, và một phân bố phẳng lì trông giả ngay từ cái nhìn đầu.
# Tổng đúng 498; hai chỗ còn lại là hai tài khoản ADMIN.
ROLE_QUOTA = [
    ("BACKEND", 90),
    ("FRONTEND", 80),
    ("FULLSTACK", 60),
    ("MOBILE", 50),
    ("DEVOPS", 50),
    ("DATA_ML", 45),
    ("QA", 40),
    ("SECURITY", 35),
    ("OTHER", 48),
]

SENIORITY_BY_YEARS = [(2, "JUNIOR"), (5, "MID"), (9, "SENIOR"), (13, "LEAD"), (99, "PRINCIPAL")]

JOB_TITLE = {
    "BACKEND": "Kỹ sư Backend",
    "FRONTEND": "Kỹ sư Frontend",
    "FULLSTACK": "Kỹ sư Fullstack",
    "MOBILE": "Kỹ sư Mobile",
    "DEVOPS": "Kỹ sư DevOps",
    "DATA_ML": "Kỹ sư Dữ liệu",
    "SECURITY": "Kỹ sư An toàn thông tin",
    "QA": "Kỹ sư Kiểm thử",
    "OTHER": "Chuyên viên Sản phẩm",
}

# PHẢI là đúng bốn hằng của knowledge.entity.enums.ExplanationStyle. Cột này ánh xạ bằng
# @Enumerated(EnumType.STRING), nên một giá trị lạ KHÔNG bị Flyway chặn (cột là varchar, không có
# CHECK) mà nổ ở tầng Hibernate lúc đọc hàng — và hàng này được đọc ngay trong luồng đăng nhập
# (TokenService.issueTokens tra jobTitle), nên người dùng dính phải sẽ nhận 500 khi đăng nhập chứ
# không phải một màn hình hồ sơ thiếu chữ.
EXPLANATION_STYLE = ["CONCISE", "DETAILED", "ANALOGY_HEAVY", "CODE_HEAVY"]

COMPANIES = [
    "FPT Software", "VNG Corporation", "Tiki", "MoMo", "Shopee Việt Nam", "Viettel Digital",
    "VNPAY", "Zalo", "Base.vn", "Got It AI", "KMS Technology", "NashTech",
]
WORK_DOMAINS = ["E-commerce", "Fintech", "Logistics", "EdTech", "Social", "HealthTech"]

# ── Kho tên người Việt ─────────────────────────────────────────────────────────────────────────
HO = ["Nguyễn", "Trần", "Lê", "Phạm", "Hoàng", "Huỳnh", "Phan", "Vũ", "Võ", "Đặng",
      "Bùi", "Đỗ", "Hồ", "Ngô", "Dương", "Lý", "Đinh", "Tô", "Trương", "Mai"]
DEM_NAM = ["Văn", "Hữu", "Đức", "Minh", "Quang", "Thanh", "Xuân", "Gia", "Bảo", "Khánh",
           "Tuấn", "Hải", "Nhật", "Quốc", "Trung", "Anh", "Công", "Duy", "Tiến", "Thành"]
DEM_NU = ["Thị", "Ngọc", "Thu", "Hoài", "Phương", "Khánh", "Thanh", "Minh", "Bảo", "Diệu",
          "Hà", "Kim", "Lan", "Mỹ", "Như", "Quỳnh", "Thuỳ", "Trúc", "Tường", "Yên"]
TEN_NAM = ["An", "Bình", "Cường", "Dũng", "Duy", "Đạt", "Giang", "Hải", "Hiếu", "Hoà",
           "Huy", "Khoa", "Lâm", "Long", "Nam", "Phong", "Phúc", "Quân", "Sơn", "Tâm",
           "Thắng", "Tiến", "Trung", "Tú", "Tuấn", "Vinh", "Kiên", "Nghĩa", "Thịnh", "Vũ"]
TEN_NU = ["Anh", "Chi", "Dung", "Hà", "Hạnh", "Hoa", "Huyền", "Lan", "Linh", "Mai",
          "Nga", "Ngân", "Nhung", "Oanh", "Phương", "Quyên", "Thảo", "Thu", "Trang", "Trâm",
          "Tuyết", "Vy", "Yến", "Ánh", "Diệp", "Giang", "Hân", "Khuê", "My", "Nhi"]


def strip_accents(text):
    """Bỏ dấu tiếng Việt để dựng username.

    unicodedata.normalize("NFD") tách dấu thành ký tự tổ hợp riêng rồi lọc đi được — nhưng `đ`
    KHÔNG phải `d` cộng dấu, nó là một chữ cái riêng và không tách ra được. Bỏ sót ca đó thì
    "Đặng" ra "ng" thay vì "dang", tức là một username hỏng mà chỉ ai đọc kỹ mới thấy.
    """
    text = text.replace("đ", "d").replace("Đ", "D")
    decomposed = unicodedata.normalize("NFD", text)
    return "".join(c for c in decomposed if unicodedata.category(c) != "Mn")


def q(value):
    """Trích dẫn một chuỗi cho SQL, hoặc NULL."""
    if value is None:
        return "NULL"
    return "'" + str(value).replace("'", "''") + "'"


def jdoc(obj):
    """Một document JSON thành literal jsonb.

    Dùng json.dumps chứ KHÔNG tự ghép chuỗi. Tự escape bằng tay đã sinh ra JSON hỏng đúng một lần
    trong lúc dựng file này: một ký tự TAB thô trong đoạn mã Go lọt nguyên vào chuỗi JSON, và
    Postgres từ chối ::jsonb ở giữa migration. json.dumps escape đủ mọi ký tự điều khiển, dấu nháy
    và dấu chéo ngược; ensure_ascii=False giữ tiếng Việt đọc được trong file .sql.
    """
    return q(json.dumps(obj, ensure_ascii=False, separators=(",", ":"))) + "::jsonb"


def jsonb(items):
    """Một MẢNG thành literal jsonb, phần tử thuộc kiểu gì cũng được.

    Trước đây hàm này tự ghép chuỗi và bọc mọi phần tử trong dấu nháy kép, tức nó ngầm giả định
    "mảng nào cũng là mảng chuỗi". Giả định đó đúng với `concepts`, `tags`, `known_tech_stack`…
    và SAI với `t_explanations.external_links`, vốn là mảng đối tượng `{title, url, reason}` —
    ở đó `str(dict)` cho ra một chuỗi Python (dấu nháy ĐƠN) nhét vào một chuỗi JSON. Postgres
    nhận, vì đó vẫn là jsonb hợp lệ: một mảng chứa một chuỗi. Hibernate mới là chỗ nổ khi đọc.

    Uỷ quyền cho jdoc() nên chỉ còn MỘT đường sinh JSON trong cả script, và đường đó là
    json.dumps — xem jdoc() về lần TAB thô làm hỏng migration.
    """
    return jdoc(list(items))


# ═══ Lớp tự canh 1: trùng số version ═══════════════════════════════════════════════════════════

def scan_existing_versions():
    """Số version đang tồn tại, kèm đường dẫn, gom từ cả db/migration lẫn db/seed.

    Quét cả hai vì Flyway phân giải chung một dãy: trùng số giữa db/seed và db/migration là lỗi
    khởi động ("Found more than one migration with version N"), không phải cảnh báo bỏ qua được.
    """
    found = {}
    for directory in (MIGRATION_DIR, SEED_DIR):
        if not directory.is_dir():
            continue
        for path in sorted(directory.glob("V*.sql")):
            match = VERSION_RE.match(path.name)
            if match:
                found.setdefault(int(match.group(1)), []).append(path)
    return found


def guard_version_collisions(will_write):
    """Dừng nếu một số ta định ghi đã bị chiếm bởi file khác file ta sắp ghi đè."""
    existing = scan_existing_versions()
    problems = []
    for version, name in sorted(will_write.items()):
        target = (SEED_DIR / f"V{version}__{name}.sql").resolve()
        for path in existing.get(version, []):
            if path.resolve() != target:
                problems.append(f"  V{version} đã bị chiếm: {path.relative_to(ROOT)}")
    if problems:
        sys.exit(
            "DỪNG — trùng số version Flyway.\n"
            + "\n".join(problems)
            + "\n\nFlyway dùng MỘT dãy version cho cả db/migration lẫn db/seed, nên trùng số làm\n"
            "app không khởi động được. Dời dãy seed lên trên số cao nhất đang tồn tại rồi chạy lại."
        )


# ═══ Lớp tự canh 2 và 3: nội dung từng file ════════════════════════════════════════════════════

def guard_placeholders(label, text):
    """Chỉ cho phép đúng ${minioUrl}; mọi ${...} khác là migration chết lúc khởi động."""
    bad = sorted({m for m in re.findall(r"\$\{[^}]*\}", text) if m != ALLOWED_PLACEHOLDER})
    if bad:
        sys.exit(
            f"DỪNG — {label} chứa placeholder không được khai: {bad}\n"
            "Flyway thay placeholder TRƯỚC khi parse SQL, nên chuyện này giết migration kể cả khi\n"
            f"chuỗi đó nằm trong một dòng comment. Chỉ {ALLOWED_PLACEHOLDER} được phép."
        )


_MACHINE_NUMBERING_RE = re.compile(r"\(#\d+\)")


def guard_machine_numbering(label, text):
    """Chặn số thứ tự máy sinh còn sót trong nội dung — 'seed slop' dễ thấy nhất trên giao diện.

    Commit 72b06fb bỏ khuôn "(#N)" khỏi post/knowledge/project nhưng bỏ sót bình luận (V84) cùng
    vài loại post (CODE_SNIPPET, LINK, BOOK, REGULAR), và vì không có lớp canh nào nên nó lọt tới
    tận production: mọi bình luận kết thúc bằng "(#4180)", mọi bài link giống hệt nhau trừ con số.
    Cách chống trùng đúng là xoay vòng nhiều mẫu câu với chu kỳ nguyên tố cùng nhau (xem
    REGULAR_TAILS, COMMENT_TAILS…), không phải dán một chỉ số tăng dần. Regex cố ý hẹp — đúng
    khuôn "(#123)" mà generator từng gắn — nên không đụng tới nội dung có số hợp lệ.
    """
    hits = sorted(set(_MACHINE_NUMBERING_RE.findall(text)))
    if hits:
        sys.exit(
            f"DỪNG — {label} còn số thứ tự máy sinh trong nội dung: {hits[:5]}\n"
            "Đây là dấu vết dữ liệu sinh hàng loạt, lộ ngay trên giao diện. Sinh nội dung không\n"
            "đánh số bằng cách xoay vòng nhiều mẫu câu (chu kỳ nguyên tố cùng nhau). Đừng gỡ lớp\n"
            "canh này."
        )


def guard_statement_timeout(label, text, row_count, required=False):
    """File lớn phải tự mở trần thời gian, vì không có test nào canh việc này."""
    if (required or row_count >= TIMEOUT_GUARD_ROWS) and TIMEOUT_MARKER not in text:
        sys.exit(
            f"DỪNG — {label} sinh {row_count} dòng dữ liệu nhưng thiếu\n"
            f"    {TIMEOUT_MARKER}\n"
            "Không có dòng đó, statement_timeout = 15s của pool Hikari sẽ huỷ INSERT này ở\n"
            "production, trong khi SeedMigrationTest (JDBC thô, không qua Hikari) vẫn xanh."
        )


# ═══ Bộ ghi file ═══════════════════════════════════════════════════════════════════════════════

RULE = "=" * 93


class SqlFile:
    """Gom nội dung một file seed, đếm số dòng dữ liệu, rồi ghi ra sau khi qua đủ ba lớp canh.

    Đếm DÒNG DỮ LIỆU chứ không đếm dòng văn bản. Chú thích trong bộ seed này rất dài, có chủ ý —
    người đọc sau cần biết vì sao một hàng tồn tại — nên đếm dòng văn bản sẽ bắt cả những file
    nhẹ phải mở trần thời gian mà không có lý do, và làm cái ngưỡng mất hết ý nghĩa.
    """

    def __init__(self, version, name, title):
        self.version = version
        self.name = name
        self.path = SEED_DIR / f"V{version}__{name}.sql"
        self.rows = 0
        self.parts = [f"-- {RULE}\n-- {title}"]

    def note(self, text):
        """Một khối chú thích. Nhận văn bản thường, tự thêm `-- `."""
        lines = [f"-- {line}".rstrip() for line in text.strip("\n").splitlines()]
        self.parts.append("\n".join(lines))

    def rule(self):
        self.parts.append(f"-- {RULE}")

    def sql(self, text, rows=0):
        """Một khối SQL. `rows` là số HÀNG DỮ LIỆU nó chèn, dùng cho lớp canh 3."""
        self.parts.append(text.strip("\n"))
        self.rows += rows

    def needs_timeout_guard(self):
        return self.rows >= TIMEOUT_GUARD_ROWS or self.version in TIMEOUT_ALWAYS_VERSIONS

    def render(self):
        body = "\n\n".join(self.parts)
        if self.needs_timeout_guard():
            # Chèn ngay sau khối tiêu đề, trước mọi câu lệnh — trần phải mở trước câu lệnh đầu
            # tiên, không phải trước câu lệnh lớn đầu tiên.
            head, sep, rest = body.partition("\n\n")
            body = head + sep + TIMEOUT_PREAMBLE + "\n\n" + rest
        return body.rstrip() + "\n"

    def write(self):
        text = self.render()
        label = self.path.name
        guard_placeholders(label, text)
        guard_machine_numbering(label, text)
        guard_statement_timeout(label, text, self.rows, self.needs_timeout_guard())
        self.path.write_text(text, encoding="utf-8", newline="\n")
        return label, self.rows, len(text)


# ═══ Manifest ảnh ══════════════════════════════════════════════════════════════════════════════

# key ⇢ (loại, URL nguồn). docker/minio/generate-seed-objects.py đọc file này để biết tải ảnh thật
# ở đâu; mất mạng thì nó rơi về bộ sinh PNG/PDF nội tuyến và báo rõ đã dùng bao nhiêu ảnh dự phòng.
MANIFEST = {}


def want_object(key, kind, url):
    """Ghi nhận một object MinIO mà bộ seed trỏ tới bằng KEY TRẦN, không phải URL.

    Sách đi đường này: BookStorageService tự ký URL tạm thời khi phục vụ, nên cột *_key giữ đúng
    cái key. `url` là None với những object không có nguồn thật để tải (nội dung PDF/EPUB) —
    generate-seed-objects.py sẽ sinh file mẫu cho chúng.
    """
    if key in MANIFEST and MANIFEST[key] != (kind, url):
        sys.exit(f"DỪNG — key {key} được khai hai lần với hai nguồn khác nhau")
    MANIFEST[key] = (kind, url)
    return key


# Prefix của object ⇢ BUCKET chứa nó. Bảng này phải khớp từng dòng với các lệnh `mc cp` của service
# minio-init trong docker-compose.yml — nó là nửa "nguồn trong generate_seed.py" của cái ràng buộc
# "mỗi loại ảnh mới là ba chỗ phải sửa cùng lúc" mà comment ở compose nói tới.
#
# Vì sao cần bảng này: object nằm ở <bucket>/<key>, còn manifest chỉ giữ <key> (đúng như vậy —
# minio-init chép /objects/<prefix>/ vào <bucket>/<prefix>/, nên key trong manifest là đường dẫn
# BÊN TRONG bucket). URL công khai thì phải có đủ cả hai: MediaService dựng
# `minio.url + "/" + BUCKET + "/" + objectKey`. Bỏ khúc bucket đi thì mọi avatar, ảnh bìa và ảnh
# bài viết của bộ seed trả 403 — URL vẫn hợp lệ, ảnh vẫn có địa chỉ, chỉ là không có gì ở đó.
BUCKET_OF_PREFIX = {
    "avatars": "profile-pictures",
    "covers-user": "profile-pictures",
    "posts": "post-media",
}


def want_image(key, kind, url):
    """Ghi nhận một object MinIO mà bộ seed trỏ tới, kèm nguồn ảnh thật của nó.

    Trả về URL TUYỆT ĐỐI có đủ segment bucket — khác `want_object`, vốn trả key trần cho những
    cột mà backend tự ký URL khi phục vụ.
    """
    if key in MANIFEST and MANIFEST[key] != (kind, url):
        sys.exit(f"DỪNG — key {key} được khai hai lần với hai nguồn khác nhau")
    prefix = key.split("/", 1)[0]
    if prefix not in BUCKET_OF_PREFIX:
        sys.exit(
            f"DỪNG — prefix {prefix!r} chưa có trong BUCKET_OF_PREFIX.\n"
            "Thêm một loại ảnh mới là NĂM chỗ phải sửa cùng lúc: bảng này; thư mục prefix trong\n"
            "docker/minio/generate-seed-objects.py; dòng `mc cp` của minio-init trong\n"
            "docker-compose.yml (bucket tương ứng phải được `mc anonymous set download`); và\n"
            "BUCKET_OF_PREFIX + placeholder() trong MinIOSeedObjectInitializer.java (đường prod)."
        )
    MANIFEST[key] = (kind, url)
    return "${minioUrl}/" + BUCKET_OF_PREFIX[prefix] + "/" + key


# ═══ V81 — 500 người dùng ══════════════════════════════════════════════════════════════════════

# Bao nhiêu tài khoản dùng chân dung thật (Pravatar) thay vì avatar vẽ (DiceBear). Chỉ một thiểu
# số: một bảng xếp hạng toàn ảnh chụp người thật trông giống một trang web đánh cắp ảnh hơn là một
# mạng nghề nghiệp, còn toàn avatar vẽ thì không kiểm được bố cục với ảnh thật.
PORTRAIT_COUNT = 40

# Một tên dài cố ý, để chạy nhánh cắt chữ trên thanh danh tính (fixture: full_name >= 40 ký tự).
LONG_NAME = "Nguyễn Hoàng Bảo Trân Thục Đoan Phương Vy"


def build_people(rng):
    """500 hồ sơ, tất định. Trả về list dict theo thứ tự id tăng dần."""
    roles = []
    for role, count in ROLE_QUOTA:
        roles.extend([role] * count)
    assert len(roles) == USER_ID_LAST - USER_ID_FIRST + 1 - len(ADMIN_IDS), len(roles)
    rng.shuffle(roles)

    # Vài username cùng tiền tố, để ô soạn thảo gõ "@nguyenvan" ra một DANH SÁCH chứ không ra một
    # dòng — MentionSuggestService chỉ chứng minh được là nó lọc khi có nhiều hơn một ứng viên.
    forced_prefix = set(rng.sample(range(len(roles)), 12))

    people = []
    used_usernames = set()
    portrait_ids = set(rng.sample(range(USER_ID_FIRST, USER_ID_LAST - 1), PORTRAIT_COUNT))

    role_iter = iter(roles)
    for uid in range(USER_ID_FIRST, USER_ID_LAST + 1):
        is_admin = uid in ADMIN_IDS
        female = rng.random() < 0.42
        ho = rng.choice(HO)
        dem = rng.choice(DEM_NU if female else DEM_NAM)
        ten = rng.choice(TEN_NU if female else TEN_NAM)

        index = uid - USER_ID_FIRST
        if index in forced_prefix:
            ho, dem = "Nguyễn", "Văn"
            ten = rng.choice(TEN_NAM)
            female = False

        full_name = LONG_NAME if uid == 9003 else f"{ho} {dem} {ten}"

        # Username dựng từ FULL_NAME, không phải từ bộ ba ho/dem/ten ở trên. Chỉ khác nhau ở đúng
        # một tài khoản — 9003, cái bị LONG_NAME ghi đè — nhưng đó lại là tài khoản đem hai thứ ra
        # khoe cạnh nhau: hồ sơ hiện tên đầy đủ ngay trên handle. Dựng từ bộ ba cũ thì màn hình ra
        # "Nguyễn Hoàng Bảo Trân Thục Đoan Phương Vy" đặt trên "@nguyenvanlong" — handle của một
        # cái tên đã bị vứt đi ở dòng trên, và người xem demo không có cách nào đoán ra vì sao.
        base = strip_accents(full_name).lower().replace(" ", "")
        username = base
        suffix = 0
        while username in used_usernames:
            suffix += 1
            username = f"{base}{suffix:02d}"
        used_usernames.add(username)

        role = None if is_admin else next(role_iter)

        if uid == DEMO_EXPERT:
            years = 14
        elif uid == DEMO_NEWCOMER:
            years = 0
        else:
            years = rng.choice([0, 1, 1, 2, 2, 3, 3, 4, 5, 5, 6, 7, 8, 9, 10, 12, 15])
        seniority = next(level for cutoff, level in SENIORITY_BY_YEARS if years <= cutoff)

        # 19 người thường cố ý KHÔNG có hồ sơ nghề nghiệp (9004, 9005, cộng mọi uid chia hết cho
        # 29): hồ sơ là tuỳ chọn trong ứng dụng, và nếu ai cũng có thì nhánh "chưa điền hồ sơ" của
        # trang cá nhân không bao giờ chạy.
        has_profile = not is_admin and uid not in {9004, 9005} and (uid % 29) != 0

        # Avatar: cứ 11 người thì 1 người để trống, để nhánh rơi-về-chữ-viết-tắt có dữ liệu. Hai
        # tài khoản demo luôn CÓ ảnh — chúng lên màn hình chiếu.
        wants_avatar = uid in (DEMO_EXPERT, DEMO_NEWCOMER) or (uid % 11) != 0
        avatar = None
        if wants_avatar:
            key = f"avatars/{uid}/avatar.png"
            if uid in portrait_ids:
                src = f"https://i.pravatar.cc/256?img={(uid % 70) + 1}"
            else:
                src = f"https://api.dicebear.com/9.x/avataaars/png?seed={username}&size=256"
            avatar = want_image(key, "avatar", src)

        # Ảnh bìa hiếm hơn avatar, đúng như hành vi thật: nhiều người không bao giờ đặt ảnh bìa.
        cover = None
        if uid == DEMO_EXPERT or (uid % 3 == 0 and wants_avatar):
            key = f"covers-user/{uid}/cover.png"
            cover = want_image(key, "user-cover", f"https://picsum.photos/seed/cover{uid}/640/360")

        people.append({
            "id": uid,
            "username": username,
            "full_name": full_name,
            "email": f"{username}@{EMAIL_DOMAIN}",
            "role": "ADMIN" if is_admin else "USER",
            "primary_role": role,
            "years": years,
            "seniority": seniority,
            "job_title": None if is_admin else JOB_TITLE[role],
            "expl_style": rng.choice(EXPLANATION_STYLE),
            "company": rng.choice(COMPANIES),
            "work_domain": rng.choice(WORK_DOMAINS),
            "has_profile": has_profile,
            "avatar": avatar,
            "cover": cover,
            # Ngày tham gia trải 18 tháng; id nhỏ = vào sớm hơn.
            "joined_days_ago": 548 - int(index * 548 / (USER_ID_LAST - USER_ID_FIRST)),
            "email_verified": (uid % 13) != 0,
        })
    return people


def emit_users(people):
    f = SqlFile(81, "seed_users", "500 tài khoản, id 9001-9500, kèm hồ sơ nghề nghiệp và tuỳ chọn thông báo.")
    f.note(f"""
MẬT KHẨU: tài khoản thường "12qwaszx", hai tài khoản ADMIN (9499, 9500) "1234qwer".
Hash BCrypt ghi cứng bên dưới, được SeedPasswordHashTest kiểm bằng chính BCryptPasswordEncoder.

Email dùng TLD `.test` — RFC 2606 dành riêng cho thử nghiệm, KHÔNG định tuyến được và không ai
đăng ký được. Bộ seed này chạy trên production với luồng đặt lại mật khẩu đang hoạt động, nên một
tên miền có thật ở đây (kể cả .vn của chính dự án) đồng nghĩa với: ai kiểm soát hòm thư ở tên miền
đó chiếm được cả 500 tài khoản. Thế hệ seed trước đã phải bỏ @test.com và @socialapp.com vì đúng
lý do này.

Dải id 9001-9500 cố định và được tham chiếu trực tiếp bởi db/seed/friend-graph.cypher,
mọi file V82-V92 trong thư mục này, và kịch bản demo của frontend. Đổi dải là phải đổi đồng bộ.

Tài khoản demo:  9001 cao thủ  ·  9002 người mới  ·  9499/9500 ADMIN

FILE NÀY SINH TỰ ĐỘNG bởi scripts/seed/generate_seed.py — sửa tay sẽ bị ghi đè.
""")
    f.rule()

    f.note("""
Bảng tạm giữ toàn bộ hồ sơ một người ở đúng MỘT chỗ rồi rót ra ba bảng thật. Tách thành ba danh
sách VALUES riêng thì id và tên bị lặp ba lần, và chỉ cần lệch một dòng là hồ sơ nghề nghiệp gắn
nhầm người — kiểu sai rất khó nhìn ra khi đọc review.
""")
    f.sql("""
CREATE TEMPORARY TABLE tmp_seed_people (
    id             INT PRIMARY KEY,
    username       TEXT NOT NULL,
    full_name      TEXT NOT NULL,
    email          TEXT NOT NULL,
    user_role      TEXT NOT NULL,
    primary_role   TEXT,
    job_title      TEXT,
    seniority      TEXT,
    years_exp      INT,
    expl_style     TEXT,
    company        TEXT,
    work_domain    TEXT,
    has_profile    BOOLEAN NOT NULL,
    avatar_url     TEXT,
    cover_url      TEXT,
    joined_ago     INT NOT NULL,
    email_verified BOOLEAN NOT NULL
);""")

    rows = []
    for p in people:
        rows.append(
            "    ({id}, {username}, {full_name}, {email}, {role}, {primary_role}, {job_title}, "
            "{seniority}, {years}, {expl_style}, {company}, {work_domain}, {has_profile}, "
            "{avatar}, {cover}, {joined}, {verified})".format(
                id=p["id"], username=q(p["username"]), full_name=q(p["full_name"]),
                email=q(p["email"]), role=q(p["role"]), primary_role=q(p["primary_role"]),
                job_title=q(p["job_title"]), seniority=q(p["seniority"]), years=p["years"],
                expl_style=q(p["expl_style"]), company=q(p["company"]),
                work_domain=q(p["work_domain"]),
                has_profile="TRUE" if p["has_profile"] else "FALSE",
                avatar=q(p["avatar"]), cover=q(p["cover"]),
                joined=p["joined_days_ago"],
                verified="TRUE" if p["email_verified"] else "FALSE",
            )
        )
    f.sql("INSERT INTO tmp_seed_people VALUES\n" + ",\n".join(rows) + ";", rows=len(rows))

    f.note("── t_users " + "─" * 82)
    f.sql("""
INSERT INTO socialapp.t_users
    (id, email, password, username, full_name, profile_picture_url, cover_image_url,
     email_verified, role, auth_provider, provider_id, elite_score, created_at, updated_at)
SELECT p.id,
       p.email,
       CASE WHEN p.user_role = 'ADMIN' THEN {admin} ELSE {user} END,
       p.username,
       p.full_name,
       p.avatar_url,
       p.cover_url,
       p.email_verified,
       p.user_role,
       'LOCAL',
       NULL,
       -- elite_score được TÍNH LẠI từ t_reputation_events ở cuối V90; 0 chỉ là mốc xuất phát.
       0,
       now() - (p.joined_ago * INTERVAL '1 day'),
       now() - (p.joined_ago * INTERVAL '1 day')
  FROM tmp_seed_people p;""".format(admin=q(PASSWORD_ADMIN), user=q(PASSWORD_USER)),
          rows=len(people))

    f.note("""
── t_user_professional_profiles """ + "─" * 61 + """
known_tech_stack / interested_domains / work_history là jsonb. interested_domains lấy từ ĐÚNG bộ
từ vựng mà t_projects.tags dùng (xem DOMAINS trong generator) — MatchmakingService so hai cột này
bằng phép giao, nên hai kho từ khác nhau làm gợi ý dự án rỗng mà không báo lỗi gì.

19 người thường cố ý không có hồ sơ: hồ sơ là tuỳ chọn, và nếu ai cũng có thì nhánh "chưa điền
hồ sơ" của trang cá nhân không bao giờ chạy.
""")
    stack_case = "\n".join(
        f"           WHEN {q(role):<12} THEN {jsonb(items)}"
        for role, items in TECH_STACK.items()
    )
    domain_case = "\n".join(
        f"           WHEN {q(role):<12} THEN {jsonb(items)}"
        for role, items in DOMAINS.items()
    )
    f.sql(f"""
INSERT INTO socialapp.t_user_professional_profiles
    (user_id, job_title, seniority_level, years_of_experience, primary_role, explanation_style,
     known_tech_stack, work_history, interested_domains, created_at, updated_at)
SELECT p.id,
       p.job_title,
       p.seniority,
       p.years_exp,
       p.primary_role,
       p.expl_style,
       CASE p.primary_role
{stack_case}
       END,
       -- Một chỗ làm gần nhất; độ dài suy ra từ số năm kinh nghiệm để hai trường không mâu thuẫn
       -- nhau khi hiển thị cạnh nhau trên hồ sơ.
       jsonb_build_array(
           jsonb_build_object(
               'company', p.company,
               'domain', p.work_domain,
               'role', p.job_title,
               'durationMonths', GREATEST(p.years_exp * 12 - 6, 6)
           )
       ),
       CASE p.primary_role
{domain_case}
       END,
       now() - (p.joined_ago * INTERVAL '1 day'),
       now() - (p.joined_ago * INTERVAL '1 day')
  FROM tmp_seed_people p
 WHERE p.has_profile;""", rows=sum(1 for p in people if p["has_profile"]))

    f.note("""
── t_notification_preferences """ + "─" * 63 + """
Mỗi user một dòng. onesignal_player_id để NULL: đó là id thiết bị thật do SDK OneSignal cấp, bịa
ra chỉ khiến push đi vào hư không và log đầy lỗi.
email_frequency CHỈ nhận 'INSTANT' hoặc 'NONE' — CHECK constraint từ V45.
""")
    f.sql("""
INSERT INTO socialapp.t_notification_preferences
    (user_id, push_enabled, email_enabled, onesignal_player_id, email_frequency, muted_types,
     updated_at)
SELECT p.id,
       TRUE,
       (p.id % 7) <> 0,
       NULL,
       CASE WHEN (p.id % 7) = 0 THEN 'NONE' ELSE 'INSTANT' END,
       CASE
           WHEN (p.id % 11) = 0 THEN '["POST_LIKED","EVENT_REMINDER"]'
           WHEN (p.id % 13) = 0 THEN '["POST_TAGGED"]'
           ELSE                      '[]'
       END::jsonb,
       now()
  FROM tmp_seed_people p;""", rows=len(people))

    f.note("""
Đẩy sequence quá dải id gán tay. Bỏ sót bước này thì lỗi khoá trùng không nổ lúc seed — nó nổ ở
LẦN ĐĂNG KÝ ĐẦU TIÊN của một người dùng thật, cách đây rất xa về mặt nguyên nhân.
""")
    f.sql(f"SELECT setval('socialapp.q_users_id', {USER_ID_LAST + 1}, FALSE);")
    return f


# ═══ V82 — đồ thị bạn bè (Postgres + Neo4j, từ MỘT tập cạnh) ═══════════════════════════════════
#
# Quan hệ bạn bè sống ở HAI nơi với hai vai trò khác nhau. Neo4j giữ cạnh FRIENDS_WITH và là thứ
# mà areFriends, danh sách bạn bè và gợi ý kết bạn THỰC SỰ đọc. Postgres t_friend_requests chỉ là
# nhật ký lời mời, phục vụ màn hình "đã gửi / đã nhận". Hai bên lệch nhau thì KHÔNG CÓ GÌ BÁO LỖI —
# chỉ là hồ sơ hiện "đã là bạn" trong khi danh sách bạn bè không có người đó. Vì vậy tập cạnh được
# dựng đúng MỘT lần ở đây rồi in ra hai định dạng, trong cùng một lần chạy.

SUBCLUSTER_SIZE = 14

# Trong một nhóm nhỏ ~14 người thì quen nhau là chuyện thường; giữa hai nhóm cùng ngành thì thưa
# hơn hẳn; và mỗi người có vài mối xuyên ngành. Ba con số này quyết định gợi ý kết bạn có gì để
# xếp hạng hay không: mesh đầy đủ thì không còn cặp "chưa là bạn nhưng có bạn chung" nào cả.
INTRA_SUBCLUSTER_P = 0.35
INTRA_CLUSTER_P = 0.04
CROSS_EDGES_PER_USER = (1, 4)


def subclusters_of(members):
    """Chia một cụm vai trò thành các nhóm nhỏ ~SUBCLUSTER_SIZE người."""
    count = max(1, round(len(members) / SUBCLUSTER_SIZE))
    return [members[i::count] for i in range(count)]


def build_edges(rng, people):
    """Tập cạnh bạn bè vô hướng, chuẩn hoá thành (nhỏ, lớn)."""
    by_role = {}
    for p in people:
        if p["primary_role"]:
            by_role.setdefault(p["primary_role"], []).append(p["id"])

    edges = set()
    for members in by_role.values():
        groups = subclusters_of(members)

        for group in groups:
            # Xương sống: nối thành chuỗi để nhóm chắc chắn liên thông. Không có bước này, một
            # người có thể rơi vào trạng thái không bạn bè và biến mất khỏi mọi gợi ý — im lặng.
            for a, b in zip(group, group[1:]):
                edges.add((min(a, b), max(a, b)))
            for i, a in enumerate(group):
                for b in group[i + 1:]:
                    if rng.random() < INTRA_SUBCLUSTER_P:
                        edges.add((min(a, b), max(a, b)))

        # Giữa hai nhóm nhỏ cùng ngành: thưa, nhưng đủ để cụm không vỡ thành các đảo rời.
        for i, a in enumerate(members):
            for b in members[i + 1:]:
                if rng.random() < INTRA_CLUSTER_P:
                    edges.add((min(a, b), max(a, b)))

    # Cạnh liên ngành — thứ tạo ra "bạn của bạn" xuyên vai trò.
    everyone = [p["id"] for p in people if p["primary_role"]]
    own_role = {p["id"]: p["primary_role"] for p in people if p["primary_role"]}
    for uid in everyone:
        outsiders = [u for u in everyone if own_role[u] != own_role[uid]]
        for peer in rng.sample(outsiders, rng.randint(*CROSS_EDGES_PER_USER)):
            edges.add((min(uid, peer), max(uid, peer)))

    return sorted(edges)


def build_non_friend_pairs(rng, people, edges, count):
    """Cặp CHƯA là bạn — dùng cho lời mời PENDING/REJECTED/CANCELLED và cho chặn."""
    everyone = [p["id"] for p in people if p["primary_role"]]
    taken = set(edges)
    pairs = set()
    guard = 0
    while len(pairs) < count and guard < count * 400:
        guard += 1
        a, b = rng.sample(everyone, 2)
        pair = (min(a, b), max(a, b))
        if pair not in taken:
            pairs.add(pair)
            taken.add(pair)
    return sorted(pairs)


def emit_social_graph(people, edges, pending, rejected, cancelled, blocks):
    f = SqlFile(82, "seed_social_graph", "Nhật ký lời mời kết bạn và danh sách chặn.")
    f.note(f"""
FILE NÀY SINH TỰ ĐỘNG bởi scripts/seed/generate_seed.py — sửa tay sẽ bị ghi đè.

BẢNG NÀY KHÔNG PHẢI NƠI APP QUYẾT ĐỊNH HAI NGƯỜI CÓ PHẢI BẠN HAY KHÔNG. Nguồn sự thật là cạnh
FRIENDS_WITH trong Neo4j (FriendshipRepository); bảng này chỉ là nhật ký lời mời, phục vụ màn hình
"lời mời đã gửi / đã nhận". Nạp file SQL này mà quên nạp đồ thị Neo4j thì danh sách bạn bè rỗng
trong khi lịch sử lời mời đầy đủ — và không có gì báo lỗi.

{len(edges)} dòng ACCEPTED dưới đây khớp 1-1 với cạnh trong db/seed/friend-graph.cypher.
Cả hai file được sinh trong cùng một lần chạy, từ cùng một tập cạnh, đúng để chúng không lệch được.
Neo4jSeedInitializer nạp file cypher khi khởi động (cờ neo4j.seed-on-start), nên nó đi theo
ứng dụng tới mọi môi trường thay vì chỉ có ở máy nào chạy docker-compose của repo này.

Mật độ trong nhóm cố ý DƯỚI mức mesh đầy đủ: phải còn những cặp chưa là bạn nhưng có bạn chung thì
GET /v1/api/friendships/suggestions mới có gì để xếp hạng.

Hai tài khoản ADMIN (9499, 9500) cố ý KHÔNG có quan hệ bạn bè nào.
""")
    f.rule()

    def rows(pairs, status, swap=False):
        out = []
        for a, b in pairs:
            req, addr = (b, a) if swap else (a, b)
            out.append(
                f"    ({req}, {addr}, '{status}', now() - INTERVAL '{(req % 400) + 20} days',"
                f" now() - INTERVAL '{(req % 180) + 1} days')"
            )
        return out

    columns = ("INSERT INTO socialapp.t_friend_requests\n"
               "    (requester_id, addressee_id, status, created_at, updated_at) VALUES")

    f.note(f"{len(edges)} quan hệ đã thành bạn.")
    f.sql(columns + "\n" + ",\n".join(rows(edges, "ACCEPTED")) + ";", rows=len(edges))

    f.note(f"""
{len(pending)} lời mời đang chờ. Chỉ số partial uq_friend_requests_pending_pair (V33) bắt buộc mỗi
cặp KHÔNG THỨ TỰ chỉ có tối đa một dòng PENDING — nó đánh trên LEAST/GREATEST, nên đảo chiều người
gửi cũng không lách được. Các cặp dưới đây đôi một khác nhau.
""")
    f.sql(columns + "\n" + ",\n".join(rows(pending, "PENDING")) + ";", rows=len(pending))

    f.note(f"""
{len(rejected)} lời mời bị từ chối và {len(cancelled)} lời mời người gửi tự huỷ. Hai trạng thái này
không bị ràng buộc bởi chỉ số partial ở trên, nên cùng một cặp xuất hiện lại được.
""")
    f.sql(
        columns + "\n"
        + ",\n".join(rows(rejected, "REJECTED") + rows(cancelled, "CANCELLED", swap=True)) + ";",
        rows=len(rejected) + len(cancelled),
    )

    f.note(f"""
{len(blocks)} quan hệ chặn. Cố ý chọn toàn cặp CHƯA là bạn: chặn một người đang là bạn là trạng
thái mâu thuẫn mà luồng chặn thật của app không tạo ra được (chặn sẽ gỡ luôn quan hệ bạn bè).
CHECK (blocker_id <> blocked_id) từ V46 đã loại sẵn trường hợp tự chặn mình.
""")
    f.sql(
        "INSERT INTO socialapp.t_user_blocks (blocker_id, blocked_id, created_at) VALUES\n"
        + ",\n".join(
            f"    ({a}, {b}, now() - INTERVAL '{(a % 120) + 1} days')" for a, b in blocks
        )
        + ";",
        rows=len(blocks),
    )

    f.note("Sequence: t_friend_requests để Postgres tự cấp id, nên chỉ cần đẩy quá mốc hiện tại.")
    f.sql("SELECT setval('socialapp.q_friend_requests_id',\n"
          "              GREATEST((SELECT COALESCE(MAX(id), 0) FROM socialapp.t_friend_requests), 1),"
          " true);")
    return f


def write_cypher(people, edges):
    """Đồ thị bạn bè cho Neo4j — cùng tập cạnh với V82."""
    by_role = {}
    for p in people:
        if p["primary_role"]:
            by_role.setdefault(p["primary_role"], []).append(p["id"])

    lines = [
        "// " + "=" * 92,
        "// Đồ thị bạn bè cho 500 tài khoản (userId 9001-9500) sinh bởi V81__seed_users.sql.",
        "//",
        "// SINH TỰ ĐỘNG bởi scripts/seed/generate_seed.py — đừng sửa tay.",
        "//",
        "// Flyway chỉ quản Postgres, nên phần đồ thị này nạp riêng. Cạnh ở đây khớp 1-1 với các dòng",
        "// ACCEPTED trong V82__seed_social_graph.sql; hai file luôn được sinh cùng một lượt, vì lệch",
        "// nhau là một lỗi không có gì báo: hồ sơ hiện 'đã là bạn' còn danh sách bạn bè thì không.",
        "//",
        "// Neo4jSeedInitializer nạp file này mỗi lần khởi động có NEO4J_SEED_ON_START=true — KHÔNG",
        "// còn điều kiện 'đồ thị còn rỗng', vì điều kiện đó chặn TRƯỚC khi file được đọc và do đó",
        "// nuốt luôn câu DETACH DELETE ngay dưới đây. File nằm trong jar nên chạy được ở mọi môi",
        "// trường. Nạp tay:",
        "//   docker exec -i neo4j cypher-shell -u neo4j -p <mật-khẩu> \\",
        "//     < src/main/resources/db/seed/friend-graph.cypher",
        "//",
        "// Toàn bộ là MERGE nên chạy lại nhiều lần vẫn an toàn.",
        "//",
        "// Cụm vai trò (khớp V81):",
    ]
    for role in sorted(by_role):
        ids = by_role[role]
        lines.append(f"//   {role:<10} {len(ids):>3} người")
    lines += [
        "//",
        "// 9499/9500 là ADMIN vận hành — cố ý không có quan hệ bạn bè nào, nên không có node.",
        "// " + "=" * 92,
        "",
        "// Xoá đồ thị seed cũ trước khi dựng lại. Không có bước này thì cạnh của thế hệ seed trước",
        "// nằm lẫn vào và danh sách bạn bè không còn khớp với t_friend_requests bên Postgres.",
        "MATCH (u:User) WHERE u.userId >= 9001 AND u.userId <= 9599 DETACH DELETE u;",
        "",
        f"UNWIND range({USER_ID_FIRST}, {USER_ID_LAST - len(ADMIN_IDS)}) AS uid",
        "MERGE (:User {userId: uid});",
        "",
    ]

    # Gom cạnh theo lô để mỗi câu lệnh không quá dài, và để file đọc được.
    BATCH = 500
    for start in range(0, len(edges), BATCH):
        chunk = edges[start:start + BATCH]
        pairs = ", ".join(f"[{a},{b}]" for a, b in chunk)
        lines += [
            f"UNWIND [{pairs}] AS pair",
            "MATCH (a:User {userId: pair[0]}), (b:User {userId: pair[1]})",
            # Một cạnh VÔ HƯỚNG, đúng như createFriendship ghi lúc chạy runtime
            # (FriendshipRepository.java) — hai MERGE có hướng trước đây tạo ra hai quan hệ riêng
            # biệt giữa cùng một cặp node, khiến mọi MATCH vô hướng ở tầng đọc
            # (findFriendIdsAfterCursor, countFriends) khớp cả hai và trả về cùng một người hai
            # lần. Xem B30 trong docs/backend-plan.md.
            "MERGE (a)-[:FRIENDS_WITH]-(b);",
            "",
        ]

    NEO4J_SEED.parent.mkdir(parents=True, exist_ok=True)
    NEO4J_SEED.write_text("\n".join(lines), encoding="utf-8", newline="\n")
    return NEO4J_SEED.name, len(edges)


# ═══ V83 — bài viết ════════════════════════════════════════════════════════════════════════════
#
# Id cấp TƯỜNG MINH chứ không để sequence tự sinh, vì V84-V92 phải trỏ tới từng bài cụ thể (bình
# luận vào bài nào, sách gắn bài nào, log kiểm duyệt của bài nào). Để sequence cấp thì các file sau
# phải dò lại bài bằng cách so khớp nội dung — vừa dài dòng vừa hỏng ngay khi ai sửa một câu chữ.
#
# Dải theo loại, liền nhau trong 100001-102600:
POST_RANGES = {
    "EVENT": (100001, 80),
    "CODE_SNIPPET": (100081, 160),
    "ARTICLE": (100241, 120),
    "QNA": (100361, 200),
    "POLL": (100561, 80),
    "LINK": (100641, 120),
    "BOOK": (100761, 80),
    "REGULAR": (100841, 1760),
}

HASHTAGS = [
    "java", "springboot", "postgresql", "redis", "docker", "kubernetes", "typescript", "react",
    "nextjs", "tailwindcss", "kotlin", "flutter", "python", "machinelearning", "devops",
    "terraform", "aws", "security", "testing", "playwright", "performance", "architecture",
    "career", "opensource", "database", "microservices", "graphql", "observability", "ux",
    "productivity", "golang", "rust", "swift", "android", "ios", "grpc", "kafka", "rabbitmq",
    "elasticsearch", "mongodb", "nginx", "linux", "git", "cicd", "githubactions", "monitoring",
    "prometheus", "grafana", "logging", "tracing", "caching", "scaling", "loadbalancing",
    "designsystem", "accessibility", "webperf", "seo", "pwa", "webassembly", "vitejs",
    "nodejs", "deno", "bun", "express", "nestjs", "fastapi", "django", "laravel", "dotnet",
    "hibernate", "jpa", "flyway", "liquibase", "junit", "mockito", "cypress", "selenium",
    "jenkins", "gitlab", "argocd", "helm", "istio", "serverless", "lambda", "cloudflare",
    "pandas", "pytorch", "tensorflow", "spark", "airflow", "dbt", "duckdb", "clickhouse",
    "mlops", "llm", "rag", "vectordb", "prompt", "agile", "scrum", "codereview", "refactoring",
    "cleancode", "solid", "ddd", "tdd", "bdd", "mentoring", "interview", "remotework",
    "burnout", "sideproject", "freelance", "startup", "teamwork", "documentation", "api",
    "opentelemetry", "featureflag", "migration",
]
assert len(HASHTAGS) >= 120, len(HASHTAGS)
HASHTAGS = HASHTAGS[:120]

# ── Kho nội dung cho bài REGULAR ───────────────────────────────────────────────────────────────
#
# Ghép (chủ đề × góc nhìn × chi tiết) chứ không lặp một khuôn. Ba trục nhân nhau cho hơn 30.000 tổ
# hợp, thừa sức cho 1.760 bài mà không bài nào trùng bài nào.
#
# Vì sao trùng khít lại đáng tránh, và vì sao KHÔNG phải vì lý do người ta hay nghĩ: SpamDetector
# .isDuplicateContent băm SHA-256 nội dung theo từng tác giả với TTL 60 giây trong Redis, tức là nó
# chỉ bắt bài GIỐNG HỆT gửi qua API trong vòng một phút. Bộ seed chèn thẳng bằng SQL nên không đi
# qua rule engine chút nào. Lý do thật đơn giản hơn: hai bài giống hệt nhau trong một bảng tin
# trông giả ngay lập tức, và làm hỏng mọi ảnh chụp màn hình.
TOPICS = [
    ("tối ưu truy vấn N+1", "backend"), ("chọn TTL cho cache", "backend"),
    ("đánh index đúng thứ tự cột", "database"), ("chuyển sang virtual threads", "backend"),
    ("tách monolith thành service", "architecture"), ("thiết kế API phân trang", "api"),
    ("xử lý idempotency cho webhook", "api"), ("chuẩn hoá log có traceId", "observability"),
    ("đo p99 thay vì trung bình", "performance"), ("giảm thời gian build CI", "cicd"),
    ("viết test không phụ thuộc thứ tự", "testing"), ("mock ít đi, dùng testcontainers", "testing"),
    ("dựng design token dùng chung", "designsystem"), ("giảm layout shift", "webperf"),
    ("làm form truy cập được bằng bàn phím", "accessibility"),
    ("chia bundle theo route", "webperf"), ("quản lý state không cần thư viện", "react"),
    ("đồng bộ dữ liệu khi mất mạng", "mobile"), ("giảm kích thước app", "mobile"),
    ("bảo mật token trên thiết bị", "security"), ("rà soát phụ thuộc bên thứ ba", "security"),
    ("dựng pipeline triển khai xanh-lam", "devops"), ("đặt resource limit cho pod", "kubernetes"),
    ("chuyển state Terraform lên remote", "terraform"), ("giảm hoá đơn cloud", "devops"),
    ("đưa mô hình từ notebook lên production", "mlops"), ("làm sạch dữ liệu đầu vào", "data"),
    ("chọn giữa batch và streaming", "data"), ("đánh giá chất lượng embedding", "mlops"),
    ("viết prompt ổn định qua nhiều lần chạy", "llm"),
    ("phỏng vấn không hỏi thuật toán", "career"), ("nhận review mà không tự ái", "career"),
    ("dẫn dắt một đội bốn người", "career"), ("viết tài liệu mà người ta chịu đọc", "career"),
    ("ước lượng công việc sát hơn", "career"),
]
assert len(TOPICS) == 35, len(TOPICS)

# Ba sự kiện THẬT cho mỗi chủ đề ở TOPICS, cùng thứ tự — TOPIC_FACTS[i] ứng với TOPICS[i]. Bản này
# (2026-09-01) sinh bằng cách thật sự đọc trực tiếp tài liệu/bài viết công khai qua công cụ tìm
# kiếm trong phiên làm việc, không viết lại từ trí nhớ — nguồn gồm tài liệu chính thức (PostgreSQL,
# Hibernate, React, WCAG, Terraform, Kubernetes, AWS…) và case study/blog kỹ thuật đã xuất bản
# (Stripe, Netflix, Cloudflare, Telegraph/web.dev, Google SRE…). KHÔNG trích nguyên văn — diễn đạt
# lại bằng lời của nhóm, giữ đúng sự kiện. Không đủ chỗ cho trích dẫn URL trong một câu seed, nên
# nguồn nằm ở lịch sử research của phiên làm việc đã sinh ra bộ seed này, không lặp lại ở đây.
TOPIC_FACTS = [
    [  # 0. tối ưu truy vấn N+1
        "JOIN FETCH chỉ tự động chèn được khi viết tường minh trong JPQL hay Criteria API — Hibernate không tự ý thêm nó vào một câu truy vấn đã định nghĩa sẵn, nên quên viết là N+1 vẫn xảy ra dù entity đã khai batch size.",
        "Đặt hibernate.default_batch_fetch_size ở cấu hình toàn cục áp batch fetching cho mọi quan hệ lazy trong ứng dụng, không cần gắn @BatchSize thủ công ở từng entity riêng lẻ.",
        "Batch fetching nên đóng vai trò lưới an toàn cho những chỗ lỡ quên JOIN FETCH, chứ không phải phương án chính — JOIN FETCH vẫn rẻ hơn vì gom được mọi thứ trong đúng một lượt truy vấn.",
    ],
    [  # 1. chọn TTL cho cache
        "Cache stampede — còn gọi là hiệu ứng dogpile hay thundering herd — xảy ra khi nhiều request cùng phát hiện một key hết hạn ở đúng cùng thời điểm và cùng lúc dội xuống nguồn dữ liệu để tái tạo lại nó.",
        "Cloudflare vận hành stale-while-revalidate theo kiểu bất đồng bộ: tiến trình làm mới cache bắt đầu ngay lúc hết hạn chứ không đợi tới lượt truy cập kế tiếp, nên người dùng đầu tiên sau khi hết hạn nhận ngay bản cũ mà không phải chờ vòng round-trip nào tới origin.",
        "TTL Jitter được xem là biện pháp nền tảng nên áp dụng ở mọi hệ thống cache vì gần như miễn phí về độ phức tạp, còn cơ chế khoá tái tạo hay hàng đợi làm mới đòi thêm logic điều phối phức tạp hơn hẳn.",
    ],
    [  # 2. đánh index đúng thứ tự cột
        "Một index nhiều cột chỉ thật sự phát huy hiệu quả khi truy vấn ràng buộc đúng các cột nằm ở đầu index — PostgreSQL vẫn dùng được index cho một tập con cột, nhưng hiệu quả giảm hẳn nếu không chạm tới cột dẫn đầu.",
        "Quy tắc leftmost prefix xác định chính xác cách Postgres dùng index tổ hợp: mọi ràng buộc bằng trên các cột đầu, cộng thêm đúng một ràng buộc khoảng trên cột kế tiếp chưa có ràng buộc bằng, là phần được dùng để giới hạn vùng quét.",
        "Đặt cột hay xuất hiện trong mệnh đề WHERE lên đầu danh sách cột của index, còn cột ít dùng để lọc hơn xếp sau — thứ tự khai báo trong CREATE INDEX quyết định trực tiếp cấu trúc B-tree và những truy vấn nào tận dụng được nó.",
    ],
    [  # 3. chuyển sang virtual threads
        "Trước JEP 491 ở Java 24, một lệnh block bên trong khối synchronized khiến virtual thread bị ghim cứng vào carrier thread vì JVM gắn quyền sở hữu monitor với carrier thread chứ không phải với virtual thread, nên không thể tráo carrier giữa chừng.",
        "Trong sự cố sản xuất tháng 7/2024, Netflix ghi nhận việc ghim làm cạn kiệt toàn bộ carrier thread khiến ứng dụng trông như vẫn sống — vẫn nhận kết nối, dashboard vẫn xanh — nhưng không xử lý được việc gì.",
        "JEP 491 đã sửa lỗi ghim do synchronized kể từ Java 24; cùng một đoạn mã từng làm treo hệ thống ở Netflix chạy trên JDK 24 trở lên không còn gây deadlock nữa.",
    ],
    [  # 4. tách monolith thành service
        "Strangler Fig — đặt tên và mô tả lần đầu bởi Martin Fowler năm 2004 — ví việc thay thế hệ thống cũ như cách cây đa bóp cổ leo quấn quanh một cây chủ và dần thay thế nó, thay vì chặt bỏ cây chủ ngay từ đầu.",
        "Cách tiếp cận này trích dần từng tính năng ra khỏi khối monolith và dựng một ứng dụng mới bao quanh hệ thống cũ, để người dùng chuyển sang phần vừa tách một cách từ từ thay vì một lần cắt toàn bộ.",
        "Giá trị chính của strangler fig nằm ở việc giảm rủi ro khi thay thế hệ thống lớn trong lúc vẫn giao được giá trị nghiệp vụ nhanh, đổi lại là phải duy trì đồng thời cả lớp định tuyến cũ và mới suốt quá trình chuyển.",
    ],
    [  # 5. thiết kế API phân trang
        "Stripe dùng cursor dạng starting_after/ending_before trỏ thẳng vào id của một đối tượng đã có trong danh sách, và hai tham số này loại trừ nhau — không dùng được đồng thời trong cùng một lượt gọi.",
        "Giới hạn limit trong API phân trang của Stripe chỉ nhận giá trị từ 1 đến 100 mỗi trang, và tài liệu khuyến cáo luôn dựa vào cờ has_more thay vì tự đếm để biết còn dữ liệu hay không.",
        "Với endpoint tìm kiếm, Stripe lại dùng một dạng con trỏ khác — tham số page nhận giá trị next_page trả về từ lượt gọi trước, tách bạch hẳn với cơ chế starting_after dùng cho list thông thường.",
    ],
    [  # 6. xử lý idempotency cho webhook
        "Stripe cam kết chỉ giao webhook theo kiểu ít-nhất-một-lần, không bao giờ đúng-một-lần — cùng một sự kiện đến hai hay ba lần được xem là hành vi bình thường, không phải lỗi.",
        "Mỗi sự kiện của Stripe giữ nguyên event id qua mọi lần gửi lại, và nếu endpoint trả về mã khác 2xx hoặc time out, Stripe sẽ thử gửi lại theo cơ chế exponential backoff kéo dài tới ba ngày.",
        "Idempotency-Key ở tầng API request và event id ở tầng webhook là hai cơ chế khác nhau dù cùng mục đích chống trùng: request gửi lên Stripe dùng Idempotency-Key tự đặt, còn sự kiện Stripe gửi xuống dùng chính event id làm khoá khử trùng.",
    ],
    [  # 7. chuẩn hoá log có traceId
        "Chuẩn W3C Trace Context định nghĩa header traceparent theo đúng khuôn: phiên bản, traceId, id của span cha, và cờ trạng thái lấy mẫu, cho phép nhiều hệ thống giám sát khác nhau hiểu chung một ngữ cảnh vết.",
        "traceId giữ nguyên suốt một request xuyên nhiều service trong khi spanId đổi ở mỗi chặng — đúng cơ chế cho phép nối lại toàn bộ hành trình của một request chỉ từ một traceId duy nhất.",
        "Micrometer Tracing ghi traceId và spanId thẳng vào MDC của SLF4J trong suốt vòng đời một span, nên mọi dòng log sinh ra trong khoảng đó tự mang theo hai giá trị này mà không cần code nào truyền tay.",
    ],
    [  # 8. đo p99 thay vì trung bình
        "Trong một ví dụ được sách Site Reliability Engineering của Google trích lại, độ trễ trung bình một hệ thống có thể chỉ 80ms trong khi p99 lên tới 2,4 giây — một con số trung bình duy nhất không kể được câu chuyện đó.",
        "Với hệ thống phục vụ mười triệu request mỗi ngày, một p99 ở mức 1.200ms nghĩa là một trăm nghìn lượt trải nghiệm chậm mỗi ngày, ẩn hoàn toàn phía sau một chỉ số trung bình vẫn trông khoẻ mạnh.",
        "Percentile không cộng gộp được theo phép trung bình đơn giản: nếu vùng A có p99 200ms và vùng B có p99 800ms thì p99 toàn cục không phải là 500ms — đây là lỗi thường gặp khi gộp báo cáo độ trễ từ nhiều vùng.",
    ],
    [  # 9. giảm thời gian build CI
        "Một dự án mã nguồn mở ghi nhận giảm được một nửa thời gian build sau khi áp dụng chiến lược cache phụ thuộc đúng cách trong CI, không cần đổi logic build.",
        "Một phép đo thực tế cho thấy bước cài npm install giảm từ ba phút xuống còn khoảng bốn mươi giây — gần năm lần — chỉ nhờ cache đúng theo hash của lockfile.",
        "Cache lớp layer khi build Docker image trong CI từng đưa thời gian build từ khoảng năm phút xuống còn một phút trong một phép đo thực tế, cho thấy cache tầng layer cũng đáng giá không kém cache phụ thuộc gói.",
    ],
    [  # 10. viết test không phụ thuộc thứ tự
        "Test phụ thuộc thứ tự thường bắt nguồn từ việc một test để lại dữ liệu, cấu hình hay phiên làm việc mà test chạy sau đó vô tình đọc phải — một khảo sát ghi nhận phần lớn trường hợp được khắc phục chỉ bằng cách dọn sạch trạng thái dùng chung giữa các lượt chạy.",
        "JUnit khuyến nghị mọi trạng thái cần thiết cho một test nên được dựng tường minh trong @BeforeEach hoặc ngay trong thân test, thay vì ngầm định dựa vào trạng thái mà test chạy trước để lại.",
        "Chỉ khi thật sự cần một thứ tự cố định — như test khói cho migration hay luồng happy-path đầu-cuối — mới nên khai báo tường minh bằng @TestMethodOrder, còn lại nên để thứ tự chạy ngẫu nhiên để lộ sớm phụ thuộc ẩn.",
    ],
    [  # 11. mock ít đi, dùng testcontainers
        "Một hệ thống test qua được với H2 vẫn có thể giấu kín lỗi cú pháp SQL đặc thù của Postgres cho tới tận lúc triển khai — H2 và HSQL chạy nhanh nhưng không hành xử giống hệt database production.",
        "Testcontainers dựng container thật cho từng lượt test rồi huỷ ngay sau đó, nên mỗi test được cô lập hoàn toàn và không dính trạng thái còn sót từ những lượt chạy trước — khác hẳn cách mock hay service giả lập chia sẻ trạng thái ngầm.",
        "Những vấn đề tích hợp thật sự — như connection pool, chạy migration, hay timeout kết nối — chỉ lộ ra khi test chạy trên đúng loại hệ thống mà production dùng, điều mock không bao giờ mô phỏng được.",
    ],
    [  # 12. dựng design token dùng chung
        "Style Dictionary biên dịch một bộ token định nghĩa một lần thành CSS custom properties, hằng số màu Swift và tài nguyên XML Android — mỗi nền tảng đọc đúng định dạng của nó từ cùng một nguồn.",
        "Từ phiên bản 4, Style Dictionary hỗ trợ thẳng định dạng chuẩn DTCG (Design Tokens Community Group), nên file token xuất từ Figma hay Tokens Studio nạp thẳng vào pipeline build mà không cần chuyển đổi tay.",
        "Đổi một giá trị token rồi chạy lại pipeline build là đủ để giá trị mới lan ra mọi nền tảng đang tiêu thụ token đó, thay vì phải sửa từng file cấu hình riêng của iOS, Android và web.",
    ],
    [  # 13. giảm layout shift
        "Telegraph Media Group giảm CLS ở phân vị 75 từ 0,25 xuống 0,1 chỉ trong vài tháng, đưa tỷ lệ trang đạt chuẩn Core Web Vitals từ 57% lên 72%.",
        "Nguyên nhân chính gây layout shift ở họ là các khối nhúng giữa bài viết — tweet, video, widget theo dõi số liệu — không có kích thước cố định trước khi tải xong.",
        "Giải pháp là chuẩn hoá kích thước ô quảng cáo theo đúng các cỡ phổ biến và chừa sẵn chỗ hiển thị, cộng với sửa lại cách tải ảnh, tiêu đề và video nhúng.",
    ],
    [  # 14. làm form truy cập được bằng bàn phím
        "WCAG 2.4.3 (mức A) yêu cầu thứ tự focus khi duyệt tuần tự bằng bàn phím phải giữ đúng ý nghĩa và cách vận hành của trang, không được nhảy lộn xộn.",
        "Thứ tự tab chuẩn phải khớp với thứ tự đọc hợp lý trên giao diện, và không dùng giá trị tabindex dương vì nó phá vỡ trật tự tự nhiên của DOM.",
        "Trên một form đăng nhập, sau ô cuối cùng, focus phải đi tiếp xuống phần nội dung logic kế tiếp của trang chứ không được nhảy ngược lên đầu trang.",
    ],
    [  # 15. chia bundle theo route
        "React.lazy() kết hợp Suspense là cơ chế chính thức để dừng gửi kèm mã chưa cần dùng ngay trong lần tải đầu tiên.",
        "Điểm yếu chính của lazy loading theo route là độ trễ ở lần điều hướng đầu: người dùng bấm link, Suspense hiện fallback, trình duyệt tải chunk, chunk chạy xong component mới hiện ra.",
        "Muốn code splitting hoạt động, điểm vào của ứng dụng không được import tĩnh các component định tách riêng — import tĩnh sẽ kéo hết chúng trở lại đúng một bundle.",
    ],
    [  # 16. quản lý state không cần thư viện
        "useReducer hợp khi một state có nhiều giá trị phụ thuộc lẫn nhau hoặc cần một mô hình cập nhật kiểu Redux thu nhỏ mà không phải cài thêm thư viện.",
        "Ghép useReducer với useContext cho phép một component gọi dispatch mà không tự render lại mỗi khi state đổi, miễn tách state và dispatch thành hai context riêng.",
        "Giới hạn của cách này là context làm mọi consumer render lại khi giá trị đổi dù component chỉ đọc một phần nhỏ, nên ứng dụng có nhiều state dùng chung thường phải chuyển sang một store ngoài.",
    ],
    [  # 17. đồng bộ dữ liệu khi mất mạng
        "CRDT cho phép nhiều thiết bị sửa dữ liệu độc lập khi mất mạng rồi tự hợp nhất về cùng một trạng thái lúc đồng bộ lại, không cần máy chủ đứng ra phân xử.",
        "Automerge tập trung vào cấu trúc dữ liệu kiểu JSON và vừa có bản viết lại bằng Rust, xử lý được tài liệu lớn hơn hẳn so với bản JavaScript thuần trước đó.",
        "PowerSync tự thân không đi kèm mô hình CRDT — mặc định xử lý xung đột theo kiểu ghi sau thắng, nên muốn hợp nhất theo kiểu CRDT thật sự phải ghép thêm một thư viện như Yjs.",
    ],
    [  # 18. giảm kích thước app
        "Trung bình Android App Bundle giảm khoảng 20% kích thước app so với đóng gói APK truyền thống, vì mỗi thiết bị chỉ tải đúng phần tài nguyên khớp cấu hình của nó.",
        "LinkedIn từng ghi nhận giảm 23% kích thước app còn Twitter giảm tới 35% sau khi chuyển sang Android App Bundle, cao hơn hẳn mức trung bình toàn hệ sinh thái.",
        "Theo nghiên cứu của Google, cứ tăng 6MB dung lượng APK phân phối thì tỷ lệ cài đặt giảm khoảng 1%, nên kích thước app ảnh hưởng trực tiếp tới số lượt cài chứ không chỉ là chuyện kỹ thuật.",
    ],
    [  # 19. bảo mật token trên thiết bị
        "iOS Keychain và Android Keystore đều dựa vào phần cứng bảo mật riêng — Secure Enclave trên iOS, Hardware Security Module trên Android — để giữ khoá mã hoá tách biệt khỏi hệ điều hành chính.",
        "Trên Android, khoá sinh qua Keystore được gắn với đúng phần cứng của thiết bị đó, và bản thân khoá không bao giờ rời khỏi vùng an toàn kể cả khi ứng dụng đang đọc dữ liệu đã giải mã.",
        "Khuyến nghị bảo mật phổ biến là dùng Keychain/Keystore thay vì lưu trữ thường, đặt lịch xoay vòng token định kỳ, và luôn kiểm thử hành vi ứng dụng trên thiết bị đã jailbreak hoặc root.",
    ],
    [  # 20. rà soát phụ thuộc bên thứ ba
        "Log4Shell (CVE-2021-44228) nguy hiểm trên diện rộng vì Log4j nằm sâu bên trong hàng loạt framework Apache khác như Struts2, Solr và Druid, kéo theo vô số ứng dụng bên thứ ba dùng lại các framework đó.",
        "Phần lớn tổ chức bị ảnh hưởng không biết mình đang chạy Log4j vì nó tồn tại như một dependency gián tiếp, chôn sâu vài lớp trong cây phụ thuộc chứ không khai trực tiếp.",
        "Mức độ nghiêm trọng của lỗ hổng khiến CISA phải ra hướng dẫn khẩn cấp riêng, nhấn mạnh việc phải rà cả dependency gián tiếp chứ không chỉ dependency khai trực tiếp trong file quản lý gói.",
    ],
    [  # 21. dựng pipeline triển khai xanh-lam
        "Theo tài liệu AWS, môi trường xanh giữ bản đang chạy thật còn môi trường lam chạy bản mới; load balancer chỉ chuyển traffic sang lam sau khi bản mới đã được kiểm xong.",
        "Rollback trong mô hình này chỉ là trỏ listener của load balancer về lại target group xanh, không cần triển khai lại gì — AWS mô tả đây là rollback gần như tức thời, tính bằng giây thay vì phút.",
        "Với Amazon ECS, nếu phát hiện lỗi ngay trong lúc triển khai, hệ thống có thể tự động định tuyến traffic quay lại bản dịch vụ chính mà không cần ai can thiệp tay.",
    ],
    [  # 22. đặt resource limit cho pod
        "Khi một container vượt quá memory limit đã khai, kernel Linux buộc phải kill tiến trình đó và Kubernetes ghi nhận lại bằng trạng thái OOMKilled kèm exit code 137.",
        "Request là mức tài nguyên tối thiểu được đảm bảo và dùng để lập lịch — Kubernetes sẽ không đặt pod vào node nào không đủ đáp ứng đúng con số request đó.",
        "Một cách đặt số phổ biến là để memory request bằng mức dùng ổn định ở phân vị 95, còn limit cao hơn request khoảng 1,5-2 lần để chịu được các đợt tăng tải hoặc áp lực từ garbage collector.",
    ],
    [  # 23. chuyển state Terraform lên remote
        "Từ Terraform 1.10, backend S3 hỗ trợ khoá state ngay trong chính S3 qua tuỳ chọn use_lockfile, không còn bắt buộc phải dựng thêm một bảng DynamoDB riêng chỉ để khoá.",
        "Tham số dynamodb_table để khoá theo kiểu cũ vẫn hoạt động nhưng đã bị đánh dấu deprecated từ Terraform 1.11 và sẽ bị gỡ ở một bản sau.",
        "Khuyến nghị chuẩn là tách state theo từng môi trường và từng thành phần hạ tầng thay vì gộp toàn bộ vào một file state duy nhất, để giảm rủi ro một lần apply ảnh hưởng nhầm sang tài nguyên không liên quan.",
    ],
    [  # 24. giảm hoá đơn cloud
        "Chuyển tải sang gói cam kết trước (savings plan/reserved instance) và dọn sạch tài nguyên nhàn rỗi thường cắt được 20-40% hoá đơn cloud mà không cần đổi kiến trúc.",
        "Môi trường test/dev bị quên chạy 24/7 là một trong những khoản lãng phí lớn nhất trên hoá đơn cloud — tắt ngoài giờ làm việc có thể cắt tới hai phần ba chi phí của riêng nhóm tài nguyên đó.",
        "FinOps đặt việc nhìn thấy rõ khoản nào đang tốn tiền lên trước việc cắt giảm — một đội không biết dịch vụ nào ngốn ngân sách thì đổi loại instance kiểu gì cũng khó tối ưu đúng chỗ.",
    ],
    [  # 25. đưa mô hình từ notebook lên production
        "Notebook được dựng để thử nghiệm chứ không phải để chạy production — nó xử lý phụ thuộc, mở rộng quy mô và quản lý phiên bản đều kém.",
        "Đánh số phiên bản cho từng lần huấn luyện và lưu lại đầy đủ metadata — siêu tham số, ảnh chụp dữ liệu huấn luyện, chỉ số đánh giá — là điều kiện để biết được model đang chạy production sinh ra từ đâu.",
        "Một model chạy tốt trên máy phát triển vẫn có thể chậm hẳn khi đưa vào production vì chưa tối ưu độ trễ, nên kiểm tra trên dữ liệu thật ở môi trường staging là bước bắt buộc trước khi bung toàn bộ.",
    ],
    [  # 26. làm sạch dữ liệu đầu vào
        "Con số 'khoa học dữ liệu tốn 80% thời gian dọn dữ liệu' bắt nguồn từ một bài báo năm 2014 trích lời chuyên gia chứ không phải một khảo sát chính thức, dù nhiều khảo sát sau đó vẫn xác nhận dọn dữ liệu chiếm phần lớn thời gian.",
        "Một khảo sát về công cụ dữ liệu ghi nhận khoảng 45% thời gian của người làm dữ liệu dành cho chuẩn bị dữ liệu, riêng phần làm sạch chiếm khoảng một phần tư.",
        "Một khảo sát khác ghi nhận con số thấp hơn hẳn, chỉ khoảng 15%, khi định nghĩa làm sạch tách riêng khỏi thu thập và gán nhãn — cho thấy con số phụ thuộc nhiều vào việc tính bước nào là 'làm sạch'.",
    ],
    [  # 27. chọn giữa batch và streaming
        "Batch xử lý được khối lượng dữ liệu lớn với thông lượng cao nhưng độ trễ tính bằng phút tới giờ; streaming ưu tiên độ trễ mili giây tới giây nhưng đổi lại phải chạy hạ tầng liên tục 24/7.",
        "Một job batch lỗi có thể chạy lại toàn bộ mà không lo mất nhất quán; một pipeline streaming lỗi cần checkpoint và khôi phục đúng trạng thái, nên gỡ lỗi khó hơn hẳn.",
        "Nếu độ trễ vài phút tới vài giờ vẫn chấp nhận được, batch cho cùng kết quả với chi phí vận hành thấp hơn và ít phức tạp hơn streaming.",
    ],
    [  # 28. đánh giá chất lượng embedding
        "MTEB đánh giá embedding trên hơn 50 bộ dữ liệu thuộc 8 nhóm tác vụ khác nhau — phân loại, gom cụm, truy hồi, đo tương đồng ngữ nghĩa — trải trên hơn 100 ngôn ngữ.",
        "Nhiều model embedding từng được huấn luyện trên dữ liệu trùng với chính bộ MTEB dùng để chấm điểm, khiến thứ hạng trên bảng xếp hạng không phản ánh đúng khả năng tổng quát hoá.",
        "Phần đánh giá truy hồi của MTEB dùng nhãn liên quan kiểu nhị phân đúng/sai, trong khi truy hồi thực tế có nhiều mức độ liên quan — một tài liệu có thể liên quan một phần chứ không chỉ đúng hoặc sai hẳn.",
    ],
    [  # 29. viết prompt ổn định qua nhiều lần chạy
        "Đặt temperature về 0 khiến model luôn chọn token có xác suất cao nhất, cho kết quả gần như xác định — cách phổ biến nhất để tăng tính lặp lại của output.",
        "Dù đặt temperature 0 và cố định seed, nhiều LLM production vẫn cho ra kết quả khác nhau giữa các lần gọi giống hệt nhau — xác định trên giấy không đồng nghĩa xác định trong thực tế vận hành.",
        "Một số nhà cung cấp API cho phép truyền tham số seed cùng với giữ nguyên mọi tham số khác như prompt và temperature để tăng khả năng lặp lại kết quả, dù không đảm bảo tuyệt đối.",
    ],
    [  # 30. phỏng vấn không hỏi thuật toán
        "Một danh sách công khai từng tổng hợp hơn 900 công ty không dùng bài kiểm tra thuật toán trên bảng trắng khi phỏng vấn kỹ sư.",
        "Một hướng thay thế phổ biến là bài tập mang về nhà — ứng viên làm trong môi trường quen thuộc, gần với công việc thật hơn một bài toán thuật toán viết tay trong 45 phút.",
        "Pair programming trong buổi phỏng vấn cho thấy cách ứng viên suy nghĩ và trao đổi khi giải quyết vấn đề, thay vì chỉ xem kết quả cuối cùng có chạy được hay không.",
    ],
    [  # 31. nhận review mà không tự ái
        "Nguyên tắc thường được nhắc trong hướng dẫn code review: người review đang đánh giá đoạn mã, không đánh giá người viết ra nó.",
        "Mục tiêu của một lượt phản hồi là cải thiện đoạn mã chứ không phải bảo vệ nó — giữ tâm thế đó giúp tách được góp ý hữu ích khỏi cảm giác bị chỉ trích.",
        "Khi phản hồi không rõ ràng, hỏi lại ví dụ cụ thể thay vì tự đoán ý là cách tránh hiểu lầm phổ biến nhất giữa người review và người được review.",
    ],
    [  # 32. dẫn dắt một đội bốn người
        "Với một đội nhỏ, biết rõ điểm mạnh, điểm yếu và động lực của từng người quan trọng hơn quy trình quản lý hình thức.",
        "Vai trò chính của người dẫn dắt là dọn đường: xử lý các bên liên quan khó tính, làm rõ ưu tiên đang đổi liên tục, và chặn bớt gián đoạn để đội tập trung code, thiết kế, giải quyết vấn đề.",
        "Nhiều tech lead vẫn trực tiếp code cùng đội thay vì chỉ giao việc — vừa giữ được cảm giác về hệ thống, vừa là cách mentor tự nhiên nhất.",
    ],
    [  # 33. viết tài liệu mà người ta chịu đọc
        "Một trang tài liệu chỉ nên giải quyết đúng một việc — cấu trúc theo kiểu người đọc lướt qua tìm câu trả lời, không đọc từ đầu tới cuối như một bài luận.",
        "Người viết tài liệu kỹ thuật thường mắc lỗi giả định người đọc đã biết trước quá nhiều — viết như thể người đọc hoàn toàn mới giúp tài liệu dùng được rộng hơn.",
        "Một ví dụ cụ thể kèm ảnh chụp màn hình thường làm rõ một khái niệm nhanh hơn nhiều đoạn văn giải thích.",
    ],
    [  # 34. ước lượng công việc sát hơn
        "Hình nón bất định (cone of uncertainty) được Barry Boehm quan sát từ đầu thập niên 1980, sau này Steve McConnell đặt tên và phổ biến trong ước lượng phần mềm — sai số ước lượng ở đầu dự án rất lớn và chỉ thu hẹp dần khi công việc gần xong.",
        "Planning poker không cố thắng hình nón bất định — nó chấp nhận ước lượng đầu kỳ vốn không chính xác, đổi lấy một con số tương đối nhanh, rồi ước lượng lại khi công việc rõ ràng hơn.",
        "Độ bất định trong ước lượng phần mềm đến từ nhiều nguồn cộng dồn — phạm vi tính năng chưa chốt, rủi ro kiến trúc, biến động nhân sự — không chỉ từ việc đội ước lượng dở.",
    ],
]
assert len(TOPIC_FACTS) == len(TOPICS), (len(TOPIC_FACTS), len(TOPICS))
assert all(len(facts) == 3 for facts in TOPIC_FACTS)

# Các mẫu câu để nội dung KHÔNG cần đánh số ("số 3", "phần 7", "Câu hỏi 12") mà vẫn không trùng
# nhau. Mỗi mẫu quay vòng với chu kỳ nguyên tố cùng nhau với len(TOPICS)=35, nên tổ hợp
# (mẫu, chủ đề) trải đủ dài để không cặp bài nào giống hệt cặp khác trong cùng loại.
EVENT_INTROS = [
    "Buổi chia sẻ về {t}",
    "Ngồi lại nói chuyện {t}",
    "Chia sẻ nội bộ về {t}",
    "Một buổi tối bàn về {t}",
    "Meetup nhỏ về {t}",
    "Cà phê kỹ thuật: chuyện {t}",
    "Kể chuyện nghề: {t}",
    "Nhóm mình mở buổi nói về {t}",
    "Chiều thứ sáu bàn về {t}",
]
ARTICLE_TITLES = [
    "Ghi chép về {t}",
    "Nhìn lại hành trình {t}",
    "Chuyện {t}, từ đầu tới lúc ổn",
    "Những gì mình học được khi {t}",
    "Nhật ký {t}",
    "Kể lại lần {t}",
    "Tổng kết đợt {t}",
    "Bài học sau khi {t}",
]
QNA_OPENERS = [
    "Có ai từng làm {t} chưa?",
    "Nhờ mọi người tư vấn chuyện {t}.",
    "Đang bí phần {t}, ai gặp rồi cho xin hướng.",
    "Hỏi nhỏ: {t} nên bắt đầu từ đâu?",
    "Team mình đang vướng {t}.",
    "Cần lời khuyên về {t}.",
    "Mình loay hoay mãi với {t}.",
    "Ai rành {t} không ạ?",
]
QNA_TAILS = [
    "Mình mắc ở bước thứ hai và chưa tìm ra hướng nào chạy được.",
    "Thử vài cách trên mạng nhưng đều không hợp với hệ đang chạy.",
    "Chưa rõ nên sửa ở tầng ứng dụng hay tầng hạ tầng.",
    "Chạy ổn trên máy mình nhưng lên staging thì hỏng.",
    "Sếp hỏi ước lượng mà mình chưa dám chốt.",
]
POLL_INTROS = [
    "Khảo sát nhanh, mong mọi người bấm giúp.",
    "Tiện thể hỏi cả nhà một câu.",
    "Bình chọn nhẹ cho vui, ai rảnh bấm giúp.",
    "Đang tò mò mọi người làm thế nào.",
    "Một câu thăm dò, không mất quá mười giây.",
]
ANGLES = [
    "Ghi lại sau một tuần vật lộn",
    "Bài học rút ra sau khi làm hỏng một lần",
    "Cách đội mình đang làm",
    "Ba thứ mình ước biết sớm hơn",
    "So sánh hai hướng đã thử",
    "Một mẹo nhỏ nhưng tiết kiệm được nhiều thời gian",
    "Vì sao mình đổi ý",
    "Thử nghiệm cuối tuần",
    "Chép lại từ buổi review hôm nay",
    "Câu hỏi mình vẫn chưa trả lời được",
    "Bản tóm tắt cho ai chưa gặp vấn đề này",
    "Điều mà tài liệu chính thức không nói",
]
# DETAILS/MEASURES (số đo bịa "2.4s", "180ms" gắn khống vào bất kỳ chủ đề nào) đã bỏ — thay bằng
# TOPIC_FACTS ở trên: mỗi bài REGULAR/ARTICLE/QNA/EVENT giờ mang một sự kiện THẬT đúng chủ đề của
# nó, không phải một con số ngẫu nhiên rút từ một danh sách chung cho mọi chủ đề.

# Nội dung seed KHÔNG được đánh số thứ tự máy ("(#123)", "số 4", "phần 7"): trên giao diện nó lộ
# ngay ra là dữ liệu sinh hàng loạt. Cách chống trùng thay thế: xoay vòng nhiều mẫu câu với các
# chu kỳ NGUYÊN TỐ CÙNG NHAU, để (mẫu_1, mẫu_2, …) trải dài hơn số bản ghi cần sinh. guard_machine
# _numbering() trong SqlFile.write() chặn xuất file nếu "(#\d+)" lọt lại.
#
# 13 nguyên tố cùng nhau với 420 = len(TOPICS) * len(ANGLES); ghép vào bài REGULAR thì bộ bốn
# (angle, topic, detail, tail) mới đủ dài để không hai bài nào trùng nội dung.
REGULAR_TAILS = [
    "Ai đang làm khác thì kể mình nghe với.",
    "Viết vội trước khi quên, có gì sai nhờ mọi người chỉ thêm.",
    "Để đây phòng khi sáu tháng nữa chính mình quay lại đọc.",
    "Không chắc đây là cách tốt nhất, nhưng nó đang chạy ổn.",
    "Mất công cả tuần nên chép lại cho người sau đỡ khổ.",
    "Chi tiết dài hơn mình để trong phần bình luận.",
    "Nếu cần mình gửi thêm biểu đồ đo trước và sau.",
    "Cảm ơn hai đồng nghiệp đã ngồi debug cùng tối hôm đó.",
    "Chỗ này mình vẫn muốn nghe góc nhìn ngược lại.",
    "Bài học chính: đo trước, đoán sau.",
    "Hoá ra phần khó nhất lại là thuyết phục cả đội đổi thói quen.",
    "Sẽ cập nhật lại nếu sau một tháng nữa nó vẫn ổn.",
    "Có thể hoàn cảnh của bạn khác, cân nhắc trước khi áp thẳng.",
]

# {lang} được điền bằng ngôn ngữ của đoạn mã. 11 mẫu nguyên tố cùng nhau với 15 = len(SNIPPET_LANGS).
CODE_SNIPPET_NOTES = [
    "Đoạn {lang} mình hay chép lại giữa các dự án.",
    "Mẫu {lang} nhỏ, để đây cho ai cần dùng nhanh.",
    "Bản {lang} rút gọn sau khi bỏ hết phần không cần thiết.",
    "Đoạn {lang} này giải quyết đúng một việc, không hơn.",
    "Ghi lại đoạn {lang} vì lần nào cũng phải tra lại.",
    "Phiên bản {lang} mình thấy dễ đọc nhất trong mấy cách đã thử.",
    "Đoạn {lang} gọn để dán vào review cho nhanh.",
    "Mẫu {lang} chạy được, chưa tối ưu, dùng tạm thì ổn.",
    "Đoạn {lang} kèm vài chú thích ở chỗ dễ nhầm.",
    "Bản {lang} cuối cùng sau ba lần viết lại.",
    "Đoạn {lang} này tránh được cái bẫy mình từng dính.",
]

# Ghép với title của LINK_SOURCES (12 nguồn); 11 nguyên tố cùng nhau với 12.
LINK_INTROS = [
    "Bài này giải thích rõ hơn mọi thứ mình từng đọc:",
    "Lưu lại để đọc kỹ cuối tuần —",
    "Đọc xong thấy tiếc vì không gặp sớm hơn:",
    "Chia sẻ cho ai đang tìm hiểu chủ đề này:",
    "Một bài cũ nhưng vẫn đúng —",
    "Phần giữa hơi dài, nhưng phần kết đáng giá:",
    "Đây là nguồn mình hay dẫn lại khi tranh luận:",
    "Ngắn, đúng trọng tâm, không lan man:",
    "Tác giả viết từ kinh nghiệm thật chứ không phải lý thuyết:",
    "Bài này thay đổi cách mình nghĩ về vấn đề:",
    "Để đây kèm một câu tóm tắt cho bạn nào bận:",
]

# BOOK: (intro, chủ đề) — 11 nguyên tố cùng nhau với 13, đủ cho 80 bài BOOK không trùng.
BOOK_POST_INTROS = [
    "Vừa đọc xong một cuốn về",
    "Gấp lại cuốn sách sau hai tuần, chủ đề",
    "Đọc chậm hết một cuốn nói về",
    "Cuốn này mình đọc đi đọc lại, xoay quanh",
    "Mới xong phần hay nhất của một cuốn về",
    "Một cuốn mỏng nhưng chắc, viết về",
    "Đọc xong và muốn giới thiệu, chủ đề",
    "Cuốn sách đầu năm mình đọc hết, về",
    "Bỏ dở nửa năm rồi quay lại đọc nốt, chủ đề",
    "Vừa khép lại một cuốn dày, nói về",
    "Đọc theo lời giới thiệu của đồng nghiệp, một cuốn về",
]
BOOK_TAKEAWAYS = [
    "Nhiều chỗ mình gật gù vì đã tự học được bằng cách làm sai.",
    "Chương giữa hơi lê thê, nhưng phần đầu và cuối rất đáng.",
    "Sẽ để trên bàn làm việc và đọc lại từng phần khi cần.",
    "Hợp với người đã đi làm vài năm hơn là người mới.",
    "Có vài ví dụ cũ, nhưng nguyên tắc thì vẫn đúng.",
    "Đọc xong muốn viết lại một service theo cách sách gợi ý.",
    "Ai đang phân vân thì mượn mình bản giấy cũng được.",
    "Ngắn gọn, không lên gân, mình thích giọng văn này.",
    "Phần bài tập cuối chương mới là chỗ đáng giá nhất.",
    "Không có công thức thần kỳ, chủ yếu là cách nghĩ.",
    "Mình sẽ tóm tắt lại vài ý cho buổi chia sẻ tháng sau.",
]
BOOK_SUBJECTS = [
    "thiết kế hệ thống chịu tải",
    "thói quen làm việc của kỹ sư lâu năm",
    "cách một đội nhỏ giữ chất lượng mã",
    "những quyết định kiến trúc từng đi sai",
    "kỹ năng viết và giao tiếp trong kỹ thuật",
    "vận hành hệ thống lúc nửa đêm",
    "cách đọc mã của người khác",
    "trả nợ kỹ thuật mà không viết lại từ đầu",
    "phỏng vấn và xây đội",
    "tư duy dữ liệu cho người làm sản phẩm",
    "bảo mật nhìn từ phía người phòng thủ",
    "hiệu năng web đo bằng số thật",
    "chuyển từ lập trình viên sang người dẫn dắt",
]


SNIPPET_LANGS = ["java", "typescript", "python", "sql", "shell", "json", "css", "plaintext",
                 "kotlin", "yaml", "go", "rust", "hcl", "graphql",
                 # Cố ý một ngôn ngữ NGOÀI danh sách bộ tô màu nhận ra, để nhánh "không tô màu
                 # được thì hiện chữ thường" có dữ liệu chạy.
                 "zig"]

# Snippet dài (>=30 dòng) — fixture cho nhánh cuộn dọc của khối mã.
LONG_SNIPPET = """@Service
@RequiredArgsConstructor
public class FeedAssembler {

  private final PostRepository postRepository;
  private final ReactionRepository reactionRepository;
  private final CommentRepository commentRepository;

  /**
   * Gom một trang bảng tin trong đúng ba truy vấn, thay vì một truy vấn cho mỗi bài.
   */
  @Transactional(readOnly = true)
  public List<FeedItem> assemble(List<Long> postIds, Integer viewerId) {
    if (postIds.isEmpty()) {
      return List.of();
    }

    Map<Long, PostEntity> posts = postRepository.findAllByIdIn(postIds).stream()
        .collect(Collectors.toMap(PostEntity::getId, Function.identity()));

    Map<Long, Long> reactionCounts = reactionRepository.countByPostIdIn(postIds).stream()
        .collect(Collectors.toMap(CountRow::postId, CountRow::total));

    Map<Long, Long> commentCounts = commentRepository.countRootByPostIdIn(postIds).stream()
        .collect(Collectors.toMap(CountRow::postId, CountRow::total));

    return postIds.stream()
        .map(posts::get)
        .filter(Objects::nonNull)
        .map(post -> new FeedItem(
            post,
            reactionCounts.getOrDefault(post.getId(), 0L),
            commentCounts.getOrDefault(post.getId(), 0L)))
        .toList();
  }
}"""

# Snippet có MỘT dòng rất dài — nhánh cuộn NGANG, khác hẳn nhánh xuống dòng ở trên.
WIDE_SNIPPET = """-- Một dòng dài hơn 120 ký tự, để kiểm nhánh cuộn ngang của khối mã.
SELECT u.id, u.username, u.full_name, u.elite_score, p.job_title, p.seniority_level, p.years_of_experience, p.primary_role, COUNT(po.id) AS post_count
  FROM socialapp.t_users u
  LEFT JOIN socialapp.t_user_professional_profiles p ON p.user_id = u.id
  LEFT JOIN socialapp.t_posts po ON po.author_id = u.id
 GROUP BY u.id, p.job_title, p.seniority_level, p.years_of_experience, p.primary_role;"""

# Thân mỗi snippet viết thành DANH SÁCH DÒNG rồi nối bằng chr(10), không dùng chuỗi thoát.
# Lý do: mọi ký tự điều khiển ở đây sẽ được json.dumps escape khi ghi ra jsonb, nên nguồn
# giữ ký tự thật là cách duy nhất để cái nhìn thấy trong file này đúng bằng cái chạy ra.
_NL = chr(10)
SNIPPET_BODIES = {
    "java": _NL.join([
        'var stats = sessionFactory.getStatistics();',
        'stats.setStatisticsEnabled(true);',
        'service.loadFeed(userId);',
        'assertThat(stats.getPrepareStatementCount()).isLessThan(5);',
    ]),
    "typescript": _NL.join([
        'export function useDebounced<T>(value: T, delay = 300): T {',
        '  const [v, setV] = useState(value);',
        '  useEffect(() => {',
        '    const id = setTimeout(() => setV(value), delay);',
        '    return () => clearTimeout(id);',
        '  }, [value, delay]);',
        '  return v;',
        '}',
    ]),
    "python": _NL.join([
        'daily = (df.assign(day=df.created_at.dt.floor("D"))',
        '           .groupby(["day", "source"], as_index=False)',
        '           .agg(total=("score", "sum")))',
    ]),
    "sql": _NL.join([
        'CREATE INDEX CONCURRENTLY idx_posts_author_created',
        '    ON socialapp.t_posts (author_id, created_at DESC);',
    ]),
    "shell": _NL.join([
        'docker compose down -v',
        'python scripts/seed/generate_seed.py',
        'docker compose up -d',
    ]),
    "json": _NL.join([
        '{',
        '  "minioUrl": "http://localhost:9000",',
        '  "buckets": ["books", "book-covers", "post-media"]',
        '}',
    ]),
    "css": _NL.join([
        '.feed-card {',
        '  container-type: inline-size;',
        '}',
        '@container (min-width: 480px) {',
        '  .feed-card__body { display: grid; grid-template-columns: 1fr auto; }',
        '}',
    ]),
    "plaintext": _NL.join([
        'statement_timeout = 15s            <- tran cua pool Hikari',
        'SET LOCAL statement_timeout = 0    <- trong giao dich cua migration',
    ]),
    "kotlin": _NL.join([
        'val feed = repo.observeFeed()',
        '    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())',
    ]),
    "yaml": _NL.join([
        'healthcheck:',
        '  test: ["CMD", "mc", "ready", "local"]',
        '  interval: 5s',
        '  retries: 20',
    ]),
    "go": _NL.join([
        'func (s *Server) handleFeed(w http.ResponseWriter, r *http.Request) {',
        '\tctx, cancel := context.WithTimeout(r.Context(), 2*time.Second)',
        '\tdefer cancel()',
        '\ts.render(ctx, w)',
        '}',
    ]),
    "rust": _NL.join([
        'let mut seen = HashSet::new();',
        'posts.retain(|p| seen.insert(p.id));',
    ]),
    "hcl": _NL.join([
        'resource "aws_s3_bucket_versioning" "this" {',
        '  bucket = aws_s3_bucket.this.id',
        '  versioning_configuration { status = "Enabled" }',
        '}',
    ]),
    "graphql": _NL.join([
        'query {',
        '  user(login: "octocat") {',
        '    pinnedItems(first: 6) { nodes { ... on Repository { name } } }',
        '  }',
        '}',
    ]),
    "zig": _NL.join([
        'const std = @import("std");',
        'pub fn main() !void {',
        '    std.debug.print("seed", .{});',
        '}',
    ]),
}

LINK_SOURCES = [
    ("https://www.postgresql.org/docs/16/indexes.html", "PostgreSQL: Indexes",
     "Chương về index trong tài liệu chính thức, từ B-tree tới GIN và partial index."),
    ("https://react.dev/reference/rsc/server-components", "React Server Components",
     "Tài liệu chính thức về server components và ranh giới client/server."),
    ("https://owasp.org/www-project-top-ten/", "OWASP Top 10",
     "Danh sách mười rủi ro bảo mật ứng dụng web phổ biến nhất."),
    ("https://cloud.google.com/apis/design", "Google API Design Guide",
     "Nguyên tắc thiết kế API nhất quán, phần đặt tên tài nguyên rất đáng đọc."),
    ("https://kubernetes.io/docs/concepts/configuration/manage-resources-containers/",
     "Managing Resources for Containers",
     "Phân biệt request và limit, và điều gì xảy ra khi container vượt ngưỡng."),
    ("https://playwright.dev/docs/trace-viewer", "Playwright Trace Viewer",
     "Xem lại từng bước của một lần chạy test đã hỏng, kèm ảnh chụp và network log."),
    ("https://ml-ops.org/", "MLOps Principles",
     "Tổng hợp nguyên tắc vận hành hệ thống machine learning trong sản xuất."),
    ("https://developer.hashicorp.com/terraform/language/state", "Terraform State",
     "Vì sao state tồn tại, và vì sao không nên để nó trên máy cá nhân."),
    ("https://cbea.ms/git-commit/", "How to Write a Git Commit Message",
     "Bảy quy tắc viết commit message, phần giải thích vì sao dùng thể mệnh lệnh rất thuyết phục."),
    ("https://redis.io/docs/latest/develop/data-types/sorted-sets/", "Redis Sorted Sets",
     "Cấu trúc đứng sau bảng tin: xếp hạng theo điểm, cắt trang bằng ZREVRANGE."),
    ("https://docs.docker.com/compose/compose-file/", "Compose file reference",
     "Tham chiếu đầy đủ, phần depends_on với condition hay bị bỏ qua."),
    ("https://neo4j.com/docs/cypher-manual/current/clauses/merge/", "Cypher MERGE",
     "Vì sao MERGE làm script nạp đồ thị chạy lại được nhiều lần mà không nhân đôi cạnh."),
]

# ── Quiz ───────────────────────────────────────────────────────────────────────────────────────
#
# quiz_details KHÔNG phải một PostType riêng — enum PostType không có giá trị QUIZ. Bất kỳ bài nào
# cũng mang được một quiz, và QuizService chỉ kiểm tra post.quizDetails có khác null hay không.
#
# Ràng buộc PostService.validateQuizDetails áp: tiêu đề không rỗng, ít nhất một câu hỏi, mỗi câu ít
# nhất 2 lựa chọn, và correctOptionIndex nằm trong khoảng của options.
#
# MỖI QUIZ ĐÚNG 3 CÂU. QuizService bắt bài nộp phải có SỐ ĐÁP ÁN BẰNG số câu hỏi, và V84 sinh bài
# nộp dựa vào đúng con số 3 này. Thêm một câu vào đây mà quên sửa V84 là mọi bài nộp bị từ chối.
QUIZZES = [
    ("Kiểm tra nhanh: @Transactional", [
        ("Mặc định Spring rollback khi gặp loại ngoại lệ nào?",
         ["Mọi Exception", "Chỉ RuntimeException và Error", "Chỉ checked exception", "Không tự rollback"], 1,
         "Mặc định chỉ rollback với unchecked exception; muốn rollback cho checked exception phải khai báo rollbackFor."),
        ("Gọi một phương thức @Transactional từ chính bên trong cùng lớp thì sao?",
         ["Vẫn mở giao dịch bình thường", "Proxy bị bỏ qua nên không có giao dịch", "Ném lỗi khi khởi động", "Tạo giao dịch lồng nhau"], 1,
         "Giao dịch được cài qua proxy; gọi nội bộ không đi qua proxy nên annotation không có tác dụng."),
        ("readOnly = true mang lại lợi ích rõ nhất nào?",
         ["Tăng tốc ghi", "Hibernate bỏ qua dirty checking", "Tự động thêm index", "Nén dữ liệu trả về"], 1,
         "Không cần so sánh trạng thái trước và sau nên tiết kiệm cả bộ nhớ lẫn thời gian ở cuối giao dịch."),
    ]),
    ("Kiểm tra nhanh: Index Postgres", [
        ("Partial index là gì?",
         ["Index chỉ trên các hàng thoả điều kiện WHERE", "Index chia theo phân vùng", "Index chứa một phần giá trị cột", "Index tạm trong bộ nhớ"], 0,
         "Partial index chỉ lập chỉ mục cho hàng khớp mệnh đề WHERE, nên nhỏ và rẻ hơn nhiều."),
        ("Thứ tự cột trong index tổ hợp có quan trọng không?",
         ["Không, Postgres tự sắp lại", "Có, truy vấn phải dùng tiền tố trái", "Chỉ với index UNIQUE", "Chỉ khi có ORDER BY"], 1,
         "Index B-tree tổ hợp chỉ dùng được khi điều kiện phủ từ cột đầu tiên trở đi."),
        ("GIN index hợp với kiểu dữ liệu nào?",
         ["integer", "jsonb và mảng", "boolean", "timestamp"], 1,
         "GIN thiết kế cho giá trị chứa nhiều phần tử con như jsonb, mảng và full-text search."),
    ]),
    ("Kiểm tra nhanh: React Hooks", [
        ("useEffect không truyền mảng phụ thuộc thì chạy khi nào?",
         ["Chỉ một lần khi mount", "Sau mỗi lần render", "Không bao giờ chạy", "Chỉ khi unmount"], 1,
         "Thiếu mảng phụ thuộc nghĩa là effect chạy lại sau mọi lần render."),
        ("useMemo dùng để làm gì?",
         ["Ghi nhớ kết quả tính toán tốn kém", "Thay thế useState", "Gọi API", "Quản lý route"], 0,
         "useMemo giữ lại kết quả và chỉ tính lại khi phụ thuộc thay đổi."),
        ("Vì sao không gọi hook trong vòng lặp hay câu điều kiện?",
         ["Vì cú pháp không cho phép", "Vì React khớp state theo thứ tự gọi hook", "Vì gây rò rỉ bộ nhớ", "Vì hook chỉ chạy trên server"], 1,
         "React khớp state với hook theo thứ tự gọi; thứ tự đổi giữa các lần render là state gắn nhầm chỗ."),
    ]),
    ("Kiểm tra nhanh: Docker", [
        ("Khác nhau giữa image và container là gì?",
         ["Không khác, chỉ là tên gọi", "Image là bản mẫu chỉ đọc, container là tiến trình chạy từ nó", "Container nhẹ hơn image", "Image chạy được, container thì không"], 1,
         "Image là các lớp chỉ đọc; container thêm một lớp ghi được và một tiến trình."),
        ("COPY khác ADD ở điểm nào?",
         ["Không khác gì", "ADD giải nén tar và tải được URL", "COPY nhanh hơn", "ADD chỉ dùng cho thư mục"], 1,
         "ADD làm thêm hai việc ngầm, nên COPY được khuyến nghị khi không cần hai việc đó."),
        ("Vì sao nên gộp lệnh RUN?",
         ["Để mã ngắn hơn", "Vì mỗi RUN tạo một lớp image mới", "Vì Docker giới hạn số RUN", "Không nên gộp"], 1,
         "Mỗi chỉ thị tạo một lớp; gộp lại giảm số lớp và kích thước image cuối."),
    ]),
    ("Kiểm tra nhanh: Redis", [
        ("Sorted set dùng để làm gì trong bảng tin?",
         ["Lưu phiên đăng nhập", "Xếp hạng bài theo điểm và cắt trang", "Nén dữ liệu", "Thay thế database"], 1,
         "ZADD gán điểm, ZREVRANGE cắt trang — đúng hình dạng của một bảng tin."),
        ("SETEX khác SET ở chỗ nào?",
         ["Ghi nhanh hơn", "Đặt kèm thời gian sống", "Ghi vào đĩa ngay", "Chỉ ghi nếu chưa tồn tại"], 1,
         "SETEX gộp SET và EXPIRE thành một lệnh, tránh trạng thái key không có TTL."),
        ("Vì sao không nên dùng KEYS trên production?",
         ["Vì trả sai kết quả", "Vì nó quét toàn bộ không gian khoá và chặn server", "Vì cần quyền admin", "Vì đã bị gỡ bỏ"], 1,
         "Redis chạy một luồng; KEYS quét toàn bộ và chặn mọi lệnh khác trong lúc đó."),
    ]),
    ("Kiểm tra nhanh: HTTP", [
        ("Mã 401 khác 403 thế nào?",
         ["Giống nhau", "401 là chưa xác thực, 403 là đã xác thực nhưng không đủ quyền", "401 là lỗi server", "403 nghĩa là không tìm thấy"], 1,
         "401 nói 'bạn là ai?', 403 nói 'biết bạn là ai rồi, nhưng không cho'."),
        ("PUT khác PATCH ở điểm nào?",
         ["Không khác", "PUT thay toàn bộ tài nguyên, PATCH sửa một phần", "PATCH nhanh hơn", "PUT không có body"], 1,
         "PUT mang ngữ nghĩa thay thế nguyên vẹn, nên thiếu trường là xoá trường đó."),
        ("Idempotent nghĩa là gì?",
         ["Chạy nhanh", "Gọi nhiều lần cho cùng kết quả như gọi một lần", "Không có tác dụng phụ nào", "Luôn trả 200"], 1,
         "Đó là lý do webhook cần khoá idempotency: mạng lỗi thì bên gửi sẽ thử lại."),
    ]),
    ("Kiểm tra nhanh: Kiểm thử", [
        ("Vì sao test không nên phụ thuộc thứ tự chạy?",
         ["Vì chạy chậm hơn", "Vì đỏ ngẫu nhiên và rất khó tái hiện", "Vì trình chạy không hỗ trợ", "Vì tốn bộ nhớ"], 1,
         "Test phụ thuộc thứ tự sẽ đỏ khi ai đó thêm một test mới ở giữa, chẳng liên quan gì tới thay đổi của họ."),
        ("Testcontainers giải quyết vấn đề gì so với H2?",
         ["Chạy nhanh hơn", "Chạy trên đúng database mà production dùng", "Không cần Docker", "Tự sinh dữ liệu"], 1,
         "H2 không có unaccent, không có jsonb như Postgres, nên test xanh mà production vẫn hỏng."),
        ("Mock quá nhiều dẫn tới điều gì?",
         ["Test chạy chậm", "Test kiểm chứng chính cái mock chứ không phải hệ thống", "Không biên dịch được", "Tăng độ phủ giả"], 1,
         "Khi mọi cộng tác viên đều là mock, test chỉ còn chứng minh rằng bạn đã viết đúng cái mock."),
    ]),
    ("Kiểm tra nhanh: Flyway", [
        ("Vì sao không được sửa một migration đã apply?",
         ["Vì file bị khoá", "Vì checksum lệch và validate-on-migrate sẽ chặn khởi động", "Vì Flyway xoá file cũ", "Không sao cả"], 1,
         "Chuyện này đã một lần làm production không khởi động được — xem README của db/seed."),
        ("Flyway phân giải version thế nào khi có nhiều location?",
         ["Mỗi location một dãy riêng", "Một dãy duy nhất cho tất cả", "Theo thứ tự khai báo", "Theo thời gian sửa file"], 1,
         "Vì vậy trùng số giữa db/seed và db/migration là lỗi khởi động, không phải cảnh báo."),
        ("placeholder được thay ở giai đoạn nào?",
         ["Sau khi parse SQL", "Khi đọc file, trước khi parse", "Lúc commit", "Không bao giờ"], 1,
         "Nên một placeholder chưa khai nằm trong dòng comment cũng đủ giết migration."),
    ]),
]


EVENT_PLACES = [
    "Dreamplex, 195 Điện Biên Phủ, Bình Thạnh, TP.HCM",
    "Toong Coworking, 126 Nguyễn Thị Minh Khai, Quận 3, TP.HCM",
    "Circo Coworking, 267 Nguyễn Thị Minh Khai, Quận 1, TP.HCM",
    "UP Coworking, 1 Đại Cồ Việt, Hai Bà Trưng, Hà Nội",
    "Hive Coworking, 94 Xuân Thuỷ, Cầu Giấy, Hà Nội",
    "Đại học Bách khoa Đà Nẵng, 54 Nguyễn Lương Bằng",
]


def post_age(rng, index, total):
    """Tuổi bài tính bằng ngày, trải 18 tháng với mật độ TĂNG DẦN về hiện tại.

    Phân phối đều làm bảng tin trông như một kho lưu trữ: cùng số bài ở tháng thứ 18 và tuần trước.
    Bình phương tỉ lệ kéo phần lớn bài về gần hiện tại, giống một cộng đồng đang lớn dần — và đó
    cũng là thứ làm cho việc cuộn bảng tin có ý nghĩa.
    """
    ratio = (index + rng.random()) / total
    return max(1, int(540 * (ratio ** 2)) + 1)


def build_posts(rng, people, edges):
    friends = {}
    for a, b in edges:
        friends.setdefault(a, []).append(b)
        friends.setdefault(b, []).append(a)

    authors = [p["id"] for p in people if p["primary_role"]]
    role_of = {p["id"]: p["primary_role"] for p in people if p["primary_role"]}

    # ── Ai viết nhiều, ai viết ít ─────────────────────────────────────────────────────────────
    #
    # Chọn tác giả ĐỀU NHAU là sai bản chất, và nó hỏng theo một cách rất cụ thể: 2.600 bài chia
    # đều cho 498 người thành ~5 bài mỗi người, không ai đủ hoạt động để vượt ngưỡng 100 điểm của
    # CONTRIBUTOR bằng một biên độ đáng kể — và bộ seed lại rơi đúng vào căn bệnh mà nó sinh ra để
    # chữa: ai cũng `Newcomer`, thanh tiến độ hạng phẳng lì.
    #
    # Cộng đồng thật theo phân phối luỹ thừa: một nhóm nhỏ viết rất nhiều, phần lớn viết thỉnh
    # thoảng, một phần không viết gì. Trọng số dưới đây tái tạo hình dạng đó, và nó là thứ khiến
    # elite_score trải ra được — mà vẫn dẫn xuất từ hoạt động NHÌN THẤY ĐƯỢC trên giao diện, chứ
    # không phải một con số gán thêm.
    weights = {}
    for i, uid in enumerate(sorted(authors)):
        rank = (i * 7919) % len(authors)          # xáo tất định, không theo id
        if rank < 12:
            weights[uid] = 60                     # nhóm viết rất khoẻ
        elif rank < 60:
            weights[uid] = 18
        elif rank < 180:
            weights[uid] = 6
        elif rank < 400:
            weights[uid] = 2
        else:
            weights[uid] = 0                      # người chỉ đọc, không viết
    # Tài khoản demo "cao thủ" phải nằm ở nhóm đầu — đó là cả lý do nó tồn tại. 9002 là "người
    # mới" nên cố ý ở đáy: hồ sơ của họ phải trông đúng như một người vừa tham gia.
    weights[DEMO_EXPERT] = 90
    weights[DEMO_NEWCOMER] = 0

    weighted_authors = [uid for uid in authors if weights[uid] > 0]
    weight_list = [weights[uid] for uid in weighted_authors]

    posts = []
    seen_content = set()
    order = 0
    total = sum(n for _, n in POST_RANGES.values())

    def add(kind, pid, content, author, detail=None, images=None, visibility="PUBLIC",
            moderation="APPROVED", tag_count=None, age=None):
        nonlocal order
        # detail phải là DICT, không phải chuỗi JSON tự ghép. Ghép tay đã lọt một ký tự TAB thô
        # vào đoạn mã Go, sinh ra JSON hỏng mà Postgres chỉ từ chối lúc migrate — tức là ở
        # production. jdoc() escape đúng mọi thứ, nhưng chỉ khi nó được đưa một dict.
        if detail is not None and not isinstance(detail, dict):
            sys.exit(f"DỪNG — bài {pid}: detail phải là dict, nhận {type(detail).__name__}")
        if content in seen_content:
            sys.exit(f"DỪNG — hai bài trùng khít nội dung: {content[:60]!r}")
        seen_content.add(content)
        pool = friends.get(author, [])
        tagged = rng.sample(pool, min(len(pool), rng.choice([0, 0, 0, 1, 1, 2])))
        picks = rng.sample(range(len(HASHTAGS)), tag_count or rng.choice([1, 2, 2, 3, 3, 4]))
        posts.append({
            "id": pid, "kind": kind, "content": content, "author_id": author,
            "visibility": visibility, "moderation": moderation, "detail": detail,
            "images": images or [], "hashtags": sorted(picks), "tagged": sorted(tagged),
            "age_days": age if age is not None else post_age(rng, order, total),
        })
        order += 1

    def pick_author(role=None):
        if role:
            candidates = [a for a in weighted_authors if role_of[a] == role]
            if candidates:
                return rng.choice(candidates)
        return rng.choices(weighted_authors, weights=weight_list)[0]

    # ── EVENT ──────────────────────────────────────────────────────────────────────────────────
    # ~70% sự kiện SẮP diễn ra (RSVP mới có nghĩa), ~30% ĐÃ diễn ra để nhánh "sự kiện kết thúc" có
    # dữ liệu. Chia bằng (i*7)%10 < 7 — mỗi giá trị 0-9 xuất hiện đúng một lần mỗi 10 nên tỉ lệ
    # đúng 70/30 mà vẫn rải đều. Ngày tính từ TODAY (hằng số cố định). created_at của bài (age)
    # đặt tường minh cho khớp: sự kiện tương lai thì vừa mới đăng, sự kiện quá khứ thì đăng trước
    # khi nó diễn ra.
    start, count = POST_RANGES["EVENT"]
    for i in range(count):
        pid = start + i
        ti = i % len(TOPICS)
        topic, _ = TOPICS[ti]
        teaser = TOPIC_FACTS[ti][(i // len(TOPICS)) % 3]
        author = pick_author()
        online = i % 3 == 0
        if (i * 7) % 10 < 7:
            day_offset = rng.randint(3, 90)
            announced_days_ago = rng.randint(1, 45)
            tail = "đăng ký sớm còn chỗ nhé cả nhà."
        else:
            day_offset = -rng.randint(2, 120)
            announced_days_ago = -day_offset + rng.randint(3, 20)
            tail = "buổi này đã diễn ra, video và slide mình để ở phần bình luận."
        when = TODAY + timedelta(days=day_offset)
        detail = {
            "eventTitle": f"Buổi chia sẻ: {topic}",
            "eventDescription": f"Trình bày 30 phút, hỏi đáp 30 phút. {teaser}",
            "startTime": f"{when.isoformat()}T18:30:00+07:00",
            "endTime": f"{when.isoformat()}T21:00:00+07:00",
            "timezone": "Asia/Ho_Chi_Minh",
            "location": None if online else EVENT_PLACES[i % len(EVENT_PLACES)],
            "onlineUrl": f"https://meet.google.com/seed-event-{pid}" if online else None,
            "maxAttendees": 20 + (i % 8) * 10,
        }
        intro = EVENT_INTROS[i % len(EVENT_INTROS)].format(t=topic)
        add("EVENT", pid, f"{intro} — {tail}",
            author, detail=detail, age=announced_days_ago)

    # ── CODE_SNIPPET ───────────────────────────────────────────────────────────────────────────
    start, count = POST_RANGES["CODE_SNIPPET"]
    for i in range(count):
        pid = start + i
        lang = SNIPPET_LANGS[i % len(SNIPPET_LANGS)]
        if i == 0:
            lang, body = "java", LONG_SNIPPET
            note = "Đoạn gom một trang bảng tin trong đúng ba truy vấn — dài nhưng đọc một mạch được."
        elif i == 1:
            lang, body = "sql", WIDE_SNIPPET
            note = "Truy vấn thống kê hồ sơ; cố ý để một dòng rất dài."
        else:
            body = SNIPPET_BODIES[lang]
            note = CODE_SNIPPET_NOTES[i % len(CODE_SNIPPET_NOTES)].format(lang=lang)
        add("CODE_SNIPPET", pid, note, pick_author(),
            detail={"language": lang, "code": body})

    # ── ARTICLE ────────────────────────────────────────────────────────────────────────────────
    start, count = POST_RANGES["ARTICLE"]
    for i in range(count):
        pid = start + i
        ti = i % len(TOPICS)
        topic, _ = TOPICS[ti]
        fact_a, fact_b = TOPIC_FACTS[ti][i % 3], TOPIC_FACTS[ti][(i + 1) % 3]
        cover = None
        if i % 4 == 0:
            key = f"posts/{pid}/cover.png"
            cover = want_image(key, "article-cover",
                               f"https://picsum.photos/seed/art{pid}/640/360")
        title = ARTICLE_TITLES[i % len(ARTICLE_TITLES)].format(t=topic)
        detail = {
            "title": title,
            "coverImage": cover,
            "summary": fact_a,
        }
        add("ARTICLE", pid,
            f"{title}. {fact_a} {fact_b}",
            pick_author(), detail=detail)

    # ── QNA ────────────────────────────────────────────────────────────────────────────────────
    start, count = POST_RANGES["QNA"]
    for i in range(count):
        pid = start + i
        ti = i % len(TOPICS)
        topic, _ = TOPICS[ti]
        # acceptedAnswerId để None ở đây: nó phải trỏ tới một BÌNH LUẬN CÓ THẬT của chính bài
        # này, mà bình luận thì tới V84 mới tồn tại. V84 cập nhật lại — xem chú thích ở file đó.
        #
        # "Cái đã biết" (tried) là một sự kiện THẬT về chủ đề — người hỏi đã đọc/thử đúng kỹ thuật
        # chuẩn, nhưng vẫn vướng; đó là câu hỏi thật hơn nhiều so với một người chưa biết gì.
        opener = QNA_OPENERS[i % len(QNA_OPENERS)].format(t=topic)
        tried = TOPIC_FACTS[ti][(i // len(TOPICS)) % 3]
        tail = QNA_TAILS[i % len(QNA_TAILS)]
        add("QNA", pid,
            f"{opener} Đã thử theo hướng: {tried[0].lower()}{tried[1:]} {tail}",
            pick_author(),
            detail={"isResolved": i % 3 == 0, "bountyPoints": (i % 5) * 25,
                    "acceptedAnswerId": None})

    # ── POLL ───────────────────────────────────────────────────────────────────────────────────
    start, count = POST_RANGES["POLL"]
    poll_sets = [
        ("Dự án của bạn đang dùng gì để quản lý migration?",
         ["Flyway", "Liquibase", "Tự viết script", "Không dùng gì cả"]),
        ("Bạn chạy test tích hợp bằng gì?",
         ["Testcontainers", "H2 in-memory", "Database dùng chung", "Không có test tích hợp"]),
        ("Đội bạn review code thế nào?",
         ["Bắt buộc 1 người duyệt", "Bắt buộc 2 người", "Tuỳ tình huống", "Không review"]),
        ("Bạn đo hiệu năng bằng chỉ số nào?",
         ["p50", "p95", "p99", "Trung bình"]),
        ("Thư mục seed nên chạy ở đâu?",
         ["Chỉ dev", "Dev và staging", "Cả production", "Không dùng seed"]),
        ("Bạn deploy lên production bằng cách nào?",
         ["Tự động khi merge", "Bấm tay qua CI", "Chạy script trên máy", "Có người trực deploy"]),
        ("Đội bạn viết tài liệu ở đâu?",
         ["Wiki nội bộ", "File markdown trong repo", "Google Docs", "Gần như không viết"]),
        ("Bạn giữ secret lúc dev thế nào?",
         ["Vault hoặc secret manager", "File .env không commit", "Biến môi trường máy", "Ghi thẳng trong config"]),
        ("Nhánh chính của repo bạn tên gì?",
         ["main", "master", "develop", "Tuỳ repo"]),
        ("Bạn chạy CI trên đâu?",
         ["GitHub Actions", "GitLab CI", "Jenkins", "Tự dựng"]),
        ("Bao lâu bạn nâng phiên bản thư viện một lần?",
         ["Hằng tuần", "Hằng tháng", "Khi có lỗ hổng", "Khi buộc phải"]),
        ("Bạn theo dõi lỗi production bằng gì?",
         ["Sentry", "Log tập trung", "Dashboard tự dựng", "Đợi người dùng báo"]),
        ("Độ phủ test của dự án bạn khoảng bao nhiêu?",
         ["Trên 80%", "50-80%", "Dưới 50%", "Không đo"]),
        ("Bạn gọi thử API lúc dev bằng gì?",
         ["Postman", "curl", "HTTP client trong IDE", "Viết test luôn"]),
        ("Standup của đội bạn kéo dài bao lâu?",
         ["Dưới 10 phút", "10-20 phút", "Trên 20 phút", "Không có standup"]),
        ("Bạn viết commit message theo quy ước nào?",
         ["Conventional Commits", "Có tiền tố tự quy ước", "Câu mô tả tự do", "Không quan tâm"]),
    ]
    # ~75% khảo sát CÒN mở (endDate ở tương lai), ~25% ĐÃ đóng — nhánh "poll kết thúc, chỉ xem
    # kết quả" cần dữ liệu. Poll đã đóng thì created_at phải nằm TRƯỚC endDate, nên đặt age tường minh.
    for i in range(count):
        pid = start + i
        question, options = poll_sets[i % len(poll_sets)]
        if (i * 3) % 4 != 0:
            end = TODAY + timedelta(days=rng.choice([10, 20, 30, 45, 90]))
            age = None
        else:
            closed_days_ago = rng.choice([4, 10, 20, 45])
            end = TODAY - timedelta(days=closed_days_ago)
            age = closed_days_ago + rng.randint(7, 90)
        detail = {
            "question": question,
            "options": [
                {"id": j + 1, "text": text, "votesCount": rng.randint(2, 60)}
                for j, text in enumerate(options)
            ],
            "allowMultipleVotes": i % 4 == 0,
            "endDate": f"{end.isoformat()}T23:59:59+07:00",
        }
        add("POLL", pid, f"{POLL_INTROS[i % len(POLL_INTROS)]} {question}", pick_author(),
            detail=detail, age=age)

    # ── LINK ───────────────────────────────────────────────────────────────────────────────────
    start, count = POST_RANGES["LINK"]
    for i in range(count):
        pid = start + i
        url, title, desc = LINK_SOURCES[i % len(LINK_SOURCES)]
        # Một phần ba số link CHƯA có thumbnail: đó là trạng thái của một link vừa dán, chưa unfurl
        # xong. Nếu link nào cũng có ảnh thì nhánh "chưa unfurl" không bao giờ hiện.
        if i % 3 == 0:
            thumb = None
        else:
            key = f"posts/{pid}/thumb.png"
            thumb = want_image(key, "link-thumb",
                               f"https://picsum.photos/seed/link{pid}/640/360")
        add("LINK", pid, f"{LINK_INTROS[i % len(LINK_INTROS)]} {title}",
            pick_author(),
            detail={"url": url, "title": title, "description": desc, "thumbnailUrl": thumb})

    # ── BOOK ───────────────────────────────────────────────────────────────────────────────────
    start, count = POST_RANGES["BOOK"]
    for i in range(count):
        pid = start + i
        add("BOOK", pid,
            f"{BOOK_POST_INTROS[i % len(BOOK_POST_INTROS)]} "
            f"{BOOK_SUBJECTS[i % len(BOOK_SUBJECTS)]}. "
            f"{BOOK_TAKEAWAYS[i % len(BOOK_TAKEAWAYS)]}",
            pick_author())

    # ── REGULAR ────────────────────────────────────────────────────────────────────────────────
    start, count = POST_RANGES["REGULAR"]
    # Ba chỗ cuối dải dành cho fixture độ dài và fixture ảnh hỏng — xem ngay sau vòng lặp.
    for i in range(count - 3):
        pid = start + i
        ti = i % len(TOPICS)
        topic, tag = TOPICS[ti]
        angle = ANGLES[(i // len(TOPICS)) % len(ANGLES)]
        # Sự kiện THẬT của đúng chủ đề này (xem TOPIC_FACTS) — thay cho detail bịa số đo cũ.
        # (i // len(TOPICS)) đổi mỗi khi vòng lặp quay lại cùng topic, nên ba lượt quay đầu của
        # cùng một topic dùng ba sự kiện khác nhau trước khi lặp ở lượt thứ tư.
        detail_text = TOPIC_FACTS[ti][(i // len(TOPICS)) % 3]
        # i % 13 có chu kỳ nguyên tố cùng nhau với 420 = len(TOPICS) * len(ANGLES) và dịch 4 nấc
        # mỗi vòng 420, nên bộ bốn (angle, topic, detail, tail) chỉ lặp lại sau 5.460 bài — xa hơn
        # cả dải REGULAR. Thay cho "(#N)" cũ: không hai bài nào trùng khít nội dung.
        tail = REGULAR_TAILS[i % len(REGULAR_TAILS)]
        content = f"{angle}: {topic}. {detail_text} {tail}"

        images = []
        # Ba bố cục ảnh khác nhau — một, hai, và nhiều hơn bốn — vì lưới ảnh xử lý mỗi ca một khác.
        if i % 37 == 0:
            n = 1
        elif i % 53 == 0:
            n = 2
        elif i % 101 == 0:
            n = 4
        else:
            n = 0
        for j in range(n):
            key = f"posts/{pid}/img{j + 1}.png"
            images.append(want_image(key, "post-image",
                                     f"https://picsum.photos/seed/p{pid}x{j}/640/360"))

        # Kiểm duyệt không đồng nhất: hàng đợi của admin cần có việc thật.
        #
        # `i > 0` ở cả ba nhánh KHÔNG phải để cho đẹp. Mọi phép chia dư đều trúng i = 0, nên bài
        # REGULAR đầu dải lĩnh trọn cả FRIENDS lẫn PENDING_REVIEW cùng lúc — mà đó đúng là bài
        # build_engagement() lấy làm fixture "không có bình luận nào", và V92 gắn hashtag
        # fixture_zero_comments vào. Kết quả: ca kiểm ấy trả 404 khi mở qua API (bài chờ duyệt,
        # lại chỉ hiện với bạn bè) trong khi SeedMigrationTest đọc thẳng SQL nên vẫn xanh. Bảy bài
        # đầu dải là bài fixture; giữ chúng ở trạng thái bình thường là điều kiện để chúng dùng
        # được.
        moderation = "APPROVED"
        if i > 0 and i % 211 == 0:
            moderation = "PENDING_REVIEW"
        elif i > 0 and i % 307 == 0:
            moderation = "REJECTED"

        visibility = "FRIENDS" if i > 6 and i % 23 == 0 else "PUBLIC"
        add("REGULAR", pid, content, pick_author(), images=images,
            visibility=visibility, moderation=moderation)


    # ── Ba fixture cuối dải REGULAR ────────────────────────────────────────────────────────────
    # Chúng nằm ở đây chứ không ở V92 vì cả ba là THUỘC TÍNH CỦA BÀI: đưa sang V92 thì file đó
    # phải chèn thêm bài, và số bài mỗi loại sẽ không còn khớp bảng dải id ở đầu file này.
    fx = start + count - 3

    # 1. Bài rất dài. Thẻ hiển thị bài cắt bớt nội dung và hiện nút "xem thêm"; không có bài nào
    #    vượt ngưỡng thì nhánh cắt chưa từng chạy, và một component cắt NHẦM MỌI BÀI vẫn qua test.
    long_body = " ".join(
        f"{ANGLES[k % len(ANGLES)]}: {TOPICS[k % len(TOPICS)][0]}. "
        f"{TOPIC_FACTS[k % len(TOPICS)][k % 3]}"
        for k in range(9)
    )
    assert len(long_body) >= 1200, len(long_body)
    add("REGULAR", fx, long_body, pick_author())

    # 2. Bài trung bình (550-750 ký tự). Chỉ có bài dài thì không phân biệt được "cắt đúng" với
    #    "cắt tất". Bài này phải hiện TRỌN VẸN, không nút xem thêm.
    mid_body = " ".join(
        f"{ANGLES[(k + 3) % len(ANGLES)]}: {TOPICS[(k + 11) % len(TOPICS)][0]}. "
        f"{TOPIC_FACTS[(k + 11) % len(TOPICS)][(k + 1) % 3]}"
        for k in range(3)
    )
    assert 550 <= len(mid_body) <= 750, len(mid_body)
    add("REGULAR", fx + 1, mid_body, pick_author())

    # 3. Bài trỏ tới một object CỐ Ý KHÔNG TỒN TẠI trong MinIO. Key mang chuỗi `khong-ton-tai` và
    #    KHÔNG được khai qua want_image, nên nó không vào manifest, không được tải lên — URL dựng
    #    được, trình duyệt gọi được, và nhận 404. Đó là nhánh "ảnh không tải được" mà frontend đã
    #    dịch sẵn thông điệp nhưng chưa bao giờ chạy thật.
    #
    #    URL vẫn phải mang đủ segment bucket như mọi ảnh khác: một URL thiếu bucket trả 403 chứ
    #    không phải 404, và hai mã đó đi vào hai nhánh xử lý khác nhau ở phía trình duyệt. Fixture
    #    này chỉ có giá trị khi nó hỏng ĐÚNG kiểu mà một object thiếu sẽ hỏng.
    add("REGULAR", fx + 2,
        "Trước và sau khi thêm đúng index tổ hợp khớp thứ tự lọc, query plan đổi hẳn — chụp lại "
        "EXPLAIN ANALYZE ở đây cho ai muốn so sánh trực tiếp.",
        pick_author(),
        images=["${minioUrl}/" + BUCKET_OF_PREFIX["posts"]
                + "/posts/" + str(fx + 2) + "/khong-ton-tai.png"])

    # Tám bài mang quiz: trải trên cả REGULAR lẫn ARTICLE, giống cách dùng thật.
    regular_ids = [p["id"] for p in posts if p["kind"] == "REGULAR"][:600]
    article_ids = [p["id"] for p in posts if p["kind"] == "ARTICLE"][:200]
    hosts = rng.sample(regular_ids, 5) + rng.sample(article_ids, 3)
    quiz_posts = []
    for pid, (title, questions) in zip(sorted(hosts), QUIZZES):
        quiz_posts.append((pid, {
            "title": title,
            "questions": [
                {"question": qq, "options": opts, "correctOptionIndex": idx, "explanation": expl}
                for qq, opts, idx, expl in questions
            ],
        }))

    return posts, quiz_posts, weights


def emit_posts(rng, people, posts, quiz_posts):
    """posts: danh sách dict đã dựng sẵn ở build_posts."""
    f = SqlFile(83, "seed_posts",
                f"{len(posts)} bài viết trải đủ 8 giá trị PostType, kèm hashtag, gắn thẻ và ảnh.")
    f.note(f"""
FILE NÀY SINH TỰ ĐỘNG bởi scripts/seed/generate_seed.py — sửa tay sẽ bị ghi đè.

Id cấp TƯỜNG MINH (100001-102600) chứ không để sequence tự sinh: V84-V92 phải trỏ tới từng bài cụ
thể — bình luận vào bài nào, sách gắn bài nào, log kiểm duyệt của bài nào. Để sequence cấp thì các
file sau phải dò lại bài bằng cách so khớp nội dung, vừa dài dòng vừa hỏng ngay khi ai sửa câu chữ.

Dải id theo loại:
{chr(10).join(f"  {kind:<13} {start}-{start + n - 1}  ({n} bài)" for kind, (start, n) in POST_RANGES.items())}

Ảnh dùng placeholder ${{minioUrl}} nên chạy đúng ở cả dev lẫn production. Object tương ứng do
`docker compose up` nạp (minio-seed-objects đọc key từ chính file này, minio-init tải lên). MỘT ảnh
cố ý mang key `khong-ton-tai` và KHÔNG được nạp — đó là fixture cho nhánh "ảnh không tải được".

Trạng thái kiểm duyệt cố ý không đồng nhất: /posts/public chỉ trả bài APPROVED, còn
/admin/moderation/pending cần bài PENDING_REVIEW mới có gì để trình bày.
""")
    f.rule()

    f.note("""
── Hashtag ──────────────────────────────────────────────────────────────────────────────────
usage_count được TÍNH LẠI từ t_post_hashtags ở cuối file, không gõ tay, để con số trên trang
hashtag luôn khớp với số bài thật sự gắn thẻ đó.
""")
    f.sql(
        "INSERT INTO socialapp.t_hashtags (id, name, usage_count, created_at) VALUES\n"
        + ",\n".join(
            f"    ({1001 + i}, {q(name)}, 0, now() - INTERVAL '{540 - i * 4} days')"
            for i, name in enumerate(HASHTAGS)
        )
        + ";",
        rows=len(HASHTAGS),
    )

    # Mỗi loại bài một câu INSERT riêng: cột chi tiết khác nhau, và gộp lại thì phải điền NULL cho
    # bảy cột jsonb ở mỗi hàng — dài hơn mà khó đọc hơn.
    by_kind = {}
    for p in posts:
        by_kind.setdefault(p["kind"], []).append(p)

    detail_column = {
        "EVENT": "event_details", "CODE_SNIPPET": "code_snippet_details",
        "ARTICLE": "article_details", "QNA": "qna_details", "POLL": "poll_details",
        "LINK": "link_details",
    }

    for kind, (start, count) in POST_RANGES.items():
        group = by_kind[kind]
        column = detail_column.get(kind)
        cols = ["id", "content", "visibility", "author_id", "post_type", "moderation_status"]
        if column:
            cols.append(column)
        cols += ["images", "created_at", "updated_at"]

        f.note(f"── {kind} ({start}-{start + count - 1}) " + "─" * max(0, 60 - len(kind)))
        rows = []
        for p in group:
            values = [str(p["id"]), q(p["content"]), q(p["visibility"]), str(p["author_id"]),
                      q(kind), q(p["moderation"])]
            if column:
                values.append(jdoc(p["detail"]))
            values.append(jsonb(p["images"]) if p["images"] else "NULL")
            values.append(f"now() - INTERVAL '{p['age_days']} days'")
            values.append(f"now() - INTERVAL '{p['age_days']} days'")
            rows.append("    (" + ", ".join(values) + ")")
        f.sql(
            f"INSERT INTO socialapp.t_posts\n    ({', '.join(cols)}) VALUES\n"
            + ",\n".join(rows) + ";",
            rows=len(rows),
        )

    f.note("""
── Gắn hashtag vào bài ──────────────────────────────────────────────────────────────────────
""")
    tag_rows = [f"    ({p['id']}, {1001 + t})" for p in posts for t in p["hashtags"]]
    f.sql(
        "INSERT INTO socialapp.t_post_hashtags (post_id, hashtag_id) VALUES\n"
        + ",\n".join(tag_rows) + ";",
        rows=len(tag_rows),
    )

    f.note("""
── Gắn thẻ người vào bài ────────────────────────────────────────────────────────────────────
Chỉ gắn thẻ người mà tác giả ĐÃ là bạn: gắn thẻ người lạ là thứ luồng thật không cho làm.
""")
    # `position` là THỨ TỰ trong danh sách người được gắn thẻ của một bài, và nó nằm trong khoá
    # chính (post_id, position) — xem V8. Bỏ qua cột này thì INSERT đổ ngay vì NOT NULL; cấp cùng
    # một giá trị cho hai người trong cùng bài thì đổ vì trùng khoá chính.
    tagged = [
        f"    ({p['id']}, {pos}, {u})"
        for p in posts
        for pos, u in enumerate(p["tagged"])
    ]
    f.sql(
        "INSERT INTO socialapp.t_post_tags (post_id, position, tagged_user_id) VALUES\n"
        + ",\n".join(tagged) + ";",
        rows=len(tagged),
    )

    f.note("""
usage_count tính lại từ dữ liệu vừa chèn, không gõ tay.
""")
    f.sql("""
UPDATE socialapp.t_hashtags h
   SET usage_count = COALESCE(c.total, 0)
  FROM (SELECT hashtag_id, COUNT(*) AS total
          FROM socialapp.t_post_hashtags GROUP BY hashtag_id) c
 WHERE c.hashtag_id = h.id;""")

    f.note("""
── Quiz gắn vào bài ─────────────────────────────────────────────────────────────────────────
quiz_details KHÔNG phải một PostType riêng: bất kỳ bài nào cũng mang được quiz, và QuizService chỉ
kiểm tra post.quizDetails có khác null hay không. Gắn vào bài REGULAR và ARTICLE cho giống cách
dùng thật.

Mỗi quiz đúng 3 câu. QuizService bắt bài nộp phải có SỐ ĐÁP ÁN BẰNG số câu hỏi, và V84 sinh bài
nộp dựa vào đúng con số đó — thêm câu ở đây mà quên sửa V84 là mọi bài nộp bị từ chối.

UPDATE chứ không thêm cột vào INSERT ở trên: chỉ 8 trong 2.600 bài có quiz, nên nhét thêm một cột
jsonb NULL vào mọi hàng chỉ làm file to ra mà không nói thêm điều gì.
""")
    quiz_rows = []
    for pid, quiz in quiz_posts:
        quiz_rows.append(f"    ({pid}, {jdoc(quiz)})")
    f.sql(
        "UPDATE socialapp.t_posts p SET quiz_details = v.details\n"
        "  FROM (VALUES\n" + ",\n".join(quiz_rows) + "\n"
        "  ) AS v(id, details)\n"
        " WHERE p.id = v.id;",
        rows=len(quiz_rows),
    )

    last_id = max(p["id"] for p in posts)
    f.note("Đẩy sequence quá dải id gán tay.")
    f.sql(f"SELECT setval('socialapp.q_posts_id', {last_id + 1}, FALSE);")
    return f


# ═══ V84 — tương tác ═══════════════════════════════════════════════════════════════════════════

COMMENT_ID_FIRST = 200001

# Bảy loại cảm xúc, và chúng CHỈ hợp lệ trên BÀI VIẾT.
POST_REACTIONS = ["LIKE", "LOVE", "HAHA", "CRY", "ANGRY", "INSIGHT", "CLAP"]

# Bình luận chỉ nhận LIKE — luật B24. CommentReactionService.upsertReaction ném ValidationException
# (400) cho mọi giá trị khác, và V73 (thứ từng migrate dữ liệu cũ về LIKE) nay là no-op vì nó chạy
# TRƯỚC bộ seed này trên bảng rỗng. Không còn lưới an toàn nào phía sau hằng số này.
COMMENT_REACTION = "LIKE"

# natural_comment() ghép opener × body × tail với ba chu kỳ NGUYÊN TỐ CÙNG NHAU (17, 19, 13):
# bộ ba có chu kỳ 17*19*13 = 4.199, nên trên ~9.000 bình luận không chuỗi nào lặp quá 3 lần trong
# toàn bộ ~2.600 bài — đủ để không còn là "seed slop", và không cần dán số "(#N)".
COMMENT_OPENERS = [
    "Cảm ơn bạn, đúng thứ mình đang cần.",
    "Mình gặp y hệt tuần trước, cách này chạy được.",
    "Cho mình hỏi thêm một chút:",
    "Chỗ này mình nghĩ hơi khác:",
    "Đã thử và có kết quả tương tự.",
    "Bài viết rõ ràng, lưu lại đọc kỹ sau.",
    "Có ai đo trên bản mới chưa?",
    "Mình từng làm ngược lại và hối hận.",
    "Điểm thứ hai đáng giá nhất với mình.",
    "Bổ sung một chi tiết nhỏ:",
    "Không đồng ý lắm, nhưng hiểu vì sao bạn chọn vậy.",
    "Đúng cái mình định hỏi hôm qua.",
    "Mình lưu bài này vào thư mục đọc lại.",
    "Đọc tới đoạn cuối mới thấy thấm.",
    "Bên mình vừa gặp ca này tháng trước.",
    "Cho mình xin thêm ngữ cảnh một chút.",
    "Nói thật là mình chưa nghĩ tới hướng đó.",
]
COMMENT_BODIES = [
    "Bên mình cấu hình khác một chút nhưng ý tưởng thì giống hệt.",
    "Cái bẫy là nó chỉ lộ ra khi chạy nhiều luồng cùng lúc.",
    "Bạn có đo lại sau khi bật cache chưa?",
    "Chỗ này tài liệu chính thức viết hơi mơ hồ nên dễ hiểu nhầm.",
    "Mình nghĩ nên tách thành hai bước cho dễ theo dõi.",
    "Đội mình đã bỏ cách cũ sau khi gặp đúng vấn đề này.",
    "Nếu dữ liệu nhỏ thì không thấy khác biệt gì đâu.",
    "Đáng để viết thành một bài riêng đấy bạn.",
    "Cách này có nhược điểm là khó quay lui khi cần.",
    "Mình sẽ thử vào cuối tuần rồi báo lại kết quả.",
    "Điều làm mình phân vân là chi phí vận hành về sau.",
    "Trên máy mình thì kết quả lệch khá nhiều so với bạn.",
    "Có thể do phiên bản thư viện khác nhau chăng.",
    "Mình từng đọc một bài phản biện ý này, để tìm lại gửi bạn.",
    "Phần này production của tụi mình làm gần giống vậy.",
    "Chắc phải benchmark thêm mới dám kết luận.",
    "Cảm giác như vấn đề gốc nằm ở tầng dữ liệu chứ không phải ở đây.",
    "Đúng là hồi đầu mình cũng hiểu sai chỗ này.",
    "Nếu có số liệu trước và sau thì thuyết phục hơn nhiều.",
]
COMMENT_TAILS = [
    "Cảm ơn bạn đã bỏ công viết.",
    "Mình theo dõi bài để hóng thêm ý kiến.",
    "Để mình thử rồi quay lại kể kết quả.",
    "Hy vọng bạn viết tiếp phần sau.",
    "Ai có kinh nghiệm ngược lại thì phản biện giúp nhé.",
    "Cái này nên đưa vào tài liệu nội bộ của đội.",
    "Mình lưu lại rồi, cảm ơn nhiều.",
    "Chi tiết nhỏ nhưng tiết kiệm được nửa ngày.",
    "Đúng lúc mình đang cần, may quá.",
    "Không rõ trên quy mô lớn hơn thì sao nhỉ.",
    "Bạn có repo mẫu nào không cho mình xin.",
    "Đọc xong thấy đỡ hoang mang hẳn.",
    "Chốt lại là đo trước rồi hẵng sửa.",
]
LONG_COMMENT = (
    "Mình vừa đi qua đúng vấn đề này nên chép lại đầy đủ cho ai cần. Ban đầu tụi mình nghĩ nút "
    "thắt nằm ở tầng cơ sở dữ liệu, nên đổ công đi đánh thêm index và viết lại vài câu truy vấn. "
    "Kết quả gần như không nhúc nhích. Sau đó mới bật đo thật ở tầng ứng dụng thì phát hiện phần "
    "lớn thời gian trôi vào việc dựng lại cùng một tập dữ liệu nhiều lần trong một request, chứ "
    "không phải ở chỗ đọc đĩa. Sửa lại thành gom một lần rồi truyền xuống thì thời gian phản hồi "
    "rơi ngay xuống một phần mười, mà không cần thêm bất kỳ index nào. Bài học của mình là đo "
    "trước khi đoán, và đo ở đúng tầng đang nghi ngờ chứ không phải ở tầng dễ đo nhất."
)


def build_engagement(rng, people, posts, quiz_posts, edges, author_weights):
    """Sinh toàn bộ tương tác. Trả về dict các danh sách đã sẵn sàng để in ra SQL."""
    users = [p["id"] for p in people if p["primary_role"]]
    post_ids = [p["id"] for p in posts]
    author_of = {p["id"]: p["author_id"] for p in posts}
    by_kind = {}
    for p in posts:
        by_kind.setdefault(p["kind"], []).append(p["id"])
    username_of = {p["id"]: p["username"] for p in people}

    # ── Bình luận ─────────────────────────────────────────────────────────────────────────────
    comments = []          # (id, post_id, author_id, content, parent_id, age_days)
    next_cid = COMMENT_ID_FIRST

    # Fixture số bình luận GỐC: cần bài có đúng 0, đúng 1, đúng 2 và từ 5 trở lên. Bốn bài dưới
    # đây được cấp số chính xác; phần còn lại rải theo phân phối tự nhiên.
    #
    # V92 chạy SAU file này và kiểm đúng bốn con số ấy — nên đừng đổi cách rải bên dưới mà không
    # xem lại V92, và đừng để phần rải ngẫu nhiên chạm vào bốn bài này.
    regulars = by_kind["REGULAR"]
    fixture_zero = regulars[0]
    fixture_one = regulars[1]
    fixture_two = regulars[2]
    fixture_many = regulars[3]
    reserved = {fixture_zero, fixture_one, fixture_two, fixture_many}

    # Bảy bài đầu dải REGULAR là bài fixture, và cả bảy phải MỞ ĐƯỢC QUA API — một bài FRIENDS hay
    # PENDING_REVIEW trả 404 cho người xem, nên ca kiểm sẽ không bao giờ chạy được trên giao diện
    # trong khi test đọc thẳng SQL vẫn xanh. Điều đó do build_posts() bảo đảm (xem `i > 0` /
    # `i > 6` ở đó); assert dưới đây là chỗ nó gãy nếu ai nới lại các điều kiện ấy.
    state_of = {p["id"]: (p["visibility"], p["moderation"]) for p in posts}
    for pid in regulars[:7]:
        assert state_of[pid] == ("PUBLIC", "APPROVED"), (pid, state_of[pid])

    age_by_cid = {}

    def add_comment(post_id, author, content, parent=None, age=None):
        nonlocal next_cid
        cid = next_cid
        next_cid += 1
        if age is None:
            if parent is not None and parent in age_by_cid:
                # Trả lời BẮT BUỘC mới hơn bình luận nó trả lời — một trả lời "cũ hơn" cha là thứ
                # luồng thật không tạo ra được, và nhìn rất vô lý khi mở luồng ra xem.
                age = rng.randint(1, max(1, age_by_cid[parent] - 1))
            else:
                age = rng.randint(1, 300)
        comments.append((cid, post_id, author, content, parent, age))
        age_by_cid[cid] = age
        return cid

    def other_than(author_id):
        pick = rng.choice(users)
        while pick == author_id:
            pick = rng.choice(users)
        return pick

    def natural_comment(i):
        # opener × body × tail, ba chu kỳ nguyên tố cùng nhau (17 × 19 × 13 = 4.199) — dài hơn số
        # bình luận sinh ra chia cho mức lặp ta chấp nhận, nên không chuỗi nào xuất hiện quá vài
        # lần trên toàn bộ ~2.600 bài. Đủ để không còn là "seed slop"; cấu trúc ba câu là hình
        # dạng bình thường của một bình luận thật.
        no, nb = len(COMMENT_OPENERS), len(COMMENT_BODIES)
        opener = COMMENT_OPENERS[i % no]
        body = COMMENT_BODIES[(i // no) % nb]
        tail = COMMENT_TAILS[(i // (no * nb)) % len(COMMENT_TAILS)]
        return f"{opener} {body} {tail}"

    counter = 0
    add_comment(fixture_one, other_than(author_of[fixture_one]), natural_comment(counter)); counter += 1
    for _ in range(2):
        add_comment(fixture_two, other_than(author_of[fixture_two]), natural_comment(counter)); counter += 1
    for _ in range(6):
        add_comment(fixture_many, other_than(author_of[fixture_many]), natural_comment(counter)); counter += 1

    # Bình luận rất dài — nhánh cắt bớt của thẻ bình luận.
    add_comment(regulars[4], other_than(author_of[regulars[4]]), LONG_COMMENT)

    # Phần còn lại: khoảng 9.000 bình luận, phân phối lệch — vài bài rất sôi nổi, phần lớn im ắng.
    targets = [pid for pid in post_ids if pid not in reserved]
    roots_by_post = {}
    while len(comments) < 9000:
        pid = rng.choice(targets)
        author = other_than(author_of[pid])
        # Một phần tư là trả lời một bình luận gốc đã có của chính bài đó.
        parents = roots_by_post.get(pid, [])
        if parents and rng.random() < 0.25:
            cid = add_comment(pid, author, natural_comment(counter), parent=rng.choice(parents))
        else:
            cid = add_comment(pid, author, natural_comment(counter))
            roots_by_post.setdefault(pid, []).append(cid)
        counter += 1

    # ── Nhắc tên trong bình luận ──────────────────────────────────────────────────────────────
    # Một số bình luận nhắc @username CÓ THẬT — V90 sinh thông báo USER_MENTIONED cho đúng những
    # bình luận này. Và một bình luận mà dấu @ là địa chỉ email: MentionScanner phải BỎ QUA nó,
    # nên nó không được có thông báo nào.
    mention_comments = []
    for cid, pid, author, content, parent, age in rng.sample(comments, 60):
        target = other_than(author)
        mention_comments.append((cid, target))
    email_comment = add_comment(
        regulars[5], other_than(author_of[regulars[5]]),
        "Gửi giúp mình qua ho.tro@elitenexus.test nhé, dấu @ ở đây là email chứ không nhắc ai cả.")

    mention_map = dict(mention_comments)
    rebuilt = []
    for cid, pid, author, content, parent, age in comments:
        if cid in mention_map:
            content = f"@{username_of[mention_map[cid]]} {content}"
        rebuilt.append((cid, pid, author, content, parent, age))
    comments = rebuilt

    # ── Cảm xúc bài viết ──────────────────────────────────────────────────────────────────────
    # Khoá chính (user_id, post_id) nên mỗi người chỉ thả được một cảm xúc lên một bài.
    post_reactions = set()
    reaction_rows = []
    # Một bài cố ý KHÔNG có cảm xúc nào — nhánh "chưa ai thả cảm xúc" của thẻ bài.
    zero_reaction_post = regulars[6]
    for pid in post_ids:
        if pid == zero_reaction_post:
            continue
        # Phân phối lệch: đa số bài vài chục, một số ít bài rất nhiều.
        # Bài của người viết khoẻ được nhiều cảm xúc hơn — người theo dõi nhiều hơn, bài lên
        # bảng tin của nhiều người hơn. Đây là chỗ điểm uy tín trải ra thành nhiều hạng khác nhau.
        base = rng.choice([2, 4, 6, 8, 10, 12, 15, 18, 22, 30, 45, 70])
        n = base + author_weights.get(author_of[pid], 0)
        for who in rng.sample(users, min(n, len(users))):
            if who == author_of[pid] or (who, pid) in post_reactions:
                continue
            post_reactions.add((who, pid))
            kind = rng.choices(POST_REACTIONS, weights=[55, 15, 8, 3, 2, 10, 7])[0]
            reaction_rows.append((who, pid, kind, rng.randint(1, 300)))

    # Tài khoản demo phải có một cảm xúc KHÁC LIKE, và INSIGHT/CLAP phải xuất hiện ở đâu đó.
    demo_post = regulars[7]
    reaction_rows = [r for r in reaction_rows if not (r[0] == DEMO_EXPERT and r[1] == demo_post)]
    post_reactions.add((DEMO_EXPERT, demo_post))
    reaction_rows.append((DEMO_EXPERT, demo_post, "INSIGHT", 5))

    # ── Cảm xúc bình luận: CHỈ LIKE ───────────────────────────────────────────────────────────
    # Sự đa dạng đến từ SỐ LƯỢNG người like, không còn từ loại cảm xúc như bộ seed cũ — hai điều
    # này ràng buộc lẫn nhau: "hai bình luận nổi nhất" cần thứ để xếp hạng, mà loại thì chỉ còn một.
    comment_likes = []
    seen_like = set()
    for cid, pid, author, content, parent, age in comments:
        n = rng.choice([0, 0, 0, 1, 1, 2, 3, 5, 8, 13])
        if not n:
            continue
        for who in rng.sample(users, min(n, len(users))):
            if who == author or (who, cid) in seen_like:
                continue
            seen_like.add((who, cid))
            comment_likes.append((who, cid, rng.randint(1, min(age, 300))))

    # ── RSVP sự kiện ──────────────────────────────────────────────────────────────────────────
    rsvps = []
    seen_rsvp = set()
    for pid in by_kind["EVENT"]:
        for who in rng.sample(users, rng.randint(8, 35)):
            if (pid, who) in seen_rsvp:
                continue
            seen_rsvp.add((pid, who))
            rsvps.append((pid, who, rng.choices(["GOING", "INTERESTED", "NOT_GOING"],
                                                weights=[60, 30, 10])[0], rng.randint(1, 200)))

    # ── Bài nộp quiz ──────────────────────────────────────────────────────────────────────────
    # SỐ ĐÁP ÁN PHẢI BẰNG SỐ CÂU HỎI (QuizService từ chối bài nộp lệch số). Mọi quiz ở V83 đúng 3
    # câu, nên mỗi bài nộp đúng 3 đáp án.
    quiz_answers = []
    seen_quiz = set()
    for pid, quiz in quiz_posts:
        correct = [q["correctOptionIndex"] for q in quiz["questions"]]
        for who in rng.sample(users, rng.randint(90, 130)):
            if who == author_of[pid] or (pid, who) in seen_quiz:
                continue
            seen_quiz.add((pid, who))
            picks = [c if rng.random() < 0.55 else rng.randrange(4) for c in correct]
            score = sum(1 for a, c in zip(picks, correct) if a == c)
            quiz_answers.append((pid, who, picks, score, rng.randint(1, 200)))

    # ── Lịch sử tương tác (nguồn của điểm thân thiết trong bảng tin) ──────────────────────────
    interactions = []
    for who, pid, kind, age in reaction_rows[:9000]:
        interactions.append((who, pid, author_of[pid], "LIKE", age))
    for cid, pid, author, content, parent, age in comments[:3000]:
        interactions.append((author, pid, author_of[pid], "COMMENT", age))

    # ── Câu trả lời được chấp nhận ────────────────────────────────────────────────────────────
    # qna_details.acceptedAnswerId phải trỏ tới một BÌNH LUẬN CÓ THẬT CỦA CHÍNH BÀI ĐÓ. Trỏ sang
    # bình luận của bài khác thì API vẫn trả về bình thường, chỉ là giao diện hiện một câu trả lời
    # không nằm trong luồng — sai mà không có gì báo.
    roots_of = {}
    for cid, pid, author, content, parent, age in comments:
        if parent is None:
            roots_of.setdefault(pid, []).append(cid)
    accepted = []
    for p in posts:
        if p["kind"] != "QNA" or not p["detail"]["isResolved"]:
            continue
        candidates = roots_of.get(p["id"])
        if candidates:
            accepted.append((p["id"], rng.choice(candidates)))

    return {
        "comments": comments, "reactions": reaction_rows, "comment_likes": comment_likes,
        "rsvps": rsvps, "quiz_answers": quiz_answers, "interactions": interactions,
        "accepted": accepted, "email_comment": email_comment,
        "mentions": mention_comments,
        "fixtures": {"zero_comments": fixture_zero, "one_comment": fixture_one,
                     "two_comments": fixture_two, "many_comments": fixture_many,
                     "zero_reactions": zero_reaction_post},
    }


def emit_engagement(eng):
    c = eng["comments"]
    f = SqlFile(84, "seed_engagement",
                f"{len(c):,} bình luận, {len(eng['reactions']):,} cảm xúc bài, "
                f"{len(eng['comment_likes']):,} lượt thích bình luận, RSVP, quiz và lịch sử tương tác.")
    f.note("""
FILE NÀY SINH TỰ ĐỘNG bởi scripts/seed/generate_seed.py — sửa tay sẽ bị ghi đè.

HAI LUẬT CẢM XÚC KHÁC NHAU, KHÔNG ĐƯỢC GỘP:
  · BÀI VIẾT  nhận đủ bảy loại (LIKE, LOVE, HAHA, CRY, ANGRY, INSIGHT, CLAP).
  · BÌNH LUẬN chỉ nhận LIKE. CommentReactionService.upsertReaction ném ValidationException (400)
    cho mọi giá trị khác — luật B24.

V73, thứ từng UPDATE dữ liệu cũ về LIKE, nay là NO-OP: nó chạy trước bộ seed này trên bảng rỗng.
Nghĩa là không còn lưới an toàn nào phía sau; file này phải sinh đúng LIKE ngay từ đầu.

Sự đa dạng của cảm xúc bình luận vì vậy đến từ SỐ LƯỢNG người thích, không còn từ loại — hai điều
này ràng buộc lẫn nhau, vì "hai bình luận nổi nhất" vẫn cần thứ gì đó để xếp hạng.
""")
    f.rule()

    f.note("""
── Bình luận (200001+) ──────────────────────────────────────────────────────────────────────
Id gán tường minh vì V90 phải trỏ thông báo tới từng bình luận cụ thể, và V92 kiểm số bình luận
gốc của bốn bài fixture.

Một phần tư là trả lời, và mọi trả lời đều trỏ về một bình luận GỐC CỦA CHÍNH BÀI ĐÓ — parent_id
trỏ sang bài khác thì luồng vẫn dựng được cây nhưng cây đó vô nghĩa.
""")
    rows = [
        f"    ({cid}, {pid}, {author}, {q(content)}, "
        f"{parent if parent else 'NULL'}, "
        f"now() - INTERVAL '{age} days', now() - INTERVAL '{age} days')"
        for cid, pid, author, content, parent, age in c
    ]
    f.sql(
        "INSERT INTO socialapp.t_comments\n"
        "    (id, post_id, author_id, content, parent_id, created_at, updated_at) VALUES\n"
        + ",\n".join(rows) + ";",
        rows=len(rows),
    )

    f.note("── Cảm xúc bài viết — đủ bảy loại " + "─" * 60)
    rows = [
        f"    ({who}, {pid}, {q(kind)}, now() - INTERVAL '{age} days')"
        for who, pid, kind, age in eng["reactions"]
    ]
    f.sql(
        "INSERT INTO socialapp.t_post_reactions (user_id, post_id, reaction_type, created_at)"
        " VALUES\n" + ",\n".join(rows) + ";",
        rows=len(rows),
    )

    f.note(f"""
── Cảm xúc bình luận — CHỈ LIKE ─────────────────────────────────────────────────────────────
Giá trị {COMMENT_REACTION!r} ghi cứng ở một chỗ duy nhất trong generator. Một hàng mang giá trị
khác là dữ liệu mà API không bao giờ tạo ra được, và là thứ SeedMigrationTest canh.
""")
    rows = [
        f"    ({who}, {cid}, '{COMMENT_REACTION}', now() - INTERVAL '{age} days')"
        for who, cid, age in eng["comment_likes"]
    ]
    f.sql(
        "INSERT INTO socialapp.t_comment_reactions (user_id, comment_id, reaction_type, created_at)"
        " VALUES\n" + ",\n".join(rows) + ";",
        rows=len(rows),
    )

    f.note("── RSVP sự kiện " + "─" * 76)
    rows = [
        f"    ({pid}, {who}, {q(status)}, now() - INTERVAL '{age} days')"
        for pid, who, status, age in eng["rsvps"]
    ]
    f.sql(
        "INSERT INTO socialapp.t_event_rsvps (post_id, user_id, status, created_at) VALUES\n"
        + ",\n".join(rows) + ";",
        rows=len(rows),
    )

    f.note("""
── Bài nộp quiz ─────────────────────────────────────────────────────────────────────────────
SỐ ĐÁP ÁN BẰNG SỐ CÂU HỎI. QuizService từ chối bài nộp lệch số, nên nếu ai đó thêm câu hỏi vào
quiz ở V83 mà quên file này thì mọi bài nộp ở đây trở thành dữ liệu API không tạo ra được.
""")
    rows = [
        f"    ({pid}, {who}, {jdoc(picks)}, {score}, now() - INTERVAL '{age} days')"
        for pid, who, picks, score, age in eng["quiz_answers"]
    ]
    f.sql(
        "INSERT INTO socialapp.t_quiz_answers (post_id, user_id, answers, score, created_at)"
        " VALUES\n" + ",\n".join(rows) + ";",
        rows=len(rows),
    )

    f.note("""
── Lịch sử tương tác ────────────────────────────────────────────────────────────────────────
Nguồn của điểm thân thiết mà bảng tin dùng để xếp hạng. type chỉ có LIKE và COMMENT — đó là toàn
bộ enum InteractionType.
""")
    rows = [
        f"    ({who}, {pid}, {author}, {q(kind)}, now() - INTERVAL '{age} days')"
        for who, pid, author, kind, age in eng["interactions"]
    ]
    f.sql(
        "INSERT INTO socialapp.t_user_interactions (user_id, post_id, author_id, type, created_at)"
        " VALUES\n" + ",\n".join(rows) + ";",
        rows=len(rows),
    )

    f.note("""
── Câu trả lời được chấp nhận ───────────────────────────────────────────────────────────────
acceptedAnswerId phải trỏ tới một bình luận CÓ THẬT CỦA CHÍNH BÀI ĐÓ. Trỏ sang bình luận của bài
khác thì API vẫn trả về bình thường, chỉ là giao diện hiện một câu trả lời không nằm trong luồng —
sai mà không có gì báo. Vì vậy phép gán nằm ở đây, sau khi bình luận đã tồn tại, chứ không ở V83.
""")
    rows = [f"    ({pid}, {cid})" for pid, cid in eng["accepted"]]
    f.sql(
        "UPDATE socialapp.t_posts p\n"
        "   SET qna_details = jsonb_set(p.qna_details, '{acceptedAnswerId}', to_jsonb(v.cid))\n"
        "  FROM (VALUES\n" + ",\n".join(rows) + "\n"
        "  ) AS v(pid, cid)\n"
        " WHERE p.id = v.pid;",
        rows=len(rows),
    )

    f.note("Đẩy các sequence quá dải id gán tay, và quá dữ liệu vừa chèn.")
    last_cid = max(cid for cid, *_ in c)
    f.sql(f"""
SELECT setval('socialapp.q_comments_id', {last_cid + 1}, FALSE);
SELECT setval('socialapp.q_event_rsvps_id',
              GREATEST((SELECT COALESCE(MAX(id), 0) FROM socialapp.t_event_rsvps), 1), true);
SELECT setval('socialapp.q_user_interactions_id',
              GREATEST((SELECT COALESCE(MAX(id), 0) FROM socialapp.t_user_interactions), 1), true);""")
    return f


# ═══ V85 — gian sách ═══════════════════════════════════════════════════════════════════════════

BOOK_ID_FIRST = 3001

# Từ khoá trong tiêu đề → chủ đề. Bám NỘI DUNG THẬT của quyển sách chứ không rải cho đủ chín tab:
# một cơ sở dữ liệu nói dối để màn hình demo cân đối hơn là đánh đổi sai.
CATEGORY_HINTS = [
    ("SECURITY", ("hack", "penetration", "security", "crypt", "kali", "ethical")),
    ("DEVOPS", ("devops", "kubernetes", "docker", "helm", "terraform", "ansible", "linux", "unix",
                "site reliability", "continuous", "observability", "aws", "azure", "cloud", "git")),
    ("DATA_ML", ("machine learning", "deep learning", "data science", "hadoop", "spark", "kafka",
                 "analytics", "mlops", "llm", "neural")),
    ("FRONTEND", ("javascript", "typescript", "react", "angular", "vue", "css", "frontend", "web ",
                  "html")),
    ("MOBILE", ("android", "ios", "kotlin", "swift", "mobile", "react native")),
    ("QA", ("test", "tdd", "rspec", "quality")),
    ("CAREER", ("interview", "team", "agile", "scrum", "lean", "leadership", "retrospect",
                "mythical", "startup", "founders")),
    ("BACKEND", ("java", "python", "go ", "golang", "rust", "spring", "api", "microservice",
                 "database", "sql", "postgres", "redis", "mongo", "distributed", "architecture",
                 "algorithm", "refactor", "clean", "pragmatic", "design pattern", "programming",
                 "software", "system")),
]

# Một blurb THẬT cho mỗi quyển, cùng thứ tự với CATALOG ở book_catalog.py — BOOK_BLURBS[i] ứng với
# CATALOG[i], không còn là 6 câu mẫu lặp vòng như bản cũ. Sinh ngày 2026-09-01 bằng cách tra mô tả
# thật của từng sách qua Open Library (isbn → works.description, và toàn bộ ấn bản khác của cùng
# work khi ấn bản khớp ISBN không có description), rồi paraphrase lại — KHÔNG dịch nguyên văn. Với
# sách không có mô tả công khai tìm được (khoảng một phần tư danh mục, chủ yếu sách tự xuất bản hoặc
# nằm sau paywall của nhà xuất bản), blurb viết từ các sự kiện có thể kiểm chứng khác về quyển sách
# đó — chủ đề, vai trò của tác giả, nhà xuất bản, định dạng — chứ không bịa nội dung/cốt truyện.
BOOK_BLURBS = [
    "Trình bày các nguyên tắc và thói quen viết mã dễ đọc, dễ bảo trì, kèm loạt bài tập làm sạch dần một đoạn mã lộn xộn thành mã mạch lạc.",  # 0 Clean Code
    "Sách nền tảng để viết Go rõ ràng và đúng thói quen ngôn ngữ, dẫn dắt từ cú pháp cơ bản tới các chủ đề nâng cao như reflection và liên kết với thư viện C.",  # 1 The Go Programming Language
    "Bàn về cách tổ chức kiến trúc để tách biệt quy tắc nghiệp vụ cốt lõi khỏi các chi tiết kỹ thuật dễ đổi như framework hay cơ sở dữ liệu.",  # 2 Clean Architecture
    "Tập hợp các khuyến nghị thực dụng, mỗi mục là một bài học ngắn về cách dùng đúng các đặc tính của nền tảng Java, từ collection tới lambda và stream.",  # 3 Effective Java
    "Giới thiệu danh mục các kỹ thuật tái cấu trúc mã cùng những dấu hiệu code smell cần nhận ra, nhấn mạnh vai trò của bộ test tự động khi thay đổi thiết kế.",  # 4 Refactoring
    "Tổng hợp các thói quen thực dụng của một lập trình viên chuyên nghiệp, từ quản lý độ phức tạp, giữ các thành phần độc lập, đến tự động hoá kiểm thử.",  # 5 The Pragmatic Programmer
    "Bộ catalog kinh điển gồm 23 mẫu thiết kế hướng đối tượng, chia theo nhóm khởi tạo, cấu trúc và hành vi, đề cao việc ưu tiên compose hơn kế thừa.",  # 6 Design Patterns
    "Rút từ kinh nghiệm quản lý dự án hệ điều hành OS/360 của IBM, nổi tiếng với luận điểm thêm người vào một dự án đang trễ tiến độ thường khiến nó trễ hơn.",  # 7 The Mythical Man-Month
    "Giáo trình thuật toán được dùng rộng rãi nhất trong các trường đại học, trình bày chặt chẽ từ cấu trúc dữ liệu cơ bản tới các thuật toán đồ thị và tối ưu phức tạp.",  # 8 Introduction to Algorithms
    "Đi từ nền tảng toán học của học sâu — đại số tuyến tính, xác suất — tới các kiến trúc mạng nơ-ron dùng trong công nghiệp như mạng tích chập và mô hình chuỗi.",  # 9 Deep Learning
    "Đề xuất vòng lặp Xây dựng - Đo lường - Học hỏi để khởi nghiệp thử nghiệm nhanh giả định thay vì đặt cược lớn vào một kế hoạch chưa kiểm chứng.",  # 10 The Lean Startup
    "Đề xuất xây dựng một ngôn ngữ chung giữa chuyên gia nghiệp vụ và lập trình viên, rồi liên tục tinh chỉnh mô hình miền song song với việc tái cấu trúc mã.",  # 11 Domain-Driven Design
    "Trình bày phương pháp Extreme Programming cho các đội nhỏ: lập trình cặp đôi, viết test trước, sở hữu mã chung và lập kế hoạch theo tuần để thích nghi với yêu cầu hay đổi.",  # 12 Extreme programming explained
    "Dạy Ruby qua 52 bài tập buộc người học tự gõ lại từng đoạn mã, tự sửa lỗi và quan sát chương trình chạy, dành cho người chưa từng lập trình.",  # 13 Learn Ruby the Hard Way
    "Đúc kết nguyên tắc thiết kế điều hướng web sao cho trực quan tới mức người dùng không phải dừng lại suy nghĩ, kèm hướng dẫn tự tổ chức kiểm thử khả dụng.",  # 14 Don't Make Me Think, Revisited: A Common Sense Approach to Web Usability
    "Giải thích các mẫu thiết kế hướng đối tượng qua ví dụ đời thường và hình minh hoạ, phù hợp cho người mới tiếp cận trước khi đọc bộ catalog gốc của Gang of Four.",  # 15 Head First design patterns
    "Lọc ra tập con đáng tin cậy và dễ đọc của JavaScript, cho rằng phần lớn rắc rối của ngôn ngữ đến từ việc nó được phát triển và phát hành quá vội.",  # 16 JavaScript: The Good Parts
    "Giải thích cơ chế hoạt động bên trong của các bộ máy biểu thức chính quy khác nhau, giúp viết được regex vừa đúng vừa hiệu quả thay vì chỉ đúng cú pháp.",  # 17 Mastering Regular Expressions
    "Tài liệu tham khảo toàn diện về ngôn ngữ JavaScript lẫn các API phía trình duyệt, được xem là cuốn sách gối đầu giường của giới lập trình JavaScript từ năm 1996.",  # 18 JavaScript
    "Tổng hợp kỹ thuật xây dựng phần mềm ở mức chi tiết — đặt tên biến, cấu trúc điều khiển, gỡ lỗi — kèm danh sách kiểm tra thực dụng cho từng giai đoạn viết mã.",  # 19 Code complete
    "Nhìn hệ thống học máy như một quy trình lặp toàn diện, từ chọn đặc trưng, huấn luyện lại mô hình đến giám sát và phát hiện lệch dữ liệu khi đã lên production.",  # 20 Designing Machine Learning Systems
    "Cập nhật các mẫu thiết kế cổ điển sang bối cảnh JavaScript và React hiện đại, thêm cả các mẫu về hiệu năng như chia nhỏ mã và render phía máy chủ.",  # 21 Learning JavaScript Design Pattern
    "So sánh có hệ thống các lựa chọn lưu trữ và xử lý dữ liệu — từ cơ sở dữ liệu quan hệ, NoSQL đến hệ xử lý luồng — dưới góc nhìn đánh đổi giữa nhất quán, độ tin cậy và khả năng mở rộng.",  # 22 Designing Data-Intensive Applications: The Big Ideas Behind Reliable, Scalable, and Maintainable Systems
    "Hướng dẫn dựng và vận hành cụm Hadoop, từ hệ tệp phân tán HDFS, mô hình MapReduce đến các dự án vệ tinh như Parquet hay Spark trong hệ sinh thái.",  # 23 Hadoop
    "Khuyến khích viết Python theo đúng phong cách ngôn ngữ thay vì bê nguyên thói quen từ ngôn ngữ khác, đi từ cấu trúc dữ liệu tới lập trình bất đồng bộ và metaprogramming.",  # 24 Fluent Python
    "Đặt khả năng triển khai độc lập của từng service làm mục tiêu trung tâm, bàn cách tách dần một hệ thống nguyên khối theo ranh giới nghiệp vụ thay vì theo tầng kỹ thuật.",  # 25 Building Microservices: Designing Fine-Grained Systems
    "Giới thiệu nhanh các tính năng nâng cao của PostgreSQL cho người quen hệ quản trị khác, từ quản trị vai trò, sao lưu đến viết hàm và tinh chỉnh truy vấn.",  # 26 PostgreSQL: Up and Running: A Practical Guide to the Advanced Open Source Database
    "Đúc kết các mẫu lặp lại được cho hệ phân tán — sidecar, adapter, ambassador — như một ngôn ngữ chung để kỹ sư mô tả và tái sử dụng thiết kế thay vì làm lại từ đầu.",  # 27 Designing Distributed Systems: Patterns and Paradigms for Scalable, Reliable Services
    "Dạy học máy bằng ví dụ cụ thể và hai bộ công cụ Scikit-Learn cùng TensorFlow, đi từ hồi quy tuyến tính đơn giản tới mạng nơ-ron sâu và học tăng cường.",  # 28 Hands-On Machine Learning with Scikit-Learn, Keras, and TensorFlow
    "Hướng dẫn dùng Kafka để xử lý luồng dữ liệu thời gian thực, từ viết producer/consumer, đảm bảo giao dữ liệu tin cậy đến vận hành và giám sát cụm trong production.",  # 29 Kafka
    "Nhấn mạnh việc học đúng thói quen ngôn ngữ Go thay vì chỉ học cú pháp, tránh mang khuôn mẫu từ ngôn ngữ khác vào và làm sai lệch tinh thần thiết kế của Go.",  # 30 Learning Go: An Idiomatic Approach to Real-World Go Programming
    "Đúc kết kinh nghiệm giữ một codebase khổng lồ bền vững qua thời gian tại Google, xoay quanh cách thời gian và quy mô ảnh hưởng tới lựa chọn thực hành kỹ thuật.",  # 31 Software Engineering at Google
    "Dẫn dắt người đọc từ cú pháp JavaScript cơ bản tới việc tự xây các chương trình hoàn chỉnh như game nền tảng hay ngôn ngữ lập trình mini, áp dụng cả trên trình duyệt lẫn Node.js.",  # 32 Eloquent JavaScript
    "Dạy Python cho người chưa từng lập trình bằng cách tự động hoá việc vặt hằng ngày — dọn file, đọc bảng tính, gửi email nhắc việc — thay vì các bài toán thuật toán trừu tượng.",  # 33 Automate the Boring Stuff with Python
    "Hướng dẫn triển khai tìm kiếm với Apache Solr, từ tìm theo từ khoá cơ bản tới lọc theo facet, gợi ý truy vấn và mở rộng hệ thống cho hàng tỉ tài liệu.",  # 34 Solr in Action
    "Giải thích thuật toán tìm kiếm, sắp xếp và đồ thị bằng hình vẽ trực quan thay vì chứng minh toán học dài dòng, kèm ví dụ mã Python ngắn gọn.",  # 35 Grokking Algorithms
    "Bắt đầu từ khái niệm container cơ bản rồi dẫn vào cách triển khai ứng dụng phân tán trên Kubernetes, cập nhật phiên bản mới mà không gây gián đoạn dịch vụ.",  # 36 Kubernetes in Action
    "Dựng từng bước một ứng dụng web có kết nối cơ sở dữ liệu bằng Spring, bao quát từ REST, bảo mật tới lập trình reactive và tích hợp container.",  # 37 Spring in Action
    "Tài liệu chính thức của cộng đồng Rust, giải thích các khái niệm cốt lõi như ownership, xử lý lỗi và concurrency an toàn thông qua các dự án nhỏ đi kèm mỗi chương.",  # 38 Rust Programming Language, 2nd Edition
    "Hệ thống hoá các mẫu và 'mùi' thường gặp khi viết test tự động bằng các framework họ xUnit, kèm hướng dẫn tái cấu trúc để test dễ hiểu và bền vững hơn theo thời gian.",  # 39 xUnit Test Patterns
    "Dẫn người mới bắt đầu với Rust ở phía backend bằng cách dựng từ đầu một API gửi bản tin qua email, đi kèm kiểm thử, xử lý lỗi và một pipeline CI/CD hoàn chỉnh.",  # 40 Zero To Production In Rust
    "Giáo trình nhập môn lập trình hướng đối tượng, dùng môi trường BlueJ để minh hoạ khái niệm Java ngay từ những bài học đầu tiên.",  # 41 Objects first with Java
    "Do một trong những người đồng sáng lập dự án Kubernetes viết cùng đồng nghiệp ở Google, sách gom lại các thực hành vận hành cụm Kubernetes an toàn và ổn định trong môi trường sản xuất.",  # 42 Kubernetes Best Practices
    "Hướng dẫn xây dựng dịch vụ giao tiếp qua gRPC trên nhiều ngôn ngữ lập trình, từ thiết kế API bằng Protocol Buffers tới triển khai microservice thực tế.",  # 43 GRPC
    "Ấn bản mới bổ sung các chương kiểm thử đơn vị để tăng độ tin cậy, thêm một dự án api-gateway và dùng Lombok để giảm code lặp, cùng phiên bản Spring Boot, Java và Kubernetes được cập nhật mới nhất.",  # 44 Back-end Java
    "Trình bày Helm — trình quản lý gói chuẩn của hệ sinh thái Kubernetes — từ cách cài đặt ứng dụng đóng gói sẵn tới tự viết chart riêng và quản lý vòng đời triển khai.",  # 45 Learning Helm
    "Dẫn dắt người đọc từ việc dựng cụm Kubernetes đầu tiên tới triển khai container, mở rộng quy mô và cập nhật ứng dụng mà không gây gián đoạn dịch vụ.",  # 46 Kubernetes in Action, Second Edition
    "Trình bày các mẫu thiết kế (design pattern) áp dụng cho React nhằm xây dựng ứng dụng web theo module, dễ bảo trì và hiệu năng tốt.",  # 47 React17 design patterns and best practices
    "Sách hướng dẫn xây dựng ứng dụng di động đa nền tảng bằng React Native theo hướng làm dự án thực tế, do đội ngũ tác giả từng phát triển ứng dụng production viết.",  # 48 Fullstack React Native
    "Tổng hợp hàng chục quy tắc thực hành cụ thể để dùng TypeScript hiệu quả hơn, viết theo phong cách liệt kê từng mục ngắn gọn quen thuộc của dòng sách 'Effective'.",  # 49 Effective TypeScript
    "Hướng dẫn dùng TypeScript để phát triển ứng dụng JavaScript quy mô lớn, từ hệ thống kiểu tới cách tổ chức mã nguồn cho dự án dài hạn.",  # 50 Pro TypeScript: Application-Scale JavaScript Development
    "Một cuốn trong loạt sách hướng dẫn từng bước dùng Android Studio, phiên bản này tập trung vào phát triển ứng dụng Android bằng Kotlin.",  # 51 Android Studio 3.4 Development Essentials - Kotlin Edition
    "Giáo trình lập trình Kotlin theo hướng thực hành, dẫn người học từ cú pháp cơ bản tới xây dựng ứng dụng hoàn chỉnh.",  # 52 Kotlin Programming
    "Hướng dẫn phát triển ứng dụng Android hiện đại bằng Kotlin, bao quát từ kiến trúc ứng dụng tới các API nền tảng mới.",  # 53 Pro Android with Kotlin
    "Sách dạng cookbook gồm nhiều công thức lập trình iOS bằng Swift, mỗi công thức giải quyết một tác vụ cụ thể trong phát triển ứng dụng.",  # 54 IOS 9 Swift Programming Cookbook
    "Giáo trình nhập môn kiểm thử xâm nhập theo hướng thực hành, dẫn người đọc dựng phòng lab riêng rồi tự tay khai thác các lỗ hổng phổ biến.",  # 55 Penetration Testing
    "Xây nền tảng Linux cho người mới học bảo mật, kèm theo MySQL, PostgreSQL và viết script Python qua các bài tập thực hành.",  # 56 Linux Basics for Hackers
    "Sách nhập môn dùng Kali Linux cho kiểm thử xâm nhập, giới thiệu các công cụ và quy trình cơ bản của một bài kiểm thử bảo mật.",  # 57 Hacking with Kali Linux
    "Giáo trình bao quát toàn bộ quy trình kiểm thử xâm nhập có đạo đức, từ trinh sát, quét lỗ hổng tới khai thác và viết báo cáo.",  # 58 Ethical Hacking and Penetration Testing Guide
    "Sách nhập môn dùng các công cụ có sẵn trong Kali Linux để kiểm tra bảo mật cơ bản cho hệ thống của chính mình.",  # 59 Basic security testing with Kali Linux
    "Giải thích chín thuật toán nền tảng đứng sau các công nghệ quen thuộc — từ xếp hạng kết quả tìm kiếm, mã hoá khoá công khai đến nén dữ liệu — cho người đọc không cần nền tảng toán chuyên sâu.",  # 60 Nine algorithms that changed the future
    "Đội SRE của Google chia sẻ cách họ vận hành các hệ thống phần mềm quy mô lớn qua toàn bộ vòng đời, từ xây dựng, triển khai tới giám sát và bảo trì.",  # 61 Site Reliability Engineering
    "Tổng hợp các nguyên tắc và thực hành kỹ thuật độ tin cậy trong vận hành hệ thống ở quy mô lớn.",  # 62 Site Reliability Engineering  Handbook
    "Hướng dẫn đầy đủ về Git từ cơ bản tới nâng cao, tập trung vào quy trình làm việc phân tán và cách tuỳ biến Git cho nhu cầu riêng của từng đội.",  # 63 Pro Git
    "Bộ bài tập cấu trúc dữ liệu và giải thuật kèm lời giải bằng Java, quen thuộc với nhiều người ôn phỏng vấn lập trình ở Ấn Độ.",  # 64 Data Structures and Algorithms Made Easy in Java
    "Đúc kết hơn hai mươi năm nghiên cứu của Altshuller về TRIZ, sách trình bày ARIZ — thuật toán giải quyết vấn đề sáng chế có hệ thống — qua các ví dụ thực tế, được nhiều người xem là tác phẩm quan trọng nhất của ông.",  # 65 The Innovation Algorithm
    "Hơn bốn mươi kiến trúc sư phần mềm góp mỗi người một bài học ngắn, từ cách giao tiếp với các bên liên quan tới cách đơn giản hoá hệ thống phức tạp.",  # 66 97 things every software architect should know
    "Người đồng sáng tạo ra Scrum trình bày lại phương pháp này và cách nó giúp các đội rút ngắn đáng kể thời gian hoàn thành công việc.",  # 67 Scrum
    "Đề xuất cách tổ chức retrospective đều đặn trong suốt dự án thay vì chỉ một lần lúc kết thúc, giúp đội phát hiện sớm vấn đề về cả kỹ thuật lẫn con người.",  # 68 Agile retrospectives
    "Dẫn người đọc từ những lệnh terminal cơ bản tới viết script Bash hoàn chỉnh, qua các chủ đề như xử lý văn bản, quản trị hệ thống và duyệt tệp.",  # 69 The Linux Command Line
    "Cẩm nang quản trị hệ thống Unix/Linux được xem là tài liệu tham khảo chuẩn, bao quát từ cấu hình mạng tới vận hành hạ tầng internet quy mô lớn.",  # 70 UNIX and Linux System Administration Handbook (5th Edition)
    "Hướng dẫn doanh nghiệp nhỏ tiếp cận phần mềm mã nguồn mở miễn phí bản quyền, từ cài đặt, cấu hình tới cách khai thác cộng đồng đứng sau các phần mềm đó.",  # 71 Pro Linux system administration
    "Giới thiệu cách cấu hình, biên dịch và tinh chỉnh nhân Linux, viết bởi một trong những người bảo trì lâu năm của dự án kernel.",  # 72 Linux Kernel in a Nutshell
    "Hướng dẫn dùng Python để tự động hoá các tác vụ quản trị hệ thống Unix và Linux, từ xử lý tệp, mạng đến quản lý tiến trình.",  # 73 Python for Unix and Linux System Administration
    "Tài liệu tham khảo toàn diện về quản trị hệ thống Linux, bao quát từ cài đặt, cấu hình mạng tới quản lý người dùng và dịch vụ.",  # 74 Special edition using Linux system administration
    "Tập trung vào các kỹ thuật bảo mật và làm cứng (hardening) cho hệ thống Linux, từ quản lý quyền tới giám sát xâm nhập.",  # 75 Linux system security
    "Cẩm nang quản trị Linux được giới chuyên môn đánh giá là tài liệu tham khảo hàng đầu, bao quát lưu trữ, mạng, hosting web và quản lý cấu hình.",  # 76 Linux administration handbook
    "Giáo trình UNIX/Linux dùng cho cả người mới lẫn lập trình viên, với gần một nghìn bài tập và câu hỏi tự kiểm để củng cố kiến thức.",  # 77 Your UNIX/LINUX
    "Tài liệu tham khảo đầy đủ để lên kế hoạch, cài đặt, cấu hình, bảo trì và khai thác Red Hat Linux ở mức tối đa.",  # 78 Red Hat Linux 8 unleashed
    "Dẫn người đọc từ những bước đầu tới khả năng vận hành PostgreSQL ở mức chuyên nghiệp, bao quát thiết kế lược đồ, truy vấn và quản trị.",  # 79 Beginning databases with PostgreSQL
]


def categorise_book(title):
    low = " " + title.lower() + " "
    for category, keys in CATEGORY_HINTS:
        if any(k in low for k in keys):
            return category
    return "OTHER"


def build_books(rng, people, posts):
    """80 quyển sách thật, gắn với bài BOOK và với người đăng."""
    from book_catalog import CATALOG

    book_post_ids = [p["id"] for p in posts if p["kind"] == "BOOK"]
    authors = [p["id"] for p in people if p["primary_role"]]

    books = []
    for i, (isbn, title, writer) in enumerate(CATALOG):
        bid = BOOK_ID_FIRST + i
        uploader = authors[(i * 7) % len(authors)]
        category = categorise_book(title)
        is_free = i % 5 == 0
        price = 0 if is_free else rng.choice([49000, 79000, 99000, 129000, 149000, 199000, 249000])

        # Ba key MinIO. Chúng là KEY TRẦN, không phải URL: BookStorageService tự ký URL tạm thời
        # khi phục vụ, nên nhét ${minioUrl} vào đây là sai kiểu dữ liệu chứ không chỉ thừa.
        fmt = "PDF" if i % 3 else "EPUB"
        ext = fmt.lower()
        file_key = f"books/{uploader}/{isbn}.{ext}"
        preview_key = f"previews/{uploader}/{isbn}-preview.{ext}"
        cover_key = f"covers/{uploader}/{isbn}.jpg"
        want_object(file_key, "book-file", None)
        want_object(preview_key, "book-preview", None)
        want_object(cover_key, "book-cover",
                    f"https://covers.openlibrary.org/b/isbn/{isbn}-L.jpg")

        books.append({
            "id": bid, "isbn": isbn, "title": title, "writer": writer,
            "author_id": uploader,
            "post_id": book_post_ids[i] if i < len(book_post_ids) else None,
            "category": category,
            "description": f"{title} — {writer}. {BOOK_BLURBS[i]}",
            "file_key": file_key, "preview_key": preview_key, "cover_key": cover_key,
            "format": fmt,
            "size": rng.randint(400_000, 9_000_000),
            "pages": rng.randint(120, 780),
            "preview_pages": rng.choice([5, 8, 10, 12, 15]),
            "price": price, "is_free": is_free,
            "age_days": rng.randint(20, 500),
        })

    # Một quyển trỏ tới object CỐ Ý KHÔNG TỒN TẠI — nhánh "kho lưu trữ hỏng" (503) mà frontend đã
    # dịch sẵn thông điệp nhưng chưa bao giờ chạy thật. Key mang chuỗi `khong-ton-tai`, thứ mà
    # docker/minio/generate-seed-objects.py loại ra bằng EXCLUDE.
    broken = books[21]
    broken["file_key"] = f"books/{broken['author_id']}/khong-ton-tai.pdf"

    return books


def build_bookstore(rng, people, books):
    users = [p["id"] for p in people if p["primary_role"]]

    reviews, seen_review = [], set()
    for b in books:
        for who in rng.sample(users, rng.randint(6, 32)):
            if who == b["author_id"] or (b["id"], who) in seen_review:
                continue
            seen_review.add((b["id"], who))
            # Lệch về phía tích cực như đánh giá thật, nhưng vẫn có 1-2 sao để bộ lọc theo sao có
            # cả hai đầu.
            rating = rng.choices([1, 2, 3, 4, 5], weights=[3, 5, 14, 38, 40])[0]
            reviews.append((b["id"], who, rating, rng.randint(1, b["age_days"])))

    purchases, seen_buy = [], set()
    for b in books:
        if b["is_free"]:
            continue
        for who in rng.sample(users, rng.randint(4, 24)):
            if who == b["author_id"] or (b["id"], who) in seen_buy:
                continue
            seen_buy.add((b["id"], who))
            # Hai modulo NGUYÊN TỐ CÙNG NHAU cho điều kiện lọc và cho việc chọn giá trị. Dùng chung
            # một modulo đã làm mất hẳn hai trạng thái thanh toán ở V55 của bộ seed cũ.
            status = ["COMPLETED", "COMPLETED", "COMPLETED", "PENDING", "FAILED",
                      "REFUNDED"][(who * 7 + b["id"] * 11) % 6]
            # PENDING phải LÙI QUÁ 15 PHÚT (PENDING_PAYMENT_STALE_MINUTES). Một hàng PENDING mới
            # tinh sẽ CHẶN người mua bấm mua lại đúng quyển đó — đúng thứ không được xảy ra với
            # quyển nào trong kịch bản demo.
            age = rng.randint(2, 300)
            purchases.append((b["id"], who, b["price"], status, age))

    # ĐÚNG MỘT hàng PENDING mới tinh, để chạy được nhánh từ chối mua lại. Đặt ở quyển gần cuối dải,
    # cố ý KHÔNG phải quyển nào trong kịch bản demo.
    demo_safe = books[-3]
    buyer = next(u for u in users if u != demo_safe["author_id"]
                 and (demo_safe["id"], u) not in seen_buy)
    seen_buy.add((demo_safe["id"], buyer))
    purchases.append((demo_safe["id"], buyer, demo_safe["price"] or 99000, "PENDING_FRESH", 0))

    return reviews, purchases


def emit_bookstore(books, reviews, purchases):
    f = SqlFile(85, "seed_bookstore",
                f"{len(books)} đầu sách CÓ THẬT, {len(reviews):,} đánh giá, {len(purchases):,} giao dịch.")
    f.note("""
FILE NÀY SINH TỰ ĐỘNG bởi scripts/seed/generate_seed.py — sửa tay sẽ bị ghi đè.
Danh mục sách nằm ở scripts/seed/book_catalog.py, nơi ghi rõ từng ISBN đã được xác minh thế nào.

SÁCH LÀ SÁCH THẬT, BÌA LÀ BÌA THẬT. Mỗi ISBN đã được tra qua API của Open Library để lấy đúng tiêu
đề và tác giả mà họ trả về, rồi tải thử ảnh bìa với tham số default=false — thiếu tham số đó thì
Open Library trả HTTP 200 kèm ảnh placeholder 1x1 và mọi ISBN sai đều trông như thành công.
Nội dung file (PDF/EPUB) vẫn là file sinh tối thiểu: không thể phân phối nội dung có bản quyền.

author_id là NGƯỜI ĐĂNG quyển sách lên gian sách, không phải tác giả của sách. Tên tác giả thật nằm
trong mô tả.

CÁC CỘT *_key TRỎ VÀO OBJECT TRONG MINIO MÀ SQL KHÔNG TẠO ĐƯỢC, và chúng là KEY TRẦN chứ không phải
URL: BookStorageService tự ký URL tạm thời khi phục vụ, nên đặt ${minioUrl} vào đây là sai kiểu dữ
liệu. `docker compose up` nạp object lên đúng những key này.

Quy ước key lấy đúng theo BookStorageService:
  nội dung sách   bucket `books`        key `books/<authorId>/<tên>.<pdf|epub>`
  bản xem thử     bucket `books`        key `previews/<authorId>/<tên>`
  ảnh bìa         bucket `book-covers`  key `covers/<authorId>/<tên>`
""")
    f.rule()

    f.note("""
── Sách ─────────────────────────────────────────────────────────────────────────────────────
avg_rating và review_count được TÍNH LẠI từ t_book_reviews ở cuối file, không gõ tay.

MỘT quyển có file_key trỏ tới object cố ý không tồn tại (`khong-ton-tai`): nhánh "kho lưu trữ
hỏng" trả 503 mà frontend đã dịch sẵn thông điệp nhưng chưa bao giờ chạy thật.
""")
    rows = []
    for b in books:
        rows.append(
            f"    ({b['id']}, {b['author_id']}, {b['post_id'] if b['post_id'] else 'NULL'}, "
            f"{q(b['title'])}, {q(b['description'])}, {q(b['file_key'])}, {q(b['cover_key'])}, "
            f"{q(b['preview_key'])}, {q(b['format'])}, {b['size']}, {b['pages']}, "
            f"{b['preview_pages']}, {b['price']}, 'VND', "
            f"{'TRUE' if b['is_free'] else 'FALSE'}, 0, 0.0, 0, {q(b['category'])}, "
            f"now() - INTERVAL '{b['age_days']} days', now() - INTERVAL '{b['age_days']} days')"
        )
    f.sql(
        "INSERT INTO socialapp.t_books\n"
        "    (id, author_id, post_id, title, description, file_key, cover_image_key,\n"
        "     preview_file_key, file_format, file_size_bytes, total_pages, preview_pages,\n"
        "     price, currency, is_free, download_count, avg_rating, review_count, category,\n"
        "     created_at, updated_at) VALUES\n" + ",\n".join(rows) + ";",
        rows=len(rows),
    )

    f.note("""
── Đánh giá ─────────────────────────────────────────────────────────────────────────────────
rating 1-5 (CHECK constraint), UNIQUE(book_id, user_id). Phân phối lệch về phía tích cực như đánh
giá thật, nhưng vẫn còn 1-2 sao để bộ lọc theo sao có dữ liệu ở cả hai đầu.
""")
    feedbacks = [
        "Đọc xong áp dụng được ngay vào việc đang làm.",
        "Phần đầu hơi dài dòng nhưng càng về sau càng chắc.",
        "Ví dụ sát thực tế, không phải kiểu bài tập trong lớp.",
        "Hợp với người đã có nền tảng, người mới sẽ hơi nặng.",
        "Mình đọc lại lần hai vẫn thấy thêm được thứ mới.",
        "Nội dung ổn nhưng nhiều chỗ đã cũ so với hiện tại.",
        "Không như mình kỳ vọng, phần thực hành quá ít.",
        "Đáng tiền, nhất là ba chương cuối.",
    ]
    rows = [
        f"    ({bid}, {who}, {rating}, {q(feedbacks[(bid + who) % len(feedbacks)])}, "
        f"now() - INTERVAL '{age} days', now() - INTERVAL '{age} days')"
        for bid, who, rating, age in reviews
    ]
    f.sql(
        "INSERT INTO socialapp.t_book_reviews\n"
        "    (book_id, user_id, rating, feedback, created_at, updated_at) VALUES\n"
        + ",\n".join(rows) + ";",
        rows=len(rows),
    )

    f.note("""
── Giao dịch ────────────────────────────────────────────────────────────────────────────────
Bốn trạng thái đều có mặt. Trạng thái được chọn bằng MỘT modulo khác với modulo dùng để lọc — dùng
chung một modulo đã làm mất hẳn hai trạng thái ở bộ seed cũ.

payment_method là 'MOMO': MomoApiClient dùng luồng ví, và 'ATM' không còn là giá trị hợp lệ kể từ
khi REQUEST_TYPE đổi. gateway_transaction_no chỉ có khi giao dịch đã đi tới đích.

MỌI HÀNG PENDING ĐỀU LÙI QUÁ 15 PHÚT trừ đúng một hàng cố ý. MomoService.createPayment từ chối cho
mua lại một quyển đang có giao dịch PENDING trong vòng PENDING_PAYMENT_STALE_MINUTES = 15 phút —
nên một hàng PENDING mới tinh sẽ CHẶN người demo bấm mua. Hàng cố ý đó nằm ở một quyển KHÔNG thuộc
kịch bản demo, và tồn tại để chạy được nhánh từ chối kèm thông báo "quay lại sau".
""")
    rows = []
    for bid, who, amount, status, age in purchases:
        fresh = status == "PENDING_FRESH"
        real_status = "PENDING" if fresh else status
        paid = (f"now() - INTERVAL '{age} days'"
                if real_status in ("COMPLETED", "REFUNDED") else "NULL")
        gateway = q(f"MOMO{bid}{who}") if real_status in ("COMPLETED", "REFUNDED") else "NULL"
        method = "'MOMO'" if real_status in ("COMPLETED", "REFUNDED") else "NULL"
        created = "now() - INTERVAL '4 minutes'" if fresh else f"now() - INTERVAL '{age} days'"
        rows.append(
            f"    ({bid}, {who}, {amount}, 'VND', {q(real_status)}, {q(f'SEED-{bid}-{who}')}, "
            f"{gateway}, {method}, NULL, {paid}, {created})"
        )
    f.sql(
        "INSERT INTO socialapp.t_book_purchases\n"
        "    (book_id, buyer_id, amount, currency, payment_status, transaction_ref,\n"
        "     gateway_transaction_no, payment_method, payment_link_id, paid_at, created_at) VALUES\n"
        + ",\n".join(rows) + ";",
        rows=len(rows),
    )

    f.note("avg_rating và review_count tính lại từ dữ liệu vừa chèn, không gõ tay.")
    f.sql("""
UPDATE socialapp.t_books b
   SET avg_rating   = COALESCE(r.avg_rating, 0.0),
       review_count = COALESCE(r.total, 0),
       download_count = COALESCE(p.total, 0)
  FROM (SELECT book_id, ROUND(AVG(rating)::numeric, 1) AS avg_rating, COUNT(*) AS total
          FROM socialapp.t_book_reviews GROUP BY book_id) r
  LEFT JOIN (SELECT book_id, COUNT(*) AS total
               FROM socialapp.t_book_purchases
              WHERE payment_status = 'COMPLETED' GROUP BY book_id) p ON p.book_id = r.book_id
 WHERE r.book_id = b.id;""")

    f.note("Đẩy sequence quá dải id gán tay và quá dữ liệu vừa chèn.")
    f.sql(f"""
SELECT setval('socialapp.q_books_id', {BOOK_ID_FIRST + len(books) + 1}, FALSE);
SELECT setval('socialapp.q_book_reviews_id',
              GREATEST((SELECT COALESCE(MAX(id), 0) FROM socialapp.t_book_reviews), 1), true);
SELECT setval('socialapp.q_book_purchases_id',
              GREATEST((SELECT COALESCE(MAX(id), 0) FROM socialapp.t_book_purchases), 1), true);""")
    return f


# ═══ V86 — kho tri thức ════════════════════════════════════════════════════════════════════════

EXPLANATION_ID_FIRST = 300001

_MD = chr(10)
# Bản giải thích chứa MARKDOWN THẬT, phủ đủ tám loại phần tử mà thẻ giải thích dựng lại được.
# Thiếu bất kỳ loại nào thì nhánh dựng loại đó chưa từng chạy với dữ liệu thật.
MARKDOWN_EXPLANATION = _MD.join([
    "## Vì sao truy vấn này chậm",
    "",
    "Vấn đề **không nằm ở đĩa** mà ở số lượt gọi. Mỗi bài trong trang gọi thêm một truy vấn để",
    "lấy tác giả, nên một trang 20 bài sinh ra 21 truy vấn thay vì 2.",
    "",
    "### Ba thứ cần kiểm trước",
    "",
    # Gạch đầu dòng BẮT ĐẦU bằng mã nội tuyến (`* ` rồi ngay dấu nháy ngược) là một nhánh dựng
    # riêng của thẻ giải thích, khác với gạch đầu dòng có mã ở giữa câu. Cần cả hai kiểu.
    "* `spring.jpa.open-in-view` — để `true` thì association lazy tự nạp lúc dựng JSON",
    "* Có `fetch join` ở đúng chỗ hay không",
    "* Thứ tự cột của index so với mệnh đề `ORDER BY`",
    "",
    "Các bước sửa theo thứ tự:",
    "",
    "1. Bật `show-sql` và đếm số câu lệnh thật sự chạy",
    "2. Thêm `@EntityGraph` hoặc `JOIN FETCH` cho association đang bị nạp lẻ",
    "3. Đo lại, rồi mới cân nhắc thêm index",
    "",
    "```java",
    "@Query(\"SELECT p FROM PostEntity p JOIN FETCH p.author WHERE p.id IN :ids\")",
    "List<PostEntity> findAllWithAuthor(@Param(\"ids\") List<Long> ids);",
    "```",
    "",
    "| Cách làm | Số truy vấn | Thời gian |",
    "| --- | --- | --- |",
    "| Ban đầu | 21 | 2.4s |",
    "| Thêm fetch join | 2 | 180ms |",
    "",
    "Đọc thêm ở [tài liệu Hibernate](https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html)",
    "nếu muốn hiểu vì sao `open-in-view` che mất vấn đề này.",
])

CONCEPT_SETS = [
    (["N+1 query", "fetch join", "lazy loading"], ["JPA cơ bản", "SQL JOIN"], "BACKEND"),
    (["connection pool", "statement timeout", "backpressure"], ["Mô hình luồng", "TCP"], "DEVOPS"),
    (["cache invalidation", "TTL", "cache stampede"], ["Redis cơ bản"], "BACKEND"),
    (["index tổ hợp", "selectivity", "query plan"], ["B-tree", "SQL"], "BACKEND"),
    (["idempotency key", "retry", "exactly-once"], ["HTTP", "Hàng đợi"], "BACKEND"),
    (["code splitting", "lazy import", "bundle size"], ["ES modules"], "FRONTEND"),
    (["layout shift", "critical CSS", "preload"], ["CSS", "Trình duyệt"], "FRONTEND"),
    (["conflict resolution", "offline queue", "sync token"], ["HTTP", "SQLite"], "MOBILE"),
    (["feature scaling", "data leakage", "cross validation"], ["Thống kê"], "DATA_ML"),
    (["threat model", "least privilege", "secret rotation"], ["Mật mã cơ bản"], "SECURITY"),
    (["test double", "flaky test", "kim tự tháp kiểm thử"], ["JUnit", "CI cơ bản"], "QA"),
    (["độ phức tạp khấu hao", "cục bộ bộ nhớ", "bất biến vòng lặp"], ["Cấu trúc dữ liệu"], "OTHER"),
]

# Bản giải thích DUY NHẤT mang chủ đề CAREER — fixture "một chủ đề chỉ có đúng một hàng" cho màn
# Kho lưu trữ. Đó là ca kiểm phân trang đáng giá nhất vì hàng ấy không nằm ở trang đầu, và nó
# chứng minh bộ lọc thật sự lọc chứ không chỉ đổi nhãn. Nội dung phải THẬT thuộc về CAREER: nhãn
# bám nội dung, không rải cho đủ tab — nên nó nói về đọc mã người khác, không về kỹ thuật nào cả.
CAREER_CONCEPT_SET = (
    ["đọc mã người khác", "phản hồi trong review", "ghi lại quyết định"],
    ["Làm việc nhóm"],
    "CAREER",
)

# EXPLANATION_PROSE_BY_CONCEPT[j] giải thích ĐÚNG bộ concept ở CONCEPT_SETS[j] — cùng chỉ số, và
# build_knowledge() chọn cả hai bằng đúng MỘT index để nội dung không bao giờ lệch khỏi cái nhãn
# concepts nó tuyên bố giải thích. Bản cũ (EXPLANATION_PROSE phẳng, 5 đoạn abstract) chọn concepts
# và content bằng hai modulo ĐỘC LẬP: một bài dán nhãn concepts = ["idempotency key", …] có thể
# nhận content nói về bất biến vòng lặp — nhãn và nội dung không khớp nhau, và đó cũng là loại
# "seed slop" tệ hơn cả câu lặp lại: nó nói dối về việc mình đang giải thích cái gì. Mỗi đoạn dưới
# đây là một sự kiện/kỹ thuật THẬT về đúng ba khái niệm được liệt, diễn đạt lại chứ không trích
# nguyên văn (cùng tinh thần với TOPIC_FACTS).
EXPLANATION_PROSE_BY_CONCEPT = [
    [  # N+1 query / fetch join / lazy loading
        "N+1 xảy ra khi lazy loading để mỗi bản ghi cha tự đi hỏi riêng bảng con, biến một thao "
        "tác lẽ ra một truy vấn thành N+1 truy vấn. Fetch join gộp cha và con vào đúng một câu "
        "SELECT, nhưng chỉ hợp khi có một quan hệ collection cần tải cùng lúc — nhiều quan hệ "
        "collection trong một fetch join lại sinh tích Descartes, khi đó nên đổi sang @BatchSize.",
        "Bật default_batch_fetch_size gom các lượt gọi lazy thành từng đợt WHERE id IN (...) thay "
        "vì từng truy vấn riêng lẻ — cách rẻ nhất để giảm N+1 mà không phải sửa logic truy vấn ở "
        "từng chỗ gọi.",
        "Đổi FetchType sang EAGER để né N+1 là hướng sai: Hibernate vẫn phát sinh truy vấn phụ cho "
        "mỗi bản ghi bất kể fetch type — lazy loading chỉ trì hoãn thời điểm gọi, không phải "
        "nguyên nhân gốc của vấn đề.",
    ],
    [  # connection pool / statement timeout / backpressure
        "Connection pool giới hạn số kết nối database mà ứng dụng giữ đồng thời; khi mọi kết nối "
        "đang bận, request mới phải xếp hàng chờ — đây là backpressure tự nhiên chặn ứng dụng "
        "không dội quá tải xuống database.",
        "Statement timeout đặt một trần cứng cho thời gian một câu truy vấn được phép chạy. Nó "
        "không làm truy vấn nhanh hơn, chỉ đảm bảo một truy vấn hỏng không giữ kết nối vô thời "
        "hạn và kéo cả pool theo.",
        "Pool cạn không phải lỗi hiếm: khi tầng phía dưới đột ngột cho phép nhiều request chạy "
        "song song hơn (như bỏ trần platform thread cũ), connection pool thường là nơi nghẽn tiếp "
        "theo lộ ra.",
    ],
    [  # cache invalidation / TTL / cache stampede
        "Cache stampede xảy ra khi một key phổ biến hết hạn và hàng loạt request cùng lúc dội "
        "xuống database để tái tạo giá trị — vấn đề nằm ở TTL đồng loạt, không nằm ở bản thân "
        "việc dùng cache.",
        "Rắc jitter ngẫu nhiên khoảng 10-20% vào TTL tránh nhiều key hết hạn cùng thời điểm; khoá "
        "mutex ngắn hạn đảm bảo chỉ một request đi tái tạo cache, các request còn lại chờ hoặc "
        "nhận bản cũ.",
        "Cache invalidation khó hơn TTL vì nó đòi biết chính xác khi nào dữ liệu gốc đổi — TTL "
        "chỉ là cách né việc đó bằng cách chấp nhận dữ liệu cũ trong một khoảng thời gian có "
        "kiểm soát.",
    ],
    [  # index tổ hợp / selectivity / query plan
        "Selectivity của một cột càng cao (giá trị càng đa dạng) thì index trên cột đó càng hiệu "
        "quả — index trên một cột chỉ có vài giá trị như boolean thường không giúp gì.",
        "Query plan (đọc qua EXPLAIN ANALYZE) mới là bằng chứng thật; đoán 'chắc index sẽ giúp' "
        "mà không xem plan trước và sau là cách phổ biến nhất khiến một index mới được thêm vào "
        "mà chẳng đổi gì.",
        "Trong index tổ hợp, quy tắc leftmost prefix nghĩa là thứ tự cột khai quyết định index "
        "dùng được cho truy vấn nào — đặt sai thứ tự thì planner âm thầm bỏ qua index đó.",
    ],
    [  # idempotency key / retry / exactly-once
        "'Exactly-once' gần như không tồn tại thật trong hệ phân tán; thứ khả thi là 'at-least-"
        "once' cộng idempotency key để retry an toàn — nhận trùng sự kiện không còn nguy hiểm vì "
        "xử lý lần hai chỉ trả lại đúng kết quả lần đầu.",
        "Idempotency key phải là một giá trị duy nhất do client sinh, gửi kèm mọi lần gọi kể cả "
        "lần retry — server lưu key này cùng kết quả để nhận diện và bỏ qua các lần gọi lặp.",
        "Retry mù trên một thao tác không idempotent — như trừ tiền hai lần vì request đầu bị "
        "timeout nhưng thực ra đã xử lý xong — là lớp lỗi phổ biến nhất khi thêm cơ chế thử lại "
        "mà không nghĩ tới trùng lặp.",
    ],
    [  # code splitting / lazy import / bundle size
        "Code splitting theo route chỉ gửi xuống trình duyệt phần JavaScript cần cho trang đang "
        "xem, thay vì gộp toàn bộ ứng dụng vào một bundle tải ngay từ lần đầu.",
        "Lazy import trì hoãn việc tải một component nặng tới đúng lúc người dùng cần tới nó — "
        "đúng thời điểm quan trọng hơn kỹ thuật: lazy import một thứ ai cũng dùng ngay khi mở app "
        "không tiết kiệm được gì.",
        "Bundle size phình ra thường không phải vì code của chính dự án mà vì một thư viện bên "
        "thứ ba nặng hơn tưởng — công cụ phân tích bundle luôn đáng chạy trước khi tối ưu tay.",
    ],
    [  # layout shift / critical CSS / preload
        "Layout shift xảy ra khi một phần tử đổi vị trí sau khi trang đã render — nguyên nhân phổ "
        "biến nhất là ảnh hoặc quảng cáo chưa được chừa chỗ trước bằng width/height hay "
        "aspect-ratio.",
        "Critical CSS là phần style cần cho nội dung hiện ngay trong màn hình đầu tiên, nhúng "
        "trực tiếp vào HTML để trình duyệt không phải chờ tải file CSS ngoài mới vẽ được gì.",
        "Preload báo trước cho trình duyệt về một tài nguyên sẽ cần sớm (như font chữ) để nó bắt "
        "đầu tải ngay, tránh việc chữ đổi font giữa chừng gây nhảy layout.",
    ],
    [  # conflict resolution / offline queue / sync token
        "Offline queue lưu lại mọi thao tác người dùng làm khi mất mạng, rồi phát lại theo đúng "
        "thứ tự khi kết nối trở lại — ghi phải luôn thành công cục bộ trước, đồng bộ là việc làm "
        "sau.",
        "Sync token đánh dấu điểm đồng bộ gần nhất giữa thiết bị và máy chủ, để lần đồng bộ tiếp "
        "theo chỉ cần gửi phần thay đổi từ token đó thay vì gửi lại toàn bộ dữ liệu.",
        "Conflict resolution kiểu CRDT hợp nhất thay đổi từ nhiều thiết bị mà không cần máy chủ "
        "trọng tài, miễn phép hợp là giao hoán — nhưng nó chỉ giải xung đột cấu trúc dữ liệu, "
        "không giải xung đột nghiệp vụ.",
    ],
    [  # feature scaling / data leakage / cross validation
        "Feature scaling cần thiết cho các thuật toán dựa trên khoảng cách — thiếu bước này, một "
        "đặc trưng có giá trị lớn hơn sẽ áp đảo các đặc trưng khác dù không quan trọng hơn.",
        "Data leakage là khi thông tin từ tập kiểm tra vô tình lọt vào quá trình huấn luyện (ví "
        "dụ chuẩn hoá dữ liệu trước khi chia train/test) — mô hình trông rất tốt lúc đánh giá "
        "nhưng thất bại khi gặp dữ liệu thật.",
        "Cross validation chia dữ liệu thành nhiều phần, huấn luyện và đánh giá luân phiên trên "
        "các phần khác nhau, cho một ước lượng hiệu năng đáng tin hơn nhiều so với chỉ chia một "
        "lần train/test.",
    ],
    [  # threat model / least privilege / secret rotation
        "Threat model trả lời ba câu hỏi trước khi viết một dòng mã bảo mật nào: ai muốn tấn "
        "công, họ muốn gì, và họ vào bằng đường nào — thiếu bước này, phòng thủ dễ mạnh chỗ không "
        "ai tấn công và yếu đúng chỗ hay bị nhắm tới.",
        "Least privilege nghĩa là mỗi thành phần chỉ được cấp đúng quyền cần cho việc nó làm, "
        "không hơn — một service đọc dữ liệu không cần quyền ghi, dù cấp thêm có vẻ 'tiện cho sau "
        "này'.",
        "Secret rotation dùng mẫu hai khoá song song: phát khoá mới, chấp nhận cả khoá cũ lẫn mới "
        "trong một khoảng chuyển tiếp, rồi mới thu hồi khoá cũ — xoay đột ngột không có giai đoạn "
        "chuyển tiếp sẽ làm gián đoạn mọi client chưa kịp cập nhật.",
    ],
    [  # test double / flaky test / kim tự tháp kiểm thử
        "Test double (mock, stub, fake) thay thế một phụ thuộc thật để cô lập đơn vị đang test — "
        "hữu ích cho logic thuần tuý, nhưng không bắt được lỗi tích hợp thật như connection pool, "
        "migration hay timeout.",
        "Test giòn phần lớn bắt nguồn từ việc chờ theo thời gian cố định thay vì chờ theo điều "
        "kiện thật, hoặc từ trạng thái dùng chung giữa các test chạy không theo thứ tự cố định.",
        "Kim tự tháp kiểm thử khuyên nhiều test đơn vị nhỏ, ít test tích hợp hơn, và rất ít test "
        "đầu cuối — đảo ngược tỉ lệ này làm bộ test chậm và giòn hơn hẳn.",
    ],
    [  # độ phức tạp khấu hao / cục bộ bộ nhớ / bất biến vòng lặp
        "Độ phức tạp khấu hao tính chi phí trung bình trên nhiều lần gọi, không phải chi phí của "
        "lần tệ nhất — một mảng động thi thoảng tốn O(n) để mở rộng vẫn có độ phức tạp khấu hao "
        "O(1) cho mỗi lần thêm phần tử.",
        "Tính cục bộ bộ nhớ giải thích vì sao duyệt một mảng thường nhanh hơn duyệt một danh sách "
        "liên kết dù cùng độ phức tạp O(n) — CPU cache nạp cả một dải bộ nhớ liền kề, mảng tận "
        "dụng được điều đó còn danh sách liên kết thì không.",
        "Bất biến vòng lặp là điều kiện luôn đúng trước và sau mỗi lần lặp — xác định đúng bất "
        "biến là cách chắc chắn nhất để chứng minh một thuật toán vòng lặp làm đúng việc nó "
        "tuyên bố làm, thay vì chỉ 'chạy thử thấy đúng'.",
    ],
]
assert len(EXPLANATION_PROSE_BY_CONCEPT) == len(CONCEPT_SETS)
assert all(len(variants) == 3 for variants in EXPLANATION_PROSE_BY_CONCEPT)

# Dạng phẳng, cho ghi chú vault: mỗi ghi chú cá nhân không gắn với đúng một bộ concept như bản
# giải thích AI, nên xoay vòng qua cả 36 đoạn thay vì lặp 5 đoạn abstract cũ 80 lần mỗi đoạn.
EXPLANATION_PROSE_FLAT = [text for group in EXPLANATION_PROSE_BY_CONCEPT for text in group]

VAULT_TAGS = [["backend", "ghi-chú"], ["kiến-trúc"], ["hiệu-năng", "đo-đạc"], ["đọc-sách"],
              ["phỏng-vấn"], ["devops", "vận-hành"], ["frontend"], ["ý-tưởng"]]


def build_knowledge(rng, people, posts):
    users = [p["id"] for p in people if p["primary_role"]]
    candidates = [p for p in posts if p["kind"] in ("REGULAR", "ARTICLE", "CODE_SNIPPET")]

    explanations = []
    seen = set()
    eid = EXPLANATION_ID_FIRST

    # Bản giải thích chứa Markdown thật — fixture S8. Đặt trước để chắc chắn nó tồn tại.
    fixture_post = candidates[0]
    explanations.append({
        "id": eid, "post_id": fixture_post["id"], "user_id": DEMO_EXPERT,
        "original": fixture_post["content"],
        "content": MARKDOWN_EXPLANATION,
        "concepts": CONCEPT_SETS[0][0], "prereq": CONCEPT_SETS[0][1],
        "category": CONCEPT_SETS[0][2], "complexity": 4,
        # external_links là jsonb ánh xạ tới List<ExplanationResponseDto.ExternalLink>, tức một
        # mảng ĐỐI TƯỢNG {title, url, reason} — KHÔNG phải mảng chuỗi URL. Một mảng chuỗi vẫn chèn
        # được (cột chỉ là jsonb) và mọi assertion SQL vẫn xanh, nhưng Hibernate ném
        # InvalidDataAccessApiUsageException("Could not deserialize string to java type") ngay khi
        # đọc hàng — và vì hàng này nằm trong Kho lưu trữ của tài khoản demo, cả màn hình
        # /knowledge/my-library trả 500. Đây là cùng một lớp lỗi với enum sai tên, chỉ khác cơ
        # chế: hình dạng jsonb, không phải tên hằng.
        #
        # `t_vault_notes.links` thì ĐÚNG là List<String> — hai cột cùng tên "links" mang hai kiểu
        # khác nhau, nên đừng sao chép hình dạng từ bên này sang bên kia.
        "links": [{
            "title": "Hibernate ORM User Guide — chương Fetching",
            "url": "https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html",
            "reason": "Giải thích vì sao fetch join gỡ được N+1 mà lazy loading thì không.",
        }],
        "age": 30,
    })
    seen.add((fixture_post["id"], DEMO_EXPERT))
    eid += 1

    # Hàng CAREER duy nhất. Đặt trước vòng lặp vì vòng lặp chọn bộ concepts bằng modulo, tức không
    # có cách nào bảo nó sinh đúng một hàng của một chủ đề.
    career_post = candidates[1]
    career_reader = users[0] if users[0] != DEMO_EXPERT else users[1]
    explanations.append({
        "id": eid, "post_id": career_post["id"], "user_id": career_reader,
        "original": career_post["content"],
        "content": "Thứ đáng học ở đoạn này không phải kỹ thuật mà là cách người viết để lại dấu "
                   "vết cho người đọc sau: mỗi lựa chọn khó đều có một câu giải thích ngay cạnh "
                   "nó. Đọc mã người khác nhanh hay chậm phần lớn do chỗ đó quyết định.",
        "concepts": CAREER_CONCEPT_SET[0], "prereq": CAREER_CONCEPT_SET[1],
        "category": CAREER_CONCEPT_SET[2], "complexity": 2,
        "links": [],
        "age": 45,
    })
    seen.add((career_post["id"], career_reader))
    eid += 1

    while len(explanations) < 1600:
        post = rng.choice(candidates)
        who = rng.choice(users)
        if (post["id"], who) in seen:
            continue
        seen.add((post["id"], who))
        # concept_idx chọn CẢ NHÃN LẪN NỘI DUNG bằng đúng một chỉ số — content luôn giải thích
        # đúng bộ concepts nó tuyên bố, không lệch nhau như bản EXPLANATION_PROSE phẳng cũ.
        concept_idx = (post["id"] + who) % len(CONCEPT_SETS)
        concepts, prereq, category = CONCEPT_SETS[concept_idx]
        variants = EXPLANATION_PROSE_BY_CONCEPT[concept_idx]
        explanations.append({
            "id": eid, "post_id": post["id"], "user_id": who,
            "original": post["content"],
            "content": variants[(post["id"] * 3 + who) % len(variants)],
            "concepts": concepts, "prereq": prereq, "category": category,
            "complexity": rng.randint(1, 5),
            "links": [],
            "age": rng.randint(1, 400),
        })
        eid += 1

    notes = []
    seen_note = set()
    while len(notes) < 400:
        who = rng.choice(users)
        idx = len(notes)
        filename = f"ghi-chu-{idx:03d}.md"
        if (who, filename) in seen_note:
            continue
        seen_note.add((who, filename))
        notes.append({
            "user_id": who, "filename": filename,
            "content": EXPLANATION_PROSE_FLAT[idx % len(EXPLANATION_PROSE_FLAT)],
            "tags": VAULT_TAGS[idx % len(VAULT_TAGS)],
            "links": [f"ghi-chu-{max(0, idx - 1):03d}.md"] if idx % 4 == 0 else [],
            "age": rng.randint(1, 400),
        })
    return explanations, notes


def emit_knowledge(explanations, notes):
    f = SqlFile(86, "seed_knowledge",
                f"{len(explanations):,} bản giải thích AI và {len(notes)} ghi chú vault.")
    f.note("""
FILE NÀY SINH TỰ ĐỘNG bởi scripts/seed/generate_seed.py — sửa tay sẽ bị ghi đè.

MỘT bản giải thích chứa Markdown THẬT, phủ đủ tám loại phần tử mà thẻ giải thích dựng lại được:
tiêu đề `##`, in đậm `**`, danh sách `*` có mã trong nháy ngược, danh sách đánh số `1.`, khối mã
```java, bảng GFM `| --- |`, và liên kết `](https://`. Các bản còn lại là văn xuôi thuần, cố ý —
nếu bản nào cũng có Markdown thì nhánh "văn xuôi thuần" không bao giờ chạy, và ngược lại.

category bám NỘI DUNG THẬT của từng bộ concepts, không rải cho đủ chín tab. Số lượng giữa các chủ
đề LỆCH HẲN NHAU và đó là điều đúng: bốn trong mười hai bộ concepts nói về phía máy chủ, nên
BACKEND đông hơn hẳn phần còn lại. Cân bằng chúng lại sẽ cho một màn hình demo đẹp hơn và một cơ
sở dữ liệu nói dối.

CAREER có ĐÚNG MỘT hàng, cố ý: đó là ca kiểm chứng minh bộ lọc thật sự lọc, và vì hàng ấy không
nằm ở trang đầu nên nó cũng là ca kiểm phân trang duy nhất đáng giá ở màn này.

UNIQUE(post_id, user_id, version) — mỗi người chỉ có một bản giải thích cho mỗi bài ở version 1.
""")
    f.rule()

    rows = []
    for e in explanations:
        rows.append(
            f"    ({e['id']}, {e['post_id']}, {e['user_id']}, {q(e['original'])}, "
            f"{q(e['content'])}, {jsonb(e['concepts'])}, {jsonb(e['prereq'])}, "
            f"{e['complexity']}, NULL, 1, {jsonb(e['links'])}, {q(e['category'])}, "
            f"now() - INTERVAL '{e['age']} days', now() - INTERVAL '{e['age']} days')"
        )
    f.sql(
        "INSERT INTO socialapp.t_explanations\n"
        "    (id, post_id, user_id, original_content, explanation_content, concepts,\n"
        "     prerequisites, complexity_score, feedback_note, version, external_links, category,\n"
        "     created_at, updated_at) VALUES\n" + ",\n".join(rows) + ";",
        rows=len(rows),
    )

    f.note("""
── Ghi chú vault ────────────────────────────────────────────────────────────────────────────
UNIQUE(user_id, filename). `links` là tên file khác trong cùng vault, đúng cách Obsidian liên kết.
""")
    rows = [
        f"    ({n['user_id']}, {q(n['filename'])}, {q(n['content'])}, {jsonb(n['tags'])}, "
        f"{jsonb(n['links'])}, now() - INTERVAL '{n['age']} days', "
        f"now() - INTERVAL '{n['age']} days')"
        for n in notes
    ]
    f.sql(
        "INSERT INTO socialapp.t_vault_notes\n"
        "    (user_id, filename, content, tags, links, created_at, updated_at) VALUES\n"
        + ",\n".join(rows) + ";",
        rows=len(rows),
    )

    f.note("Đẩy sequence quá dải id gán tay.")
    last = max(e["id"] for e in explanations)
    f.sql(f"""
SELECT setval('socialapp.q_explanations_id', {last + 1}, FALSE);
SELECT setval('socialapp.q_vault_notes_id',
              GREATEST((SELECT COALESCE(MAX(id), 0) FROM socialapp.t_vault_notes), 1), true);""")
    return f


# ═══ V87 — dự án và tuyển thành viên ═══════════════════════════════════════════════════════════

PROJECT_ID_FIRST = 4001
PROJECT_COUNT = 50

# Mỗi ý tưởng gắn một chi tiết THẬT tìm được ngày 2026-09-01 từ một sản phẩm/công cụ/bài viết kỹ
# thuật công khai cùng thể loại — không nêu tên hay link nguồn trong câu, chỉ giữ lại sự kiện đã
# paraphrase, theo đúng quy ước không trích nguyên văn dùng xuyên suốt file này.
PROJECT_IDEAS = [
    ("Nền tảng chia sẻ kiến thức nội bộ", "Nơi các đội ghi lại quyết định kỹ thuật và tra cứu lại được sau vài năm — cổng kiến thức nội bộ kiểu này từng giúp một hãng phát nhạc lớn giảm hẳn thời gian loay hoay tìm cách làm đúng thay vì viết mã, đổi lại chu kỳ phát triển của đội rút ngắn rõ rệt."),
    ("Thư viện component tiếng Việt", "Bộ component có sẵn phần chữ và định dạng ngày giờ theo thói quen trong nước — cùng một chuỗi ngày viết theo kiểu Mỹ và kiểu châu Âu có thể đọc ra hai ngày khác nhau, nên định dạng nên luôn qua một lớp chuẩn hoá dùng chung thay vì gõ tay ở từng nơi."),
    ("Bộ công cụ theo dõi chi phí hạ tầng", "Gom hoá đơn từ nhiều nhà cung cấp về một bảng, cảnh báo khi vượt ngưỡng — phần lớn lãng phí hạ tầng chỉ lộ ra khi hoá đơn cuối tháng về tay, và cấp phát tài nguyên dư thừa vẫn là nguyên nhân phổ biến nhất khiến chi phí vượt dự toán."),
    ("Hệ thống gợi ý bài viết", "Xếp hạng nội dung theo hành vi đọc thật thay vì theo thời gian đăng — các hệ gợi ý lớn thường dựa vào tín hiệu ngầm như thời gian đọc thực tế và lượt tìm lại một nội dung, chứ không hỏi thẳng người dùng thích gì."),
    ("Ứng dụng ghi chú offline-first", "Ghi được khi mất mạng, đồng bộ lại khi có mạng mà không mất dữ liệu — cơ chế hợp nhất kiểu CRDT cho phép nhiều thiết bị sửa cùng lúc, ngoại tuyến, rồi tự hội tụ về đúng một kết quả mà không cần máy chủ phân xử."),
    ("Công cụ quét cấu hình bảo mật", "Rà cấu hình hạ tầng và báo những chỗ lệch khỏi chuẩn tối thiểu — các công cụ dạng này thường đối chiếu quyền truy cập, cấu hình lưu trữ và mã hoá với một bộ chuẩn công khai như CIS, không dò theo cảm tính của người kiểm tra."),
    ("Khung kiểm thử tự động dùng chung", "Bộ khung để các đội viết test tích hợp mà không dựng lại hạ tầng — dựng một instance cơ sở dữ liệu thật riêng cho mỗi lượt chạy rồi huỷ ngay sau đó tránh được kiểu lỗi ngẫu nhiên do trạng thái cũ để lại từ lượt chạy trước."),
    ("Bảng điều khiển sức khoẻ dịch vụ", "Một màn hình cho biết dịch vụ nào đang hỏng và hỏng từ lúc nào — bảng nội bộ dạng này thường có thêm liên kết runbook và tên người trực ca ngay cạnh mỗi cảnh báo, để người xử lý sự cố không phải hỏi lại ai đang phụ trách."),
    ("Bộ sinh tài liệu API từ mã nguồn", "Đọc mã nguồn và sinh tài liệu, để tài liệu không lệch khỏi thực tế — tài liệu viết tay và mã nguồn thường trôi dần theo hai hướng khác nhau theo thời gian, và phần lớn lỗi tích hợp giữa hai hệ thống nằm đúng ở khoảng lệch đó."),
    ("Ứng dụng quản lý mục tiêu cá nhân", "Theo dõi mục tiêu học tập theo tuần, nhắc nhẹ chứ không ép — một tổng hợp trên hàng trăm nghiên cứu cho thấy việc tự ghi lại tiến độ đều đặn thật sự nâng khả năng đạt mục tiêu, và hiệu quả càng rõ khi tiến độ được ghi công khai thay vì chỉ giữ trong đầu."),
    ("Cổng đăng nhập dùng chung cho nội bộ", "Một chỗ quản lý tài khoản cho toàn bộ công cụ nội bộ — đăng nhập một lần cho mọi ứng dụng giúp việc thu hồi quyền khi ai đó nghỉ việc diễn ra ngay lập tức ở mọi nơi, thay vì phải tắt từng tài khoản rời rạc theo tay."),
    ("Hệ thống hàng đợi việc nền", "Xử lý việc chạy lâu mà không giữ kết nối của người dùng — hàng đợi việc nền hầu như luôn giao việc theo kiểu 'ít nhất một lần', nên một việc chạy trùng do thử lại là chuyện bình thường hệ thống phải tự chịu được, không phải trường hợp hiếm gặp."),
    ("Trình phân tích log tập trung", "Gom log từ nhiều dịch vụ, tìm theo mã theo dõi xuyên suốt một request — mã này thường được gán ngay ở dịch vụ nhận request đầu tiên rồi truyền qua header sang các dịch vụ phía sau, nối lại thành một hành trình duy nhất khi tra log."),
    ("Ứng dụng học từ vựng kỹ thuật", "Học thuật ngữ chuyên ngành bằng cách lặp lại ngắt quãng — thí nghiệm quên lãng kinh điển từng đo được trí nhớ rơi rất nhanh trong giờ đầu sau khi học, và mỗi lần ôn đúng lúc sắp quên làm đường cong quên đó phẳng dần theo thời gian."),
    ("Bộ chuyển đổi dữ liệu giữa các hệ", "Đọc từ một nguồn, ghi ra nhiều định dạng, có kiểm tra tính toàn vẹn — nhiều công cụ chuyển dữ liệu mã nguồn mở ra đời chính vì các bộ kết nối tự phát thiếu một chuẩn giao thức chung, mỗi người viết một kiểu khiến ghép nối giữa hai hệ rất dễ vỡ."),
    ("Trình quản lý bí mật cho môi trường dev", "Giữ khoá API và chuỗi kết nối ngoài mã nguồn, cấp theo từng máy — bí mật nằm trong file cấu hình hay biến môi trường vẫn là nguyên nhân phổ biến nhất của các sự cố lộ thông tin đăng nhập, nên tập trung chúng vào một nơi có nhật ký truy cập là hướng đi được khuyến nghị."),
    ("Bảng xếp hàng review pull request", "Cho thấy PR nào đang chờ lâu nhất và ai đang là nút cổ chai — khảo sát trên hàng trăm nghìn pull request từng chỉ ra phần lớn thời gian một PR tồn tại là nằm chờ chứ không phải đang được đọc, và khoảng chờ trước khi ai đó bắt đầu xem là nguyên nhân trễ tiến độ lớn nhất."),
    ("Công cụ dựng dữ liệu mẫu cho test", "Sinh dữ liệu giả nhất quán để test tích hợp chạy lại cho kết quả như nhau — dữ liệu giả kiểu này lấy mẫu từ những tập giá trị có thật để trông hợp lý, nhưng không gắn với bất kỳ cá nhân thật nào nên an toàn để dùng đi dùng lại nhiều lần."),
    ("Hệ thống nhắc gia hạn tên miền và chứng chỉ", "Theo dõi hạn của domain và SSL, báo trước vài tuần thay vì để hết hạn — một chứng chỉ hết hạn không ai để ý từng khiến hàng chục triệu thuê bao di động mất sóng cả ngày, và một nền tảng họp trực tuyến lớn cũng từng sập vài giờ vì đúng lý do y hệt."),
    ("Trình so sánh chi phí giữa các vùng cloud", "Ước lượng hoá đơn khi đặt dịch vụ ở vùng khác nhau trước khi triển khai — đưa ước tính chi phí vào ngay lúc lên kế hoạch hạ tầng, trước khi bấm triển khai, là cách các đội hạ tầng thường dùng để tránh phát hiện chi phí vượt dự toán khi đã quá muộn."),
    ("Ứng dụng chấm công cho nhóm làm từ xa", "Ghi giờ làm theo tự khai, tổng hợp theo tuần, không chụp màn hình — nghiên cứu về làm việc từ xa cho thấy mức năng suất người ta tự báo cáo và mức đo được khách quan không phải lúc nào cũng khớp nhau, và cách quản lý minh bạch về việc theo dõi ảnh hưởng tới hiệu suất nhiều hơn chính việc theo dõi."),
    ("Bộ lọc thư ứng tuyển theo tiêu chí", "Đọc thư ứng tuyển, gắn nhãn theo kỹ năng và mức phù hợp — phần lớn doanh nghiệp lớn đã dùng hệ thống lọc theo từ khoá kiểu này, nhưng lọc quá máy móc đúng theo từ khoá cũng là lý do không ít ứng viên giỏi bị loại oan trước khi hồ sơ tới tay người tuyển dụng."),
    ("Trình theo dõi lỗ hổng trong phụ thuộc", "Quét file khoá gói và đối chiếu với cơ sở dữ liệu lỗ hổng công khai — mã nguồn của bên thứ ba thường chiếm phần lớn một ứng dụng hiện đại, nên các công cụ quét dạng này đối chiếu trực tiếp với cơ sở dữ liệu cảnh báo bảo mật công khai thay vì tự đoán rủi ro."),
    ("Cổng tra cứu tài liệu kỹ thuật nội bộ", "Gom wiki, slide và ghi chú họp về một chỗ, tìm được bằng một ô tìm kiếm — nhiều đội kỹ thuật ghi nhận phần lớn thời gian tra cứu bị tốn vì tài liệu nằm rải trên nhiều công cụ khác nhau, chứ không phải vì thiếu tài liệu; gom về một chỗ tìm được ngay giải quyết đúng chỗ đó."),
    ("Ứng dụng lên lịch đăng bài kỹ thuật", "Xếp hàng bài viết và đăng theo giờ đặt trước trên nhiều kênh — các công cụ lên lịch đăng bài thành công thường thắng nhờ quy trình gọn: xếp hàng, xem trước, đăng đúng giờ, không cần thêm bước rườm rà nào giữa lúc viết và lúc đăng."),
    ("Bộ đo thời gian phản hồi API định kỳ", "Gọi thử các endpoint quan trọng theo chu kỳ 30-60 giây từ nhiều điểm khác nhau để tránh báo sai do lỗi mạng cục bộ, và vẽ biểu đồ độ trễ theo thời gian."),
    ("Trình chuyển đổi định dạng ảnh hàng loạt", "Đổi kích thước và định dạng cả thư mục ảnh cùng lúc, ghi ra thư mục riêng thay vì đè lên bản gốc, và giữ nguyên cấu trúc thư mục con."),
    ("Hệ thống bình chọn chủ đề buổi chia sẻ", "Cho cả nhóm đề xuất chủ đề rồi bình chọn thay vì để một người quyết — cách chọn qua khảo sát nhanh kiểu này là lý do các buổi chia sẻ nội bộ giữ được người tham gia lâu dài."),
    ("Ứng dụng ghi lại quyết định kiến trúc", "Mỗi quyết định một trang ngắn theo đúng bốn phần của mẫu ghi chép quyết định kiến trúc phổ biến từ hơn một thập kỷ trước: trạng thái, bối cảnh, lựa chọn, hệ quả — lưu thẳng trong repo để sửa và xem lại lịch sử như mọi file mã nguồn khác."),
    ("Trình dò link hỏng trong tài liệu", "Quét toàn bộ trang tài liệu định kỳ và liệt kê link trả về lỗi 404 hoặc timeout — link hỏng trong tài liệu kỹ thuật thường chỉ lộ ra khi có người đọc bấm phải, lúc đó nó đã sai từ rất lâu trước đó."),
    ("Bộ công cụ ẩn danh dữ liệu trước khi chia sẻ", "Thay tên, email và số điện thoại bằng giá trị giả nhưng vẫn đúng định dạng gốc — kỹ thuật che dữ liệu giữ nguyên định dạng, để dữ liệu giả vẫn dùng thử được cho ứng dụng mà không lộ thông tin thật."),
    ("Ứng dụng theo dõi thói quen đọc sách kỹ thuật", "Ghi số trang đọc theo tuần thay vì chuỗi ngày liên tục — người theo chuỗi ngày cứng nhắc có xu hướng bỏ cuộc hẳn khi lỡ một ngày, còn cách tính theo tổng số ngày đọc trong tuần giữ được người đọc lâu hơn."),
    ("Trình gom thông báo từ nhiều dịch vụ", "Kéo cảnh báo từ CI, giám sát và issue tracker về một luồng, gộp các cảnh báo trùng cùng một sự cố — tương quan đúng cách có thể cắt bớt phần lớn lượng nhiễu so với nhận từng cảnh báo riêng lẻ."),
    ("Hệ thống đặt phòng họp theo lịch chung", "Xem phòng nào trống theo khung giờ và khoá slot ngay khi đặt — phần lớn tình trạng trùng lịch phòng họp không đến từ việc con người quên, mà từ nhiều hệ thống lịch tách rời không đồng bộ theo thời gian thực với nhau."),
    ("Bộ sinh changelog từ lịch sử commit", "Nhóm commit theo đúng loại (fix, feat, thay đổi phá vỡ tương thích) theo một quy ước đặt tên commit chuẩn, rồi dựng ghi chú phát hành tự động cho mỗi phiên bản thay vì ngồi gõ tay từng dòng."),
    ("Ứng dụng khảo sát ẩn danh cho đội", "Hỏi một đến ba câu mỗi tuần, chỉ hiện kết quả khi đã gộp đủ số người trả lời tối thiểu — dưới ngưỡng đó thì không hiển thị, để không ai đoán ra từng câu trả lời là của ai."),
    ("Trình theo dõi ngân sách dự án cá nhân", "Ghi thu chi cho từng dự án phụ theo kiểu phong bì ngân sách số hoá — mỗi dự án một phong bì riêng, cảnh báo ngay khi phong bì đó sắp cạn trong tháng thay vì gộp chung một con số tổng."),
    ("Bộ kiểm tra cấu hình trước khi triển khai", "Chạy kiểm tra theo kiểu chặn sớm: xác nhận đủ mọi biến môi trường bắt buộc trước khi ứng dụng nhận traffic, để thiếu một biến báo lỗi rõ ràng ngay lúc khởi động thay vì rơi vào lỗi khó hiểu giữa chừng một request thật."),
    ("Kho lưu và chia sẻ truy vấn SQL hay dùng", "Đặt tên, gắn thẻ và chia sẻ câu truy vấn cho cả đội tra cứu lại — một câu truy vấn hay dùng thường gói sẵn kiến thức về cách lọc và nối bảng đúng cách, chia sẻ lại là cách rẻ nhất để kiến thức đó không chỉ nằm trong đầu một người."),
    ("Ứng dụng nhắc uống nước và nghỉ mắt", "Nhắc nghỉ mắt theo quy tắc: cứ khoảng 20 phút nhìn màn hình thì nhìn ra xa vài mét trong 20 giây — một thử nghiệm ngẫu nhiên có đối chứng từng ghi nhận cách này giảm rõ rệt điểm mỏi mắt, và tạm dừng nhắc khi đang họp."),
    ("Trình phân tích thời gian chạy test theo file", "Chỉ ra file test nào chậm nhất dựa trên thời gian chạy thực tế của các lần trước, để chia việc đều giữa các worker song song thay vì chia đều theo số lượng file — hai cách chia cho kết quả rất khác nhau khi thời gian chạy giữa các file lệch nhau nhiều."),
    ("Bộ công cụ đồng bộ dấu trang giữa trình duyệt", "Giữ danh sách link kỹ thuật giống nhau trên nhiều máy và nhiều trình duyệt khác nhau, đồng bộ qua kho lưu trữ tự chọn thay vì qua máy chủ của một bên thứ ba thu thập dữ liệu."),
    ("Hệ thống ghi nhận đóng góp mã nguồn mở nội bộ", "Đếm PR, review và issue để ghi nhận trong đánh giá cuối kỳ — nhiều tổ chức theo dõi thêm cả số người trở thành maintainer chính thức của một dự án ngoài, vì con số đó nói lên vai trò dẫn dắt chứ không chỉ số lượng đóng góp."),
    ("Ứng dụng lập kế hoạch học theo lộ trình", "Chia mục tiêu lớn thành các bước theo tuần, đánh dấu khi hoàn thành — vài tuần đầu thường là giai đoạn quyết định một thói quen học có được giữ hay bị bỏ, nên các bước đầu lộ trình đáng được thiết kế dễ hoàn thành hơn phần sau."),
    ("Trình theo dõi phiên bản thư viện đang dùng", "Liệt kê gói nào đã cũ mấy phiên bản và mức độ rủi ro khi nâng — ngay cả các công cụ tạo PR nâng cấp tự động cũng thường thiếu tín hiệu tương thích rõ ràng, nên với bản nâng cấp lớn đội ngũ vẫn có xu hướng tự tay kiểm tra thay vì gộp luôn PR tự động."),
    ("Bộ dựng trang trạng thái công khai", "Hiện tình trạng từng dịch vụ và lịch sử sự cố cho người dùng ngoài, mỗi sự cố ghi rõ thời điểm, mức ảnh hưởng, thời gian kéo dài và cách đã xử lý — một trang trạng thái làm tốt phần này thường cắt giảm đáng kể lượng yêu cầu hỗ trợ hỏi hệ thống có đang lỗi không."),
    ("Hệ thống chấm điểm chất lượng dữ liệu", "Chạy các quy tắc kiểm tra trên bảng theo từng chiều chất lượng riêng — đầy đủ, nhất quán, không trùng lặp, đúng định dạng — rồi cho điểm theo tỉ lệ hợp lệ của từng chiều thay vì gộp chung một con số duy nhất."),
    ("Ứng dụng ghi chú cuộc họp có gắn việc", "Tách phần việc ra khỏi biên bản ngay trong lúc họp và giao rõ người phụ trách kèm hạn — việc không gắn tên người cụ thể là dạng việc gần như chắc chắn bị quên ngay sau cuộc họp."),
    ("Trình mô phỏng tải cho API nội bộ", "Bắn lượng yêu cầu tăng dần theo từng bậc, giữ mỗi bậc vài phút để quan sát độ trễ và tỉ lệ lỗi trước khi tăng tiếp, rồi ghi lại đúng điểm hệ thống bắt đầu xuống cấp thay vì dừng ở một mức tải cố định đoán trước."),
    ("Bộ công cụ dọn nhánh Git đã gộp", "Tìm nhánh đã merge từ lâu và xoá sau khi xác nhận — thực hành phổ biến là xoá ngay sau khi merge chứ không đợi dọn hàng loạt, vì nhánh đã vào nhánh chính thì không còn lý do gì để giữ lại."),
]

POSITION_TITLES = {
    "BACKEND": "Kỹ sư Backend", "FRONTEND": "Kỹ sư Frontend", "FULLSTACK": "Kỹ sư Fullstack",
    "MOBILE": "Kỹ sư Mobile", "DEVOPS": "Kỹ sư DevOps", "DATA_ML": "Kỹ sư Dữ liệu",
    "SECURITY": "Kỹ sư Bảo mật", "QA": "Kỹ sư Kiểm thử", "OTHER": "Chuyên viên Sản phẩm",
}

APPLICATION_MESSAGES = [
    "Mình đã làm một dự án tương tự năm ngoái, gửi kèm liên kết trong hồ sơ.",
    "Mình rảnh khoảng 10 giờ mỗi tuần và muốn học thêm mảng này.",
    "Đọc mô tả thấy đúng thứ mình đang tìm, mong được tham gia.",
    "Mình mạnh phần kiểm thử, có thể nhận luôn việc dựng CI cho dự án.",
    "Kinh nghiệm của mình hơi lệch so với mô tả nhưng rất muốn thử.",
]


def build_projects(rng, people):
    """50 dự án. tags rút từ ĐÚNG bảng DOMAINS mà hồ sơ nghề nghiệp dùng."""
    profiles = [p for p in people if p["has_profile"]]
    projects, positions, applications = [], [], []
    next_pos = 1
    next_app = 1

    for i in range(PROJECT_COUNT):
        pid = PROJECT_ID_FIRST + i
        title_base, desc = PROJECT_IDEAS[i % len(PROJECT_IDEAS)]
        owner = profiles[(i * 13) % len(profiles)]

        # 2-3 chủ đề, LẤY TỪ DOMAINS — cùng bảng mà interested_domains của hồ sơ dùng. Đây là chỗ
        # phép giao của ProfileMatchScorer.skillOverlap xảy ra. Rút từ một kho từ khác thì giao
        # luôn rỗng và /projects/suggested trả mảng trống, endpoint vẫn 200, không có gì báo lỗi.
        primary = owner["primary_role"]
        pool = list(DOMAINS[primary])
        extra_role = rng.choice([r for r in DOMAINS if r != primary])
        pool.append(rng.choice(DOMAINS[extra_role]))
        tags = pool[:rng.choice([2, 3, 3])]

        # ProjectStatus chỉ có OPEN và CLOSED. Không có 'IN_PROGRESS' — một dự án đang chạy mà
        # không tuyển nữa thì đúng nghĩa là CLOSED, và trạng thái "đang làm" đọc ra từ chỗ khác.
        # Cột là varchar không CHECK nên nhãn thứ ba lọt qua Flyway rồi nổ lúc Hibernate đọc.
        status = "CLOSED" if i % 9 == 8 else "OPEN"

        projects.append({
            "id": pid, "author_id": owner["id"],
            "title": title_base, "description": desc,
            "tags": tags, "status": status,
            "age": rng.randint(10, 450),
        })

        # 2-3 vị trí tuyển cho mỗi dự án.
        for _ in range(rng.choice([2, 3, 3])):
            role = rng.choice(list(DOMAINS))
            # required_skills cũng rút từ hai bảng dùng chung: TECH_STACK và DOMAINS. Hồ sơ mang
            # known_tech_stack theo TECH_STACK, nên hai bên giao được nhau.
            skills = rng.sample(TECH_STACK[role], 3) + [rng.choice(DOMAINS[role])]
            positions.append({
                "id": next_pos, "project_id": pid,
                "title": POSITION_TITLES[role],
                "description": f"Tham gia phần {POSITION_TITLES[role].lower()} của dự án.",
                "skills": skills,
                "quantity": rng.choice([1, 1, 2]),
                "status": "OPEN" if status == "OPEN" else "CLOSED",
                "age": projects[-1]["age"],
            })
            next_pos += 1

    # Đơn ứng tuyển: chỉ nộp vào vị trí của dự án đang mở, và không ai nộp vào dự án của chính mình.
    open_positions = [p for p in positions if p["status"] == "OPEN"]
    owner_of = {p["id"]: p["author_id"] for p in projects}
    seen_app = set()
    while len(applications) < 420:
        pos = rng.choice(open_positions)
        who = rng.choice(profiles)["id"]
        if who == owner_of[pos["project_id"]] or (pos["id"], who) in seen_app:
            continue
        seen_app.add((pos["id"], who))
        applications.append({
            "id": next_app, "project_id": pos["project_id"], "position_id": pos["id"],
            "applicant_id": who,
            "message": APPLICATION_MESSAGES[next_app % len(APPLICATION_MESSAGES)],
            "status": rng.choices(["PENDING", "ACCEPTED", "REJECTED"], weights=[45, 30, 25])[0],
            "age": rng.randint(1, 200),
        })
        next_app += 1

    # ── Ba fixture bắt buộc ───────────────────────────────────────────────────────────────────
    # 1. Một dự án OPEN mà tags GIAO ĐƯỢC với hồ sơ của 9001, để /projects/suggested chắc chắn có
    #    kết quả trên sân khấu.
    expert = next(p for p in people if p["id"] == DEMO_EXPERT)
    guaranteed = next(p for p in projects if p["status"] == "OPEN")
    guaranteed["tags"] = list(DOMAINS[expert["primary_role"]])[:2]
    # 2. Một dự án CLOSED CÓ tags — nó phải bị lọc ra theo status, và đó chính là thứ cần kiểm.
    closed = next(p for p in projects if p["status"] == "CLOSED")
    closed["tags"] = list(DOMAINS[expert["primary_role"]])[:2]
    # 3. Một dự án tags NULL — điểm chủ đề bằng 0, và không được làm nổ phép giao.
    next(p for p in reversed(projects) if p["status"] == "OPEN")["tags"] = None

    return projects, positions, applications


def emit_projects(projects, positions, applications):
    f = SqlFile(87, "seed_projects",
                f"{len(projects)} dự án, {len(positions)} vị trí tuyển, {len(applications)} đơn ứng tuyển.")
    f.note("""
FILE NÀY SINH TỰ ĐỘNG bởi scripts/seed/generate_seed.py — sửa tay sẽ bị ghi đè.

BỐN CỘT jsonb PHẢI DÙNG CHUNG MỘT BỘ TỪ VỰNG, và đây là cái bẫy nguy hiểm nhất của cả bộ seed:

    t_projects.tags                              (file này)
    t_project_positions.required_skills          (file này)
    t_user_professional_profiles.interested_domains   (V81)
    t_user_professional_profiles.known_tech_stack     (V81)

MatchmakingService cùng ProfileMatchScorer.skillOverlap so bốn cột này bằng phép GIAO. Sinh mỗi
cột từ một kho từ riêng thì giao luôn rỗng, GET /v1/api/projects/suggested và
/projects/{id}/candidates trả mảng trống — và KHÔNG CÓ GÌ BÁO LỖI, endpoint vẫn trả 200. Vì vậy cả
bốn cột đều rút từ hai bảng hằng DOMAINS và TECH_STACK trong generator.

t_projects dùng SERIAL chứ không phải sequence q_*, nên phần setval ở cuối file gọi qua
pg_get_serial_sequence. Bỏ sót bước đó thì lỗi khoá trùng nổ ở LẦN TẠO DỰ ÁN ĐẦU TIÊN của một
người dùng thật, không nổ lúc seed.

Ba fixture bắt buộc: một dự án OPEN có tags giao được với hồ sơ của 9001; một dự án CLOSED CÓ tags
(phải bị lọc ra theo status); một dự án tags NULL (điểm chủ đề 0, không được làm nổ phép giao).
""")
    f.rule()

    rows = [
        f"    ({p['id']}, {p['author_id']}, {q(p['title'])}, {q(p['description'])}, NULL, "
        f"{q(p['status'])}, {jsonb(p['tags']) if p['tags'] else 'NULL'}, "
        f"now() - INTERVAL '{p['age']} days', now() - INTERVAL '{p['age']} days')"
        for p in projects
    ]
    f.sql(
        "INSERT INTO socialapp.t_projects\n"
        "    (id, author_id, title, description, banner_url, status, tags, created_at, updated_at)"
        " VALUES\n" + ",\n".join(rows) + ";",
        rows=len(rows),
    )
    f.note("banner_url để NULL: không có object nào được nạp cho nó, và một URL trỏ vào chỗ trống "
           "thì tệ hơn NULL — trình duyệt hiện ảnh vỡ thay vì rơi về nền mặc định.")

    rows = [
        f"    ({p['id']}, {p['project_id']}, {q(p['title'])}, {q(p['description'])}, "
        f"{jsonb(p['skills'])}, {p['quantity']}, {q(p['status'])}, "
        f"now() - INTERVAL '{p['age']} days', now() - INTERVAL '{p['age']} days')"
        for p in positions
    ]
    f.sql(
        "INSERT INTO socialapp.t_project_positions\n"
        "    (id, project_id, title, description, required_skills, quantity, status,\n"
        "     created_at, updated_at) VALUES\n" + ",\n".join(rows) + ";",
        rows=len(rows),
    )

    f.note("Đơn ứng tuyển chỉ nộp vào vị trí của dự án đang mở, và không ai nộp vào dự án của mình.")
    rows = [
        f"    ({a['id']}, {a['project_id']}, {a['position_id']}, {a['applicant_id']}, "
        f"{q(a['message'])}, {q(a['status'])}, now() - INTERVAL '{a['age']} days', "
        f"now() - INTERVAL '{a['age']} days')"
        for a in applications
    ]
    f.sql(
        "INSERT INTO socialapp.t_project_applications\n"
        "    (id, project_id, position_id, applicant_id, message, status, created_at, updated_at)"
        " VALUES\n" + ",\n".join(rows) + ";",
        rows=len(rows),
    )

    f.note("""
Ba bảng này dùng SERIAL, nên sequence của chúng lấy qua pg_get_serial_sequence chứ không phải tên
q_* như các bảng khác. Đây là chỗ dễ bỏ sót nhất trong cả bộ seed: lỗi không nổ lúc migrate mà nổ ở
lần tạo dự án đầu tiên của người dùng thật.
""")
    f.sql("""
SELECT setval(pg_get_serial_sequence('socialapp.t_projects', 'id'),
              GREATEST((SELECT COALESCE(MAX(id), 0) FROM socialapp.t_projects), 1), true);
SELECT setval(pg_get_serial_sequence('socialapp.t_project_positions', 'id'),
              GREATEST((SELECT COALESCE(MAX(id), 0) FROM socialapp.t_project_positions), 1), true);
SELECT setval(pg_get_serial_sequence('socialapp.t_project_applications', 'id'),
              GREATEST((SELECT COALESCE(MAX(id), 0) FROM socialapp.t_project_applications), 1), true);""")
    return f


# ═══ V88 — lộ trình học ════════════════════════════════════════════════════════════════════════
#
# TOÀN BỘ NỘI DUNG DƯỚI ĐÂY DO NHÓM TỰ VIẾT, KHÔNG CHÉP TỪ roadmap.sh.
#
# Giấy phép của kamranahmedse/developer-roadmap là "Other" / NOASSERTION và nguyên văn chỉ cho
# phép dùng cá nhân, cấm publish nội dung dưới mọi hình thức. Đây không phải giấy phép mã nguồn
# mở, và vi phạm ở đây nặng hơn bình thường vì ba lý do cộng dồn: bộ seed CHẠY TRÊN PRODUCTION,
# nội dung nằm CÔNG KHAI TRONG REPO, và đây là ĐỒ ÁN SẼ ĐƯỢC CHẤM.
#
# Bản quyền bảo vệ cách diễn đạt chứ không bảo vệ sự kiện: "lộ trình backend gồm HTTP, cơ sở dữ
# liệu, caching, hàng đợi" là kiến thức ngành phổ thông. Nên roadmap.sh chỉ được dùng để ĐỐI CHIẾU
# ĐỘ PHỦ CHỦ ĐỀ, còn tên nút, mô tả và thứ tự đều viết lại bằng tiếng Việt, bám chương trình học
# trong nước. Khi hội đồng hỏi lộ trình này ở đâu ra, "nhóm tự thiết kế" là câu trả lời tốt hơn
# hẳn "dịch từ roadmap.sh".
#
# CẤU TRÚC MỘT NÚT: ("tên", "mô tả") là nút gốc, ("tên", "mô tả", "tên nút cha") là nút con.
# Phần tử thứ ba tham chiếu nút cha BẰNG TÊN trong cùng lộ trình, và build_roadmaps tra tên đó
# trong các nút ĐÃ khai trước nó — nên nút cha phải nằm TRƯỚC nút con trong danh sách. Vi phạm
# thì KeyError ngay lúc sinh, chứ không âm thầm ra một cây thiếu nhánh.
#
# Vì sao tra theo tên chứ không theo chỉ số: chỉ số làm mọi lần chèn thêm một nút ở giữa lộ trình
# âm thầm đổi cha của các nút phía sau. Tên thì hoặc đúng, hoặc nổ.
#
# THỨ TỰ CÁC NÚT TRONG MỖI LỘ TRÌNH LÀ HỢP ĐỒNG, KHÔNG PHẢI THẨM MỸ: id nút được cấp tuần tự theo
# đúng thứ tự duyệt danh sách này, và V90 tính điểm uy tín theo `user_id:node_id`. Đảo hai dòng
# trong cùng một lộ trình là đổi id của cả hai. Thêm/bớt nút thì được, đảo chỗ thì không.
#
# Ngày 2026-09-01, mô tả từng nút được bổ sung một sự kiện THẬT tra từ tài liệu chính thức/chuẩn kỹ
# thuật ứng với đúng công nghệ của nút đó (paraphrase, không trích nguyên văn) — vẫn giữ đúng quy
# tắc ở trên: KHÔNG một chữ nào tham chiếu hay phái sinh từ roadmap.sh, chỉ thêm sự kiện độc lập.
# Tên nút và tên nút cha (phần tử thứ ba của tuple) không đổi trong lượt này.

ROADMAPS = [
    ("Backend cho người mới", "BACKEND",
     "Từ một endpoint chạy được tới một dịch vụ chịu được tải thật.", [
        ("Giao thức HTTP", "Phương thức, mã trạng thái, header. 401 là chưa xác thực được ai gửi request, 403 là biết rồi nhưng không đủ quyền — hai lỗi hay bị đánh đồng."),
        ("Một ngôn ngữ máy chủ", "Chọn một và đi sâu: Java, Go, Python hay Node đều được — khảo sát nhà phát triển thường niên vẫn xếp cả bốn vào nhóm ngôn ngữ dùng nhiều nhất, không có lựa chọn sai."),
        ("Cơ sở dữ liệu quan hệ", "Bảng, khoá, ràng buộc. Viết được truy vấn có JOIN mà không đoán — và biết vì sao khoá ngoại luôn cần một index trên cột bị tham chiếu."),
        ("Thiết kế API", "Đặt tên tài nguyên, xử lý lỗi nhất quán, và phân trang kiểu cursor — trỏ tới bản ghi cuối thay vì đếm OFFSET, để không lặp hay bỏ sót dòng khi dữ liệu đang đổi.", "Giao thức HTTP"),
        ("ORM và cái giá của nó", "Hiểu N+1 sinh ra từ đâu, và vì sao JOIN FETCH gộp cha con vào một câu SQL cũng có giá riêng: tập kết quả phình to khi collection lớn, không phải liều thuốc chung.", "Cơ sở dữ liệu quan hệ"),
        ("Giao dịch", "Bốn tính chất ACID, mức cô lập từ Read Committed mặc định tới Serializable nghiêm ngặt nhất, và chuyện gọi nội bộ làm mất giao dịch.", "Cơ sở dữ liệu quan hệ"),
        ("Đánh index", "Thứ tự cột, tiền tố trái, partial index. Đọc được query plan — biết index tổ hợp chỉ thu hẹp vùng quét tới cột đầu tiên có điều kiện khoảng, cột sau đó chỉ lọc thêm.", "Cơ sở dữ liệu quan hệ"),
        ("Caching", "Chọn TTL, xử lý cache stampede bằng stale-while-revalidate — trả ngay bản cũ trong lúc âm thầm làm mới nền — và vấn đề khó nhất: vô hiệu hoá cache."),
        ("Hàng đợi và việc nền", "Tách việc chạy lâu khỏi request. Hàng đợi tiêu chuẩn chỉ cam kết giao ít nhất một lần — đôi khi giao trùng — nên phần xử lý luôn phải tự idempotent."),
        ("Ghi log và đo đạc", "Mã theo dõi xuyên suốt lan truyền qua header HTTP theo chuẩn W3C Trace Context, đo p99 thay vì trung bình."),
        ("Kiểm thử", "Test tích hợp trên đúng cơ sở dữ liệu mà production dùng — dựng bằng container thật cho mỗi lượt chạy rồi huỷ ngay, không dính trạng thái cũ."),
        ("Triển khai", "Biến môi trường tách khỏi mã nguồn theo nguyên tắc 12-factor để cùng một bản build chạy mọi môi trường, cộng migration và cách quay lui khi hỏng."),
     ]),
    ("Frontend hiện đại", "FRONTEND",
     "Từ HTML tĩnh tới giao diện chịu được dữ liệu thật và người dùng thật.", [
        ("HTML ngữ nghĩa", "Dùng đúng thẻ để trình đọc màn hình tạo được landmark điều hướng theo vùng — đây cũng là bước đầu của khả năng truy cập."),
        ("CSS bố cục", "Flexbox và Grid. Đặt trước kích thước hoặc aspect-ratio để trình duyệt chừa đúng chỗ, hiểu vì sao thiếu nó gây layout shift làm người đọc mất chỗ."),
        ("JavaScript nền tảng", "Bất đồng bộ, closure, module. Promise chạy trong microtask queue, luôn được xử lý hết trước khi event loop chuyển sang setTimeout dù cùng đặt độ trễ 0 — trước khi học framework."),
        ("TypeScript", "Kiểu là tài liệu chạy được, bắt lỗi lúc biên dịch trước khi người dùng bắt — nhưng type bị xoá sạch khỏi JavaScript sinh ra, nên an toàn kiểu dừng lại ở biên dịch, không kéo tới runtime.", "JavaScript nền tảng"),
        ("Một framework", "React, Vue hay Svelte. Hiểu vòng đời hai pha của nó: dựng cây so sánh trước, rồi mới ghi thật vào DOM đúng phần đã đổi.", "JavaScript nền tảng"),
        ("Quản lý trạng thái", "Phân biệt trạng thái máy chủ — có thể đổi mà app không hay, vì một người khác vừa sửa — với trạng thái giao diện luôn đồng bộ với app.", "Một framework"),
        ("Gọi dữ liệu", "Trạng thái tải, lỗi, rỗng — thư viện quản lý trạng thái máy chủ tách rõ ba trạng thái này thay vì gộp vào một cờ loading duy nhất. Đây là ba trạng thái hay bị quên nhất.", "Một framework"),
        ("Khả năng truy cập", "Dùng được bằng bàn phím theo đúng thứ tự Tab có nghĩa (WCAG Focus Order), đọc được bằng trình đọc màn hình.", "HTML ngữ nghĩa"),
        ("Hiệu năng web", "Chia gói theo route để chỉ gửi đúng phần JavaScript của trang đang xem, tải ảnh đúng kích thước, đo bằng số thật."),
        ("Kiểm thử giao diện", "Test theo hành vi người dùng — tìm phần tử qua label, role, text như người dùng thật thấy — không theo chi tiết cài đặt bên trong, nên đổi cấu trúc component không làm test vỡ.", "Một framework"),
        ("Dựng và đóng gói", "Hiểu công cụ đang làm gì với mã của bạn: phục vụ qua module gốc để hot reload tức thì lúc code, rồi mới gộp và nén lại khi build production."),
     ]),
    ("DevOps thực dụng", "DEVOPS",
     "Đưa phần mềm ra môi trường thật và giữ nó sống.", [
        ("Dòng lệnh Linux", "Tệp, tiến trình, quyền chia ba nhóm chủ sở hữu/nhóm/người khác với ba bit đọc-ghi-thực thi mỗi nhóm. Đọc được log mà không cần giao diện."),
        ("Quản lý phiên bản", "Nhánh, gộp — fast-forward khi nhánh đích chưa đổi gì thêm, ba-way merge khi hai lịch sử đã phân nhánh — và cách viết lịch sử mà người sau đọc được."),
        ("Container", "Image khác container: container chỉ thêm đúng một lớp ghi được lên trên các lớp chỉ đọc của image, nên nhiều container cùng image vẫn dùng chung phần lớn dữ liệu."),
        ("Tích hợp liên tục", "Chạy test tự động. Build đỏ không phải vấn đề — theo đúng thực hành CI, không sửa ngay mới là vấn đề, vì nhánh chính hỏng chặn mọi người khác.", "Quản lý phiên bản"),
        ("Triển khai liên tục", "Ra bản mới thường xuyên và nhỏ. Feature flag tách deploy khỏi release — code nằm sẵn ở trạng thái tắt, bật dần rồi tắt lại tức thì nếu hỏng, không cần rollback.", "Tích hợp liên tục"),
        ("Hạ tầng dưới dạng mã", "Mô tả hạ tầng bằng tệp khai báo trạng thái đích mong muốn — không phải từng bước thao tác tay — và để công cụ tự tính kế hoạch đạt tới đó."),
        ("Điều phối container", "Khi nào cần và khi nào chưa cần Kubernetes: vài container một máy thì chưa cần, nhưng hàng chục container trải nhiều máy đòi tự phục hồi và tự co giãn thì có.", "Container"),
        ("Giám sát", "Chỉ số, log, vết. Công cụ giám sát kiểu pull chủ động ghé lấy số liệu theo chu kỳ, không chờ dịch vụ tự đẩy lên. Biết dịch vụ hỏng trước khi người dùng báo."),
        ("Cảnh báo", "Cảnh báo phải có một hành động rõ ràng người trực làm ngay được — sách SRE gọi cảnh báo không hành động được là nhiễu — nếu không nó sẽ bị tắt tiếng.", "Giám sát"),
        ("Sao lưu và khôi phục", "Quy tắc 3-2-1: ba bản sao, hai loại lưu trữ khác nhau, một bản ở nơi khác — nhưng bản sao lưu chưa từng khôi phục thử thì chưa phải bản sao lưu."),
        ("Chi phí", "Đọc hoá đơn, tìm chỗ trả tiền cho thứ không ai dùng — phần lớn nằm ở tài nguyên đã ngừng dùng nhưng quên xoá, như ổ đĩa rời hay IP tĩnh không gắn máy nào."),
     ]),
    ("Dữ liệu và học máy", "DATA_ML",
     "Từ tệp CSV tới mô hình chạy trong sản xuất.", [
        ("Python cho dữ liệu", "Thao tác bảng dữ liệu bằng DataFrame hai chiều — lọc, gộp nhóm, join ngay trên đó — thành thạo trước khi nói tới mô hình."),
        ("Thống kê nền tảng", "Phân phối, tương quan, và vì sao tương quan không phải nhân quả — hai đại lượng có thể chỉ cùng chịu một biến ẩn thứ ba, không cái nào gây ra cái nào."),
        ("Làm sạch dữ liệu", "Phần lớn thời gian nằm ở đây — nhiều khảo sát ngành liên tục cho thấy trên một phần ba thời gian người làm dữ liệu dành để dọn dữ liệu — không phải ở chỗ chọn thuật toán.", "Python cho dữ liệu"),
        ("Trực quan hoá", "Biểu đồ để hiểu, không phải để trang trí báo cáo — phần trang trí không mang thông tin từng bị gọi thẳng là 'rác trực quan' vì nó chỉ làm khó đọc thêm.", "Python cho dữ liệu"),
        ("Học có giám sát", "Hồi quy và phân loại. Hiểu rò rỉ dữ liệu — xảy ra khi thông tin từ tập test lọt vào lúc huấn luyện, nên bước biến đổi dữ liệu luôn phải fit trên tập train rồi mới áp dụng sang tập test.", "Thống kê nền tảng"),
        ("Đánh giá mô hình", "Chọn chỉ số hợp bài toán. Độ chính xác thường là chỉ số tệ — trên dữ liệu lệch lớp, một mô hình đoán bừa toàn lớp đông nhất vẫn đạt độ chính xác rất cao.", "Học có giám sát"),
        ("Kỹ thuật đặc trưng", "Đặc trưng tốt — biến đổi từ dữ liệu thô thành dạng mô hình học được — thắng mô hình phức tạp, gần như luôn luôn.", "Làm sạch dữ liệu"),
        ("Đường ống dữ liệu", "Từ notebook sang quy trình chạy lại được — đúng cùng một đoạn mã chạy ở cả môi trường thử lẫn production, không viết lại hai lần."),
        ("Đưa mô hình lên sản xuất", "Phiên bản, theo dõi trôi dữ liệu — khi phân phối dữ liệu thật lệch dần khỏi lúc huấn luyện, mô hình mất độ chính xác dù code không đổi — và cách quay lui.", "Đường ống dữ liệu"),
        ("Đạo đức dữ liệu", "Thiên lệch có sẵn trong dữ liệu huấn luyện thường bị mô hình khuếch đại thêm, chứ không chỉ phản ánh trung lập, rồi thành thiên lệch trong quyết định."),
     ]),
    ("An toàn ứng dụng", "SECURITY",
     "Nghĩ như người tấn công để viết mã như người phòng thủ.", [
        ("Mô hình hoá mối đe doạ", "Ai muốn gì, và họ vào bằng đường nào — mô hình STRIDE chia mối đe doạ thành sáu nhóm để rà từng phần hệ thống theo đúng khung đó."),
        ("Xác thực", "Mật khẩu băm bằng thuật toán cố tình chậm như bcrypt hay Argon2id kèm salt riêng từng mật khẩu, phiên hết hạn, và chống dò."),
        ("Phân quyền", "Kiểm ở máy chủ, không tin id hay quyền mà client tự gửi lên. Ẩn nút trên giao diện không phải phân quyền — đây vẫn là nhóm lỗi phổ biến nhất theo OWASP nhiều năm liền.", "Xác thực"),
        ("Mười rủi ro phổ biến", "Danh sách OWASP xếp kiểm soát truy cập, lỗi mật mã và chèn mã ở nhóm đầu, có cả mục về toàn vẹn phần mềm và chuỗi cung ứng — đọc lại hằng năm vì nó đổi."),
        ("Chèn mã", "SQL, lệnh hệ thống, mẫu. Câu lệnh tham số hoá khai báo trọn SQL trước, giá trị người dùng chỉ truyền vào sau như dữ liệu — cơ sở dữ liệu không lẫn nó với mã lệnh.", "Mười rủi ro phổ biến"),
        ("Bí mật và khoá", "Không nằm trong repo, đi qua công cụ quản lý bí mật tập trung có mã hoá và nhật ký ai lấy khoá nào lúc nào. Xoay vòng được khi lộ."),
        ("Phụ thuộc bên thứ ba", "Chuỗi cung ứng là đường vào mà ít người canh — một cửa hậu từng bị cài thẳng vào một thư viện nén dùng rộng rãi trong Linux qua nhiều năm gây dựng danh tính giả để chiếm quyền maintainer, chỉ lộ ra vì một kỹ sư tình cờ để ý CPU dùng bất thường."),
        ("Ghi nhật ký an toàn", "Ghi đủ để điều tra — đăng nhập thất bại, hành vi có quyền cao — nhưng không bao giờ ghi thẳng mật khẩu, khoá mã hay số thẻ thanh toán vào log."),
        ("Xử lý sự cố", "Biết trước sẽ gọi ai và làm gì — quy trình chuẩn chia bốn giai đoạn, và giai đoạn chuẩn bị luôn nằm trước khi có bất kỳ sự cố nào — trước khi cần đến."),
     ]),
    ("Kiểm thử và chất lượng", "QA",
     "Xây lưới an toàn để đội dám thay đổi mã.", [
        ("Kim tự tháp kiểm thử", "Nhiều test nhỏ chạy nhanh ở tầng đáy, rất ít test chạy qua giao diện ở tầng đỉnh vì chúng chậm và dễ vỡ. Ngược lại thì chậm và giòn."),
        ("Test đơn vị", "Nhanh, độc lập, không phụ thuộc thứ tự chạy — chiếm phần lớn nhất trong tổng số test vì chi phí chạy và bảo trì rẻ nhất.", "Kim tự tháp kiểm thử"),
        ("Test tích hợp", "Chạy trên đúng cơ sở dữ liệu mà production dùng — dựng bằng container thật cho mỗi lượt rồi huỷ ngay, không dính trạng thái để lại từ lượt trước.", "Kim tự tháp kiểm thử"),
        ("Test đầu cuối", "Ít thôi vì đây là tầng chậm và dễ vỡ nhất trong ba tầng, và bám hành vi người dùng thay vì chi tiết bên trong.", "Kim tự tháp kiểm thử"),
        ("Dữ liệu kiểm thử", "Dựng và dọn sạch sẽ — mỗi lượt test chạy trong một môi trường cô lập riêng, không dùng chung với lượt trước. Dữ liệu rớt lại làm test đỏ ngẫu nhiên.", "Test tích hợp"),
        ("Test giòn", "Nguyên nhân thường là chờ theo thời gian thay vì chờ theo điều kiện, hoặc phụ thuộc vào dịch vụ bên ngoài — cách xử lý phổ biến là tự động chạy lại rồi cách ly khỏi luồng CI quan trọng để không chặn cả đội.", "Test đầu cuối"),
        ("Độ phủ", "Hữu ích như tín hiệu, tai hại như mục tiêu — đặt hẳn một phần trăm làm chỉ tiêu khiến người viết test đối phó cho đủ số, thay vì hỏi liệu mình có dám refactor tự tin không."),
        ("Kiểm thử hiệu năng", "Đo dưới tải giống thật — tăng dần, đột biến, kéo dài — để phát hiện điểm nghẽn trước khi vi phạm SLO ngoài production, không phải trên máy cá nhân."),
        ("Văn hoá chất lượng", "Chất lượng là việc của cả đội ngay từ lúc thiết kế, không dồn hết trách nhiệm cho một vai kiểm thử ở cuối."),
     ]),
    ("Kỹ sư Mobile", "MOBILE",
     "Ứng dụng chạy tốt cả khi mạng chập chờn và pin sắp hết.", [
        ("Nền tảng và vòng đời", "Màn hình bị huỷ và dựng lại bất cứ lúc nào — hệ điều hành gọi onSaveInstanceState trước khi dừng một activity, nhưng khi tiến trình bị hệ thống giết hẳn thì không một hàm vòng đời nào được gọi nữa, chỉ còn lại đúng phần dữ liệu đã lưu trước đó."),
        ("Giao diện khai báo", "Compose hoặc SwiftUI. Trạng thái quyết định giao diện — nâng state lên component cha (state hoisting) giữ đúng một nguồn sự thật, và mỗi lần state đổi hệ thống chỉ recompose lại đúng phần giao diện đang đọc state đó, không vẽ lại toàn màn hình.", "Nền tảng và vòng đời"),
        ("Lưu trữ cục bộ", "Dữ liệu phải còn khi đóng ứng dụng."),
        ("Offline-first", "Ghi trước, đồng bộ sau, và giải quyết xung đột — cách đơn giản nhất là để bản ghi mới nhất thắng, cách bền hơn là dùng CRDT để hợp nhất thay đổi từ nhiều thiết bị mà không cần một máy chủ trọng tài phân xử.", "Lưu trữ cục bộ"),
        ("Gọi mạng", "Thử lại có giới hạn, và đừng thử lại thứ không idempotent — nên rắc thêm độ trễ ngẫu nhiên (jitter) vào khoảng chờ giữa các lần thử lại, nếu không toàn bộ client sẽ đồng loạt gọi lại cùng lúc và làm nghẽn chính máy chủ đang hồi phục."),
        ("Hiệu năng", "Cuộn mượt quan trọng hơn mọi hiệu ứng."),
        ("Kích thước gói cài", "Người dùng bỏ tải khi ứng dụng quá nặng — App Thinning trên iOS chỉ gửi đúng phần tài nguyên khớp với model máy đang cài thay vì một gói chung cho mọi thiết bị, có thể giảm dung lượng tải về tới 20-40%."),
        ("Phát hành", "Kênh thử nghiệm, phát hành theo tỉ lệ, và cờ tính năng — một bản cập nhật thường lên trước cho một phần nhỏ người dùng rồi tăng dần theo từng mốc phần trăm, và có thể dừng ngay lập tức nếu phát hiện lỗi trước khi lỡ chạm tới toàn bộ người dùng."),
     ]),
    ("Fullstack cân bằng", "OTHER",
     "Đủ sâu ở hai đầu để không phải chờ người khác.", [
        ("Nền tảng web", "Trình duyệt làm gì với một request — từ tra DNS ra địa chỉ IP, bắt tay ba bước TCP, gửi request HTTP, tới lúc nhận response và bắt đầu dựng trang, mỗi bước đều có thể là nơi một request chậm đi mà không do lỗi ở tầng ứng dụng."),
        ("Một ngôn ngữ hai đầu", "Giảm chi phí chuyển ngữ cảnh trong ngày làm việc — dùng chung JavaScript/TypeScript ở cả client và server cho phép chia sẻ thẳng một bộ type hay một hàm validate giữa hai đầu, thay vì viết và giữ đồng bộ hai bản riêng biệt."),
        ("Ranh giới máy chủ và máy khách", "Cái gì tính ở đâu, và vì sao — gọi đồng bộ giữa các phần giữ logic đơn giản nhưng kéo theo rủi ro lỗi dây chuyền khi một phía chậm, còn tách bất đồng bộ qua hàng đợi thì bền hơn nhưng cộng thêm độ trễ ở cả hai chiều.", "Nền tảng web"),
        ("Thiết kế dữ liệu", "Lược đồ quyết định phần lớn độ khó về sau."),
        ("Xác thực đầu cuối", "Từ ô đăng nhập tới phân quyền ở tầng dịch vụ — OAuth 2.0 chỉ trả lời được ứng dụng được phép làm gì, còn biết chính xác ai đang đăng nhập cần thêm lớp OpenID Connect nằm trên nó, hai chuẩn tách biệt nhưng gần như luôn dùng cùng nhau trong thực tế.", "Ranh giới máy chủ và máy khách"),
        ("Trải nghiệm lập trình viên", "Chạy được toàn bộ hệ thống bằng một lệnh — một lệnh compose dựng đủ web, API, database quen thuộc có thể rút thời gian dựng máy cho người mới từ vài giờ cài đặt thủ công xuống còn vài phút."),
        ("Triển khai một mình", "Biết đủ hạ tầng để tự đưa sản phẩm ra — các nền tảng PaaS như Render hay Fly.io cho một người tự deploy thẳng từ git push mà không phải tự quản lý máy chủ, đánh đổi lại là ít quyền tinh chỉnh hạ tầng hơn so với tự dựng."),
     ]),
    ("Nền tảng khoa học máy tính", "OTHER",
     "Những thứ không đổi khi framework đổi.", [
        ("Cấu trúc dữ liệu", "Mảng, bảng băm, cây, đồ thị. Biết chọn cái nào."),
        ("Độ phức tạp", "Ước lượng được trước khi đo — ký hiệu Big O mô tả tốc độ tăng của thời gian chạy theo kích thước dữ liệu đầu vào, bỏ qua hằng số, nên so sánh được hai thuật toán mà không cần chạy thử cả hai.", "Cấu trúc dữ liệu"),
        ("Giải thuật cơ bản", "Sắp xếp, tìm kiếm, duyệt đồ thị — thuật toán sắp xếp mặc định của Python và của Java từ bản 7 trở đi không phải quicksort thuần mà là Timsort, một thuật toán lai giữa merge sort và insertion sort được thiết kế riêng để tận dụng các đoạn đã có thứ tự sẵn trong dữ liệu thật.", "Cấu trúc dữ liệu"),
        ("Hệ điều hành", "Tiến trình, luồng, bộ nhớ, tệp — mỗi tiến trình có một vùng địa chỉ bộ nhớ riêng nên một tiến trình lỗi không thể ghi đè bộ nhớ của tiến trình khác, còn các luồng trong cùng một tiến trình dùng chung vùng địa chỉ đó nên giao tiếp nhanh hơn nhưng cũng dễ giẫm lên nhau hơn."),
        ("Mạng máy tính", "Vì sao một request chậm mà CPU vẫn rảnh — trong lúc chờ phản hồi từ mạng, CPU không có gì để làm nên chuyển sang trạng thái rảnh; một request chờ 100ms lẽ ra trong quãng đó CPU có thể thực thi tới hàng trăm triệu lệnh nếu không phải chờ."),
        ("Hệ phân tán", "Đánh đổi giữa nhất quán và sẵn sàng — định lý CAP nói rằng khi xảy ra chia cắt mạng, một hệ phân tán chỉ có thể giữ nhất quán dữ liệu hoặc tiếp tục phục vụ request, không thể giữ cả hai cùng lúc.", "Mạng máy tính"),
     ]),
    ("Phát triển sự nghiệp", "CAREER",
     "Kỹ năng quyết định bạn đi được bao xa, không phải bao nhanh.", [
        ("Viết rõ ràng", "Tài liệu và tin nhắn là công cụ làm việc chính của kỹ sư."),
        ("Nhận và cho phản hồi", "Tách con người khỏi đoạn mã — nguyên tắc cốt lõi của mô hình Radical Candor là phê bình hành vi hay công việc cụ thể, không phê bình con người, trong lúc vẫn thể hiện rõ mình quan tâm tới người nhận phản hồi."),
        ("Ước lượng", "Nói được mức không chắc chắn thay vì một con số giả vờ chắc — ở giai đoạn mới hình thành ý tưởng, một ước lượng có thể sai lệch tới bốn lần theo cả hai hướng, và độ không chắc chắn đó chỉ thu hẹp đáng kể sau khi đã làm rõ được khoảng 20-30% khối lượng công việc."),
        ("Làm việc nhóm", "Đồng bộ ít, tin nhau nhiều — một nghiên cứu kéo dài hai năm trên 180 đội của Google từng kết luận yếu tố phân biệt rõ nhất một đội hiệu quả không phải là ai giỏi nhất trong đội, mà là mức độ an toàn tâm lý: người trong đội dám nói sai, dám hỏi, mà không sợ bị đánh giá.", "Nhận và cho phản hồi"),
        ("Phỏng vấn", "Cả hai phía đều đang đánh giá lẫn nhau — ứng viên chủ động hỏi ngược lại về đội và cách làm việc không chỉ để lấy thông tin mà bản thân việc đặt câu hỏi tốt cũng là một tín hiệu nhà tuyển dụng thường để ý."),
        ("Dẫn dắt kỹ thuật", "Ra quyết định và chịu trách nhiệm về nó — ghi lại quyết định theo mẫu do Michael Nygard đề xuất năm 2011 (bối cảnh, quyết định, hệ quả) giúp người dẫn dắt để lại một dấu vết lý do rõ ràng thay vì chỉ để lại kết quả cuối cùng.", "Làm việc nhóm"),
        ("Học liên tục", "Chọn thứ đáng học, bỏ qua thứ đang ồn ào — một kỹ sư dạng chữ T có một mảng chuyên sâu cộng với hiểu biết rộng ở nhiều mảng liên quan, nên khi một công nghệ chính mình theo đuổi hết thời vẫn còn nền để xoay sang hướng khác."),
     ]),
    ("Kiến trúc phần mềm", "BACKEND",
     "Ra quyết định lớn với ít thông tin, và ghi lại vì sao.", [
        ("Ghép lỏng và gắn kết", "Hai chỉ số cũ mà vẫn đúng — coupling và cohesion do Larry Constantine đưa ra từ cuối thập niên 1960 và công bố rộng năm 1974, tới nay vẫn là nền cho hàng trăm nghiên cứu và nhiều thước đo chất lượng mã nguồn khác."),
        ("Kiến trúc phân tầng", "Đơn giản, đủ dùng cho phần lớn hệ thống — tầng trình bày gọi tầng nghiệp vụ, tầng nghiệp vụ gọi tầng truy cập dữ liệu, mỗi tầng chỉ biết tầng ngay dưới mình nên đổi database về lý thuyết không đụng tới logic nghiệp vụ phía trên.", "Ghép lỏng và gắn kết"),
        ("Thiết kế theo miền", "Ngôn ngữ chung giữa kỹ sư và người dùng — Eric Evans gọi đó là ngôn ngữ phổ dụng (ubiquitous language), dùng chung một từ vựng giữa người làm nghiệp vụ và người viết code trong đúng một ranh giới ngữ cảnh (bounded context) để tránh cùng một từ mang hai nghĩa khác nhau ở hai nơi."),
        ("Khi nào tách dịch vụ", "Câu trả lời thường là chưa — Martin Fowler quan sát thấy các đội thành công thường bắt đầu từ một monolith rồi mới tách dần khi nó thực sự trở thành vấn đề, còn các đội tách microservices ngay từ đầu dự án phần nhiều gặp khó khăn.", "Kiến trúc phân tầng"),
        ("Giao tiếp giữa dịch vụ", "Đồng bộ hay bất đồng bộ, và cái giá của mỗi lựa chọn — gọi đồng bộ dễ theo dõi nhưng một dịch vụ ở giữa chuỗi gọi bị chậm sẽ kéo chậm toàn bộ chuỗi, còn đưa qua hàng đợi bất đồng bộ chịu lỗi tốt hơn nhưng cộng thêm độ trễ qua hai lượt hàng đợi ở cả hai chiều.", "Khi nào tách dịch vụ"),
        ("Ghi lại quyết định", "Một trang cho mỗi quyết định lớn, kèm phương án đã loại — mẫu ghi quyết định kiến trúc (ADR) Michael Nygard đề xuất năm 2011 chỉ gồm vài mục: bối cảnh, quyết định, hệ quả, đủ ngắn để thực sự được viết trong lúc làm việc."),
        ("Tiến hoá hệ thống", "Đổi dần, không viết lại — kiến trúc tiến hoá dùng các 'hàm thích nghi' (fitness function) là những phép kiểm, chỉ số hay cơ chế giám sát chạy liên tục để xác nhận một đặc tính kiến trúc quan trọng vẫn được giữ đúng sau mỗi lần thay đổi."),
     ]),
    ("Sản phẩm cho kỹ sư", "OTHER",
     "Hiểu vì sao mình đang xây thứ này.", [
        ("Phát hiện vấn đề", "Người dùng nói triệu chứng, không nói nguyên nhân — kỹ thuật 5 Whys của Toyota hỏi liên tiếp 'vì sao' cho tới khi chạm được nguyên nhân gốc mang tính hệ thống, thay vì dừng lại ở lớp triệu chứng đầu tiên rồi vá tạm."),
        ("Nghiên cứu người dùng", "Quan sát nhiều hơn hỏi — nguyên tắc đầu tiên Nielsen Norman Group nhấn mạnh là đừng thiết kế theo lời người dùng tự nói mình muốn gì, vì lời kể lại về hành vi tương lai vốn không đáng tin bằng việc quan sát trực tiếp họ thao tác.", "Phát hiện vấn đề"),
        ("Chỉ số", "Chọn chỉ số mà đội có thể tác động được — một North Star Metric tốt vừa phản ánh đúng giá trị cốt lõi sản phẩm mang lại cho người dùng, vừa phải là thứ đội ngũ thực sự có thể tác động qua các cải tiến của mình, không phải một con số chỉ nhìn lại quá khứ."),
        ("Phạm vi", "Cắt phạm vi là kỹ năng, không phải thất bại."),
        ("Thử nghiệm", "Bản nhỏ nhất trả lời được câu hỏi — vòng lặp build-measure-learn của Eric Ries dùng một sản phẩm khả dụng tối thiểu (MVP) để thu về lượng kiểm chứng thực tế lớn nhất với công sức bỏ ra ít nhất, rồi rút ngắn vòng lặp đó càng nhanh càng tốt.", "Phạm vi"),
        ("Tăng trưởng", "Giữ chân trước, mở rộng sau — mô hình 'thùng rò' ví người dùng mới đổ vào như nước đổ vào một thùng thủng đáy: đổ thêm bao nhiêu ở trên mà đáy vẫn rò thì tăng trưởng ròng vẫn phẳng, nên vá tỉ lệ giữ chân luôn phải đi trước khi đổ thêm ngân sách thu hút người dùng mới.", "Chỉ số"),
     ]),
]


def build_roadmaps(rng, people):
    users = [p["id"] for p in people if p["primary_role"]]
    admins = [p["id"] for p in people if not p["primary_role"]]

    roadmaps, nodes, progress = [], [], []
    next_node = 1
    for i, (name, category, desc, node_specs) in enumerate(ROADMAPS):
        rid = 2001 + i
        roadmaps.append({"id": rid, "name": name, "category": category, "description": desc,
                         "age": 500 - i * 8})
        node_by_name = {}
        for order, spec in enumerate(node_specs):
            node_name, node_desc = spec[0], spec[1]
            parent_id = None
            if len(spec) == 3:
                parent_id = node_by_name.get(spec[2])
                if parent_id is None:
                    # Không KeyError thầm lặng từ dict — tên nút sai chính tả phải nói rõ nút nào,
                    # ở lộ trình nào, đang trỏ vào đâu.
                    raise ValueError(
                        f"Nút '{node_name}' (lộ trình '{name}') khai cha '{spec[2]}' nhưng nút đó "
                        "chưa xuất hiện — nút cha phải nằm TRƯỚC nút con trong danh sách.")
            nodes.append({"id": next_node, "roadmap_id": rid, "name": node_name,
                          "description": node_desc, "parent": parent_id, "order": order})
            node_by_name[node_name] = next_node
            next_node += 1

    seen = set()
    for who in users:
        # Mỗi người theo 1-3 lộ trình, và chỉ đánh dấu xong phần đầu — người học thật bỏ dở nhiều
        # hơn là hoàn thành, và một lộ trình ai cũng xong 100% thì thanh tiến độ vô nghĩa.
        for rm in rng.sample(roadmaps, rng.choice([0, 1, 1, 2, 3])):
            rm_nodes = [n for n in nodes if n["roadmap_id"] == rm["id"]]
            done = rng.randint(1, max(1, int(len(rm_nodes) * 0.7)))
            for n in rm_nodes[:done]:
                if (who, n["id"]) in seen:
                    continue
                seen.add((who, n["id"]))
                tier = rng.choices(["SELF_VERIFIED", "MOD_VERIFIED", "QUIZ_VERIFIED"],
                                   weights=[70, 20, 10])[0]
                if tier == "SELF_VERIFIED":
                    status, verifier = "VERIFIED", None
                else:
                    # PENDING_APPROVAL rơi thẳng vào hàng đợi duyệt kỹ năng của quản trị viên, và
                    # hàng đợi đó phải DUYỆT HẾT ĐƯỢC trong một buổi demo. Ở tỉ lệ 30% thì nó ra
                    # hơn 220 yêu cầu — đúng nghĩa là một màn hình không ai bấm hết nổi, và người
                    # xem sẽ hiểu là hệ thống đang tồn đọng chứ không phải đang chạy tốt.
                    status = rng.choices(["VERIFIED", "PENDING_APPROVAL", "REJECTED"],
                                         weights=[86, 3, 11])[0]
                    verifier = rng.choice(admins) if status == "VERIFIED" else None
                progress.append({"user_id": who, "node_id": n["id"], "tier": tier,
                                 "status": status, "verifier": verifier,
                                 "age": rng.randint(1, 400)})
    return roadmaps, nodes, progress


def emit_roadmaps(roadmaps, nodes, progress):
    f = SqlFile(88, "seed_roadmaps",
                f"{len(roadmaps)} lộ trình, {len(nodes)} nút, {len(progress):,} bản ghi tiến độ.")
    f.note("""
FILE NÀY SINH TỰ ĐỘNG bởi scripts/seed/generate_seed.py — sửa tay sẽ bị ghi đè.

TOÀN BỘ NỘI DUNG LỘ TRÌNH DO NHÓM TỰ VIẾT, KHÔNG CHÉP TỪ roadmap.sh.
Giấy phép của kamranahmedse/developer-roadmap là "Other"/NOASSERTION, chỉ cho phép dùng cá nhân và
cấm publish nội dung dưới mọi hình thức — không phải giấy phép mã nguồn mở. Vi phạm ở đây nặng hơn
bình thường vì ba lý do cộng dồn: bộ seed chạy TRÊN PRODUCTION, nội dung nằm CÔNG KHAI TRONG REPO,
và đây là ĐỒ ÁN SẼ ĐƯỢC CHẤM. roadmap.sh chỉ được dùng để đối chiếu độ phủ chủ đề.

t_roadmaps và t_roadmap_nodes dùng SERIAL, nên setval ở cuối file gọi qua pg_get_serial_sequence.

CÂY NÚT: parent_node_id tự tham chiếu — nút gốc NULL, nút con trỏ tới id của nút cha CÙNG lộ
trình. Mọi hàng con đều nằm SAU hàng cha của nó trong câu INSERT, nên FK tự tham chiếu được thoả
mãn ngay trong một câu mà không cần hai lượt chèn. Không có ngẫu nhiên ở đây: chacon là nội dung
do nhóm thiết kế, ghi thẳng trong ROADMAPS ở generate_seed.py.

Tiến độ cố ý DANG DỞ: người học thật bỏ giữa chừng nhiều hơn hoàn thành, và một lộ trình ai cũng
xong 100% thì thanh tiến độ không còn gì để hiển thị. Tiến độ gắn trên MỌI cấp — khác seed cũ
(V58, chỉ nút lá), vì ở mô hình này nút gốc cũng là một kỹ năng học được, không chỉ là nhãn nhóm.
""")
    f.rule()

    rows = [
        f"    ({r['id']}, {q(r['name'])}, {q(r['description'])}, {q(r['category'])}, "
        f"now() - INTERVAL '{r['age']} days', now() - INTERVAL '{r['age']} days')"
        for r in roadmaps
    ]
    f.sql(
        "INSERT INTO socialapp.t_roadmaps (id, name, description, category, created_at, updated_at)"
        " VALUES\n" + ",\n".join(rows) + ";",
        rows=len(rows),
    )

    rows = [
        f"    ({n['id']}, {n['roadmap_id']}, {q(n['name'])}, {q(n['description'])}, "
        f"{str(n['parent']) if n['parent'] else 'NULL'}, "
        f"{n['order']}, now() - INTERVAL '400 days', now() - INTERVAL '400 days')"
        for n in nodes
    ]
    f.sql(
        "INSERT INTO socialapp.t_roadmap_nodes\n"
        "    (id, roadmap_id, name, description, parent_node_id, order_index, created_at, updated_at)"
        " VALUES\n" + ",\n".join(rows) + ";",
        rows=len(rows),
    )

    f.note("""
UNIQUE(user_id, node_id). verifier_id chỉ có khi bản ghi đã được duyệt — một bản ghi PENDING mà đã
có người duyệt là trạng thái luồng thật không tạo ra được.
""")
    rows = []
    for p in progress:
        when = f"now() - INTERVAL '{p['age']} days'"
        verifier = str(p["verifier"]) if p["verifier"] else "NULL"
        verified_at = when if p["verifier"] else "NULL"
        rows.append(
            f"    ({p['user_id']}, {p['node_id']}, {q(p['tier'])}, {q(p['status'])}, NULL, NULL, "
            f"{verifier}, {verified_at}, {when}, {when})"
        )
    f.sql(
        "INSERT INTO socialapp.t_user_roadmap_progress\n"
        "    (user_id, node_id, tier, status, proof_url, proof_image_key, verifier_id,\n"
        "     verified_at, created_at, updated_at) VALUES\n" + ",\n".join(rows) + ";",
        rows=len(rows),
    )

    f.sql("""
SELECT setval(pg_get_serial_sequence('socialapp.t_roadmaps', 'id'),
              GREATEST((SELECT COALESCE(MAX(id), 0) FROM socialapp.t_roadmaps), 1), true);
SELECT setval(pg_get_serial_sequence('socialapp.t_roadmap_nodes', 'id'),
              GREATEST((SELECT COALESCE(MAX(id), 0) FROM socialapp.t_roadmap_nodes), 1), true);
SELECT setval(pg_get_serial_sequence('socialapp.t_user_roadmap_progress', 'id'),
              GREATEST((SELECT COALESCE(MAX(id), 0) FROM socialapp.t_user_roadmap_progress), 1), true);""")
    return f


# ═══ V89 — kiểm duyệt ══════════════════════════════════════════════════════════════════════════

VIOLATION_TYPES = ["SPAM", "INSULT", "KEYWORD_BLACKLIST", "DUPLICATE_CONTENT", "HATE_SPEECH"]
SEVERITY_OF = {"SPAM": "LOW", "DUPLICATE_CONTENT": "LOW", "INSULT": "MEDIUM",
               "KEYWORD_BLACKLIST": "MEDIUM", "HATE_SPEECH": "HIGH"}
REPORT_REASONS = ["SPAM", "HARASSMENT", "HATE_SPEECH", "MISINFORMATION", "OTHER"]

VIOLATION_NOTES = {
    "SPAM": "Đăng lặp một liên kết quảng cáo trong nhiều bài liên tiếp.",
    "INSULT": "Dùng lời lẽ nặng nề với người bình luận khác trong một cuộc tranh luận kỹ thuật.",
    "KEYWORD_BLACKLIST": "Nội dung chứa từ khoá nằm trong danh sách chặn.",
    "DUPLICATE_CONTENT": "Đăng lại gần như nguyên văn một bài đã đăng trước đó.",
    "HATE_SPEECH": "Nội dung công kích một nhóm người.",
}
APPEAL_REASONS = [
    "Mình nghĩ đây là hiểu nhầm: liên kết đó là tài liệu của chính dự án mình, không phải quảng cáo.",
    "Câu đó mình trích lại lời người khác để phản biện, chứ không phải mình nói.",
    "Bài bị đánh trùng nhưng thực ra là bản cập nhật, mình đã ghi rõ ở đầu bài.",
    "Mình đã sửa nội dung ngay sau khi nhận cảnh báo, mong được xem xét lại.",
]


def build_moderation(rng, people, posts):
    users = [p["id"] for p in people if p["primary_role"]]
    admins = [p["id"] for p in people if not p["primary_role"]]
    author_of = {p["id"]: p["author_id"] for p in posts}

    flagged = [p for p in posts if p["moderation"] != "APPROVED"]
    approved = [p for p in posts if p["moderation"] == "APPROVED"]

    # Log kiểm duyệt: mọi bài bị gắn cờ đều có log, cộng một phần bài đã duyệt (máy chấm rồi cho
    # qua) — nếu chỉ bài hỏng mới có log thì màn hình nhật ký trông như một danh sách lỗi.
    logs = []
    for p in flagged:
        vt = VIOLATION_TYPES[p["id"] % len(VIOLATION_TYPES)]
        logs.append((p["id"], "PENDING_REVIEW" if p["moderation"] == "PENDING_REVIEW" else "REJECTED",
                     vt, round(rng.uniform(0.62, 0.97), 3), round(rng.uniform(0.80, 0.99), 3),
                     [vt], rng.randint(1, 300)))
    for p in rng.sample(approved, 560):
        logs.append((p["id"], "APPROVED", None, round(rng.uniform(0.01, 0.28), 3),
                     round(rng.uniform(0.95, 0.999), 3), [], rng.randint(1, 400)))

    # Vi phạm gắn với người, không với bài — id do BIGSERIAL cấp, nên phần khiếu nại phải tra
    # ngược qua post_id + user_id chứ không đoán số.
    violations = []
    for p in flagged:
        vt = VIOLATION_TYPES[p["id"] % len(VIOLATION_TYPES)]
        violations.append((author_of[p["id"]], p["id"], vt, SEVERITY_OF[vt],
                           VIOLATION_NOTES[vt], rng.randint(1, 300)))
    # Thêm vi phạm không gắn bài, để nhánh post_id NULL có dữ liệu.
    for who in rng.sample(users, 40):
        vt = rng.choice(VIOLATION_TYPES)
        violations.append((who, None, vt, SEVERITY_OF[vt], VIOLATION_NOTES[vt],
                           rng.randint(1, 400)))

    # Cấm: chỉ với vi phạm nặng, và phần lớn đã hết hạn — một hàng đợi toàn người đang bị cấm
    # trông như hệ thống đang hỏng.
    bans = []
    for who, pid, vt, sev, note, age in violations:
        if sev == "HIGH" and rng.random() < 0.5:
            bans.append((who, pid, age, rng.choice([-30, -10, -3, 2, 7])))
    bans = bans[:25]

    reports = []
    seen_report = set()
    for p in rng.sample(posts, 40):
        for who in rng.sample(users, rng.randint(1, 3)):
            if who == author_of[p["id"]] or (p["id"], who) in seen_report:
                continue
            seen_report.add((p["id"], who))
            reports.append((p["id"], who, rng.choice(REPORT_REASONS),
                            "Mình thấy nội dung này không phù hợp với cộng đồng.",
                            rng.randint(1, 200)))

    return logs, violations, bans, reports, admins


def emit_moderation(logs, violations, bans, reports, admins):
    f = SqlFile(89, "seed_moderation",
                f"{len(logs)} log kiểm duyệt, {len(violations)} vi phạm, {len(bans)} lệnh cấm, "
                f"{len(reports)} báo cáo và các khiếu nại.")
    f.note("""
FILE NÀY SINH TỰ ĐỘNG bởi scripts/seed/generate_seed.py — sửa tay sẽ bị ghi đè.

Hàng đợi của quản trị viên phải CÓ VIỆC nhưng DUYỆT HẾT ĐƯỢC trong một buổi demo — nên số lượng ở
đây cố ý nhỏ so với phần còn lại của bộ seed.

Nội dung vi phạm ở mức nhẹ và được đánh dấu rõ: spam, quảng cáo, trùng lặp, cãi vã gắt giọng.
Không có nội dung thật sự độc hại nào trong repo này.

Log kiểm duyệt có cả bài ĐÃ DUYỆT: nếu chỉ bài hỏng mới có log thì màn hình nhật ký trông như một
danh sách lỗi chứ không phải nhật ký của một hệ thống đang chạy bình thường.

t_user_violations và t_moderation_appeals dùng BIGSERIAL, nên khiếu nại tra ngược violation_id qua
(user_id, post_id) thay vì đoán số — đoán số là cách nhanh nhất để khiếu nại gắn nhầm vi phạm.
""")
    f.rule()

    rows = [
        f"    ({pid}, {q(status)}, {q(vt) if vt else 'NULL'}, {tox}, {img}, {jsonb(rv)}, "
        f"now() - INTERVAL '{age} days', now() - INTERVAL '{age} days')"
        for pid, status, vt, tox, img, rv, age in logs
    ]
    f.sql(
        "INSERT INTO socialapp.t_moderation_logs\n"
        "    (post_id, status, violation_type, text_toxicity_score, image_safe_score,\n"
        "     rule_violations, reviewed_at, created_at) VALUES\n" + ",\n".join(rows) + ";",
        rows=len(rows),
    )

    f.note("Vi phạm — có cả loại gắn với một bài và loại không gắn bài nào (post_id NULL).")
    rows = [
        f"    ({who}, {pid if pid else 'NULL'}, {q(vt)}, {q(sev)}, {q(note)}, "
        f"now() - INTERVAL '{age} days')"
        for who, pid, vt, sev, note, age in violations
    ]
    f.sql(
        "INSERT INTO socialapp.t_user_violations\n"
        "    (user_id, post_id, violation_type, severity, description, created_at) VALUES\n"
        + ",\n".join(rows) + ";",
        rows=len(rows),
    )

    f.note("""
Lệnh cấm — phần lớn ĐÃ HẾT HẠN (banned_until nằm trong quá khứ). Một hàng đợi toàn người đang bị
cấm trông như hệ thống đang hỏng, và cũng khiến người demo không đăng nhập được bằng tài khoản bất
kỳ. banned_until trên t_users chỉ đặt cho những lệnh cấm CÒN hiệu lực.
""")
    rows = [
        f"    ({who}, {pid if pid else 'NULL'}, now() + INTERVAL '{days} days', "
        f"now() - INTERVAL '{age} days')"
        for who, pid, age, days in bans
    ]
    f.sql(
        "INSERT INTO socialapp.t_user_bans (user_id, post_id, banned_until, created_at) VALUES\n"
        + ",\n".join(rows) + ";",
        rows=len(rows),
    )
    f.sql("""
UPDATE socialapp.t_users u
   SET banned_until = b.until
  FROM (SELECT user_id, MAX(banned_until) AS until
          FROM socialapp.t_user_bans
         WHERE banned_until > now()
         GROUP BY user_id) b
 WHERE b.user_id = u.id;""")

    f.note("Báo cáo bài viết — UNIQUE(post_id, reporter_id), và không ai tự báo cáo bài của mình.")
    rows = [
        f"    ({pid}, {who}, {q(reason)}, {q(detail)}, now() - INTERVAL '{age} days')"
        for pid, who, reason, detail, age in reports
    ]
    f.sql(
        "INSERT INTO socialapp.t_post_reports (post_id, reporter_id, reason, details, created_at)"
        " VALUES\n" + ",\n".join(rows) + ";",
        rows=len(rows),
    )

    f.note("""
── Khiếu nại ────────────────────────────────────────────────────────────────────────────────
violation_id tra NGƯỢC từ (user_id, post_id) chứ không đoán số, vì t_user_violations dùng
BIGSERIAL và id thật chỉ biết được sau khi chèn.

Chỉ số partial uq_appeal_one_pending_per_violation cho phép TỐI ĐA MỘT khiếu nại PENDING trên mỗi
vi phạm. Mỗi vi phạm dưới đây chỉ sinh đúng một dòng, nên ràng buộc đó được giữ theo cấu trúc chứ
không nhờ may mắn.

Khiếu nại ĐÃ XỬ LÝ mới có reviewer_id và reviewed_at — một khiếu nại PENDING mà đã có người duyệt
là trạng thái luồng thật không tạo ra được.
""")
    reasons = ",\n".join(
        f"        ({i}, {q(r)})" for i, r in enumerate(APPEAL_REASONS)
    )
    f.sql(f"""
INSERT INTO socialapp.t_moderation_appeals
    (user_id, violation_id, reason, status, reviewer_id, reviewer_note, reviewed_at,
     created_at, updated_at)
SELECT v.user_id,
       v.id,
       r.reason,
       -- AppealStatus là PENDING / APPROVED / REJECTED. 'ACCEPTED' là chữ dùng cho lời mời kết
       -- bạn và cho đơn ứng tuyển dự án, không phải cho khiếu nại — và nhầm ở đây hỏng theo kiểu
       -- im lặng nhất trong cả bộ seed: màn khiếu nại lọc theo status nên những hàng mang nhãn lạ
       -- không lọt vào truy vấn nào, tab "đã duyệt" rỗng, và không ai thấy một lỗi nào cả.
       CASE WHEN v.id % 3 = 0 THEN 'PENDING'
            WHEN v.id % 3 = 1 THEN 'APPROVED'
            ELSE 'REJECTED' END,
       CASE WHEN v.id % 3 = 0 THEN NULL ELSE {admins[0]} END,
       CASE WHEN v.id % 3 = 0 THEN NULL
            WHEN v.id % 3 = 1 THEN 'Đã xem lại ngữ cảnh, gỡ vi phạm.'
            ELSE 'Giữ nguyên quyết định, nội dung vẫn vi phạm quy tắc cộng đồng.' END,
       CASE WHEN v.id % 3 = 0 THEN NULL ELSE now() - INTERVAL '2 days' END,
       now() - INTERVAL '5 days',
       now() - INTERVAL '2 days'
  FROM (SELECT id, user_id, row_number() OVER (ORDER BY id) AS rn
          FROM socialapp.t_user_violations
         WHERE severity IN ('MEDIUM', 'HIGH')
         LIMIT 12) v
  JOIN (VALUES
{reasons}
       ) AS r(idx, reason) ON r.idx = (v.rn - 1) % {len(APPEAL_REASONS)};""", rows=12)

    f.sql("""
SELECT setval('socialapp.q_moderation_logs_id',
              GREATEST((SELECT COALESCE(MAX(id), 0) FROM socialapp.t_moderation_logs), 1), true);
SELECT setval('socialapp.q_post_reports_id',
              GREATEST((SELECT COALESCE(MAX(id), 0) FROM socialapp.t_post_reports), 1), true);""")
    return f


# ═══ V90 — uy tín và thông báo ═════════════════════════════════════════════════════════════════

def emit_reputation_and_notifications(rng, people, posts, eng):
    f = SqlFile(90, "seed_reputation_and_notifications",
                "Sự kiện uy tín (dẫn xuất từ dữ liệu thật), elite_score tính lại, và thông báo.")
    f.note("""
FILE NÀY SINH TỰ ĐỘNG bởi scripts/seed/generate_seed.py — sửa tay sẽ bị ghi đè.

SỰ KIỆN UY TÍN ĐƯỢC DẪN XUẤT BẰNG SQL TỪ DỮ LIỆU ĐÃ CHÈN, KHÔNG PHẢI SINH RA RỒI GÁN.
Đó là khác biệt quan trọng: nếu generator tự bịa ra các hàng uy tín thì elite_score vẫn bằng tổng
điểm — bất biến vẫn đúng — nhưng con số ấy không còn tương ứng với bất cứ hoạt động nào nhìn thấy
được trên giao diện. Một người có 900 điểm mà bài viết chỉ có ba lượt thích là dữ liệu nói dối.
Dẫn xuất bằng SQL làm cho chuyện đó không xảy ra được.

Bảng điểm, lấy đúng theo mã nguồn:
    REACTION_RECEIVED            1   source_id "{postId}:{reactorId}"  (PostReactionService)
    ACCEPTED_ANSWER             15   source_id "{commentId}"           (PostService)
    ROADMAP_SELF_VERIFIED        5   source_id "{userId}:{nodeId}"
    ROADMAP_NODE_VERIFIED       20   source_id "{userId}:{nodeId}"
    PROJECT_APPLICATION_ACCEPTED 10  source_id "{applicationId}"       (ProjectService)

uq_reputation_event UNIQUE (user_id, source_type, source_id) — mọi câu lệnh dưới đây đều
ON CONFLICT DO NOTHING để chạy lại được.

V42 có sẵn một câu INSERT sinh ROADMAP_NODE_VERIFIED, nhưng nó chạy TRƯỚC bộ seed này trên bảng
rỗng nên là no-op. File này phải tự làm lại phần đó.
""")
    f.rule()

    f.note("REACTION_RECEIVED — 1 điểm cho mỗi cảm xúc mà bài của một người nhận được.")
    f.sql("""
INSERT INTO socialapp.t_reputation_events (user_id, source_type, source_id, points, created_at)
SELECT p.author_id,
       'REACTION_RECEIVED',
       r.post_id::text || ':' || r.user_id::text,
       1,
       r.created_at
  FROM socialapp.t_post_reactions r
  JOIN socialapp.t_posts p ON p.id = r.post_id
 WHERE p.author_id <> r.user_id
    ON CONFLICT (user_id, source_type, source_id) DO NOTHING;""", rows=len(eng["reactions"]))

    f.note("ACCEPTED_ANSWER — 15 điểm cho tác giả của bình luận được chọn làm câu trả lời.")
    f.sql("""
INSERT INTO socialapp.t_reputation_events (user_id, source_type, source_id, points, created_at)
SELECT c.author_id,
       'ACCEPTED_ANSWER',
       c.id::text,
       15,
       c.created_at
  FROM socialapp.t_posts p
  JOIN socialapp.t_comments c
    ON c.id = (p.qna_details->>'acceptedAnswerId')::int
 WHERE p.post_type = 'QNA'
   AND p.qna_details->>'acceptedAnswerId' IS NOT NULL
    ON CONFLICT (user_id, source_type, source_id) DO NOTHING;""", rows=len(eng["accepted"]))

    f.note("""
Lộ trình — 5 điểm khi tự xác nhận, 20 điểm khi được người khác duyệt. Chỉ tính bản ghi đã VERIFIED:
một nút đang chờ duyệt chưa mang lại điểm nào, đúng như luồng thật.
""")
    f.sql("""
INSERT INTO socialapp.t_reputation_events (user_id, source_type, source_id, points, created_at)
SELECT pr.user_id,
       CASE WHEN pr.tier = 'SELF_VERIFIED' THEN 'ROADMAP_SELF_VERIFIED'
            ELSE 'ROADMAP_NODE_VERIFIED' END,
       pr.user_id::text || ':' || pr.node_id::text,
       CASE WHEN pr.tier = 'SELF_VERIFIED' THEN 5 ELSE 20 END,
       pr.created_at
  FROM socialapp.t_user_roadmap_progress pr
 WHERE pr.status = 'VERIFIED'
    ON CONFLICT (user_id, source_type, source_id) DO NOTHING;""", rows=2000)

    f.note("PROJECT_APPLICATION_ACCEPTED — 10 điểm cho người được nhận vào dự án.")
    f.sql("""
INSERT INTO socialapp.t_reputation_events (user_id, source_type, source_id, points, created_at)
SELECT a.applicant_id, 'PROJECT_APPLICATION_ACCEPTED', a.id::text, 10, a.created_at
  FROM socialapp.t_project_applications a
 WHERE a.status = 'ACCEPTED'
    ON CONFLICT (user_id, source_type, source_id) DO NOTHING;""", rows=150)

    f.note("""
elite_score TÍNH LẠI từ tổng điểm, không gõ tay. Đây là bất biến dễ lệch nhất của cả bộ seed: mọi
thứ khác đều hiện ra trên giao diện, còn một elite_score sai thì trông vẫn hoàn toàn hợp lý.
""")
    f.sql("""
UPDATE socialapp.t_users u
   SET elite_score = COALESCE(e.total, 0)
  FROM (SELECT user_id, SUM(points) AS total
          FROM socialapp.t_reputation_events GROUP BY user_id) e
 WHERE e.user_id = u.id;""")

    f.note("""
── Thông báo ────────────────────────────────────────────────────────────────────────────────
LUẬT post_id (V72), và V72 nay là NO-OP nên không còn backfill nào đỡ cho file này:

  · reference_type = 'COMMENT'  →  post_id PHẢI khác NULL và bằng đúng t_comments.post_id của
                                   reference_id. Áp cho cả USER_MENTIONED lẫn COMMENT_LIKED.
  · mọi reference_type khác     →  post_id PHẢI là NULL.

Vì sao NULL là bắt buộc chứ không phải tuỳ: DTO dùng @JsonInclude(NON_NULL), nên một giá trị thừa
làm JSON của FRIEND_REQUEST hay BOOK_PURCHASED mọc thêm một khoá lạ mà frontend không khai.

channel là 'BOTH', đúng giá trị mà chính ứng dụng ghi (SendNotificationRequest mặc định
NotificationChannel.BOTH). Enum chỉ có PUSH / EMAIL / BOTH — KHÔNG có 'IN_APP', dù đó là cái tên
nghe hợp lý nhất cho một thông báo trong ứng dụng. Cột là varchar không có CHECK nên Flyway nhận
tuốt; chỗ nổ là Hibernate lúc đọc, và vì mọi hàng đều mang cùng một giá trị nên sai ở đây làm
/notifications trả 500 cho MỌI tài khoản chứ không phải hỏng lác đác.

Sinh bằng SQL từ dữ liệu đã có, nên post_id không thể lệch: nó lấy thẳng từ chính hàng bình luận.
""")

    f.sql("""
-- POST_LIKED: trỏ tới bài, nên post_id để NULL (route /posts/{id} đã đủ).
INSERT INTO socialapp.t_notifications
    (recipient_id, actor_id, type, title, body, reference_id, reference_type, post_id,
     channel, is_read, sent_at, created_at)
SELECT p.author_id, r.user_id, 'POST_LIKED',
       'Có người bày tỏ cảm xúc về bài viết của bạn',
       u.full_name || ' đã bày tỏ cảm xúc về bài viết của bạn.',
       p.id, 'POST', NULL,
       'BOTH', (r.user_id % 3) <> 0, r.created_at, r.created_at
  FROM socialapp.t_post_reactions r
  JOIN socialapp.t_posts p ON p.id = r.post_id
  JOIN socialapp.t_users u ON u.id = r.user_id
 WHERE p.author_id <> r.user_id
   AND (r.post_id + r.user_id) % 7 = 0;""", rows=7000)

    f.sql("""
-- POST_COMMENTED: cũng trỏ tới bài.
INSERT INTO socialapp.t_notifications
    (recipient_id, actor_id, type, title, body, reference_id, reference_type, post_id,
     channel, is_read, sent_at, created_at)
SELECT p.author_id, c.author_id, 'POST_COMMENTED',
       'Có bình luận mới trong bài viết của bạn',
       u.full_name || ' đã bình luận về bài viết của bạn.',
       p.id, 'POST', NULL,
       'BOTH', (c.id % 4) <> 0, c.created_at, c.created_at
  FROM socialapp.t_comments c
  JOIN socialapp.t_posts p ON p.id = c.post_id
  JOIN socialapp.t_users u ON u.id = c.author_id
 WHERE p.author_id <> c.author_id
   AND c.parent_id IS NULL
   AND c.id % 3 = 0;""", rows=2500)

    f.sql("""
-- USER_MENTIONED: trỏ tới BÌNH LUẬN, nên post_id BẮT BUỘC có, lấy thẳng từ chính hàng bình luận.
-- Bình luận mà dấu @ là địa chỉ email KHÔNG lọt vào đây: điều kiện dưới đây chỉ nhận nội dung bắt
-- đầu bằng '@' và không chứa dấu chấm trước khoảng trắng đầu tiên, đúng cách MentionScanner phân
-- biệt một handle với một địa chỉ thư.
INSERT INTO socialapp.t_notifications
    (recipient_id, actor_id, type, title, body, reference_id, reference_type, post_id,
     channel, is_read, sent_at, created_at)
SELECT target.id, c.author_id, 'USER_MENTIONED',
       'Có người nhắc tới bạn',
       u.full_name || ' đã nhắc tới bạn trong một bình luận.',
       c.id, 'COMMENT', c.post_id,
       'BOTH', (c.id % 5) <> 0, c.created_at, c.created_at
  FROM socialapp.t_comments c
  JOIN socialapp.t_users u ON u.id = c.author_id
  JOIN socialapp.t_users target
    ON target.username = substring(c.content from '^@([A-Za-z0-9]+)')
 WHERE c.content LIKE '@%'
   AND target.id <> c.author_id;""", rows=60)

    f.sql("""
-- COMMENT_LIKED: cũng trỏ tới bình luận, cùng luật post_id.
INSERT INTO socialapp.t_notifications
    (recipient_id, actor_id, type, title, body, reference_id, reference_type, post_id,
     channel, is_read, sent_at, created_at)
SELECT c.author_id, cr.user_id, 'COMMENT_LIKED',
       'Có người thấy bình luận của bạn hữu ích',
       u.full_name || ' thấy bình luận của bạn hữu ích.',
       c.id, 'COMMENT', c.post_id,
       'BOTH', (cr.user_id % 3) <> 0, cr.created_at, cr.created_at
  FROM socialapp.t_comment_reactions cr
  JOIN socialapp.t_comments c ON c.id = cr.comment_id
  JOIN socialapp.t_users u ON u.id = cr.user_id
 WHERE c.author_id <> cr.user_id
   AND (cr.comment_id + cr.user_id) % 11 = 0;""", rows=2700)

    f.sql("""
-- FRIEND_REQUEST và FRIEND_ACCEPTED: không nói về bài viết nào, nên post_id là NULL.
INSERT INTO socialapp.t_notifications
    (recipient_id, actor_id, type, title, body, reference_id, reference_type, post_id,
     channel, is_read, sent_at, created_at)
SELECT fr.addressee_id, fr.requester_id,
       CASE WHEN fr.status = 'PENDING' THEN 'FRIEND_REQUEST' ELSE 'FRIEND_ACCEPTED' END,
       CASE WHEN fr.status = 'PENDING' THEN 'Bạn có lời mời kết bạn mới'
            ELSE 'Lời mời kết bạn đã được chấp nhận' END,
       u.full_name || CASE WHEN fr.status = 'PENDING'
                           THEN ' đã gửi cho bạn một lời mời kết bạn.'
                           ELSE ' đã chấp nhận lời mời kết bạn của bạn.' END,
       fr.id, 'FRIEND_REQUEST', NULL,
       'BOTH', fr.status <> 'PENDING', fr.created_at, fr.created_at
  FROM socialapp.t_friend_requests fr
  JOIN socialapp.t_users u ON u.id = fr.requester_id
 WHERE fr.status IN ('PENDING', 'ACCEPTED')
   AND fr.id % 9 = 0;""", rows=400)

    f.sql("""
-- BOOK_PURCHASED: trỏ tới sách, post_id NULL.
INSERT INTO socialapp.t_notifications
    (recipient_id, actor_id, type, title, body, reference_id, reference_type, post_id,
     channel, is_read, sent_at, created_at)
SELECT b.author_id, bp.buyer_id, 'BOOK_PURCHASED',
       'Có người mua sách của bạn',
       u.full_name || ' vừa mua "' || b.title || '".',
       b.id, 'BOOK', NULL,
       'BOTH', (bp.buyer_id % 2) = 0, bp.created_at, bp.created_at
  FROM socialapp.t_book_purchases bp
  JOIN socialapp.t_books b ON b.id = bp.book_id
  JOIN socialapp.t_users u ON u.id = bp.buyer_id
 WHERE bp.payment_status = 'COMPLETED'
   AND b.author_id <> bp.buyer_id;""", rows=600)

    f.note("""
Kiểm tra tại chỗ: nếu bất kỳ hàng nào phá luật post_id thì câu lệnh dưới đây làm migration ĐỔ NGAY,
ở lần chạy đầu tiên, thay vì để lỗi đi tới giao diện dưới dạng một thông báo bấm vào không làm gì.
Đây là bất biến mà V72 từng canh bằng backfill, và nay V72 là no-op.
""")
    f.sql("""
DO $$
DECLARE bad INT;
BEGIN
    SELECT COUNT(*) INTO bad
      FROM socialapp.t_notifications n
     WHERE (n.reference_type = 'COMMENT'
            AND (n.post_id IS NULL
                 OR n.post_id <> (SELECT c.post_id FROM socialapp.t_comments c
                                   WHERE c.id = n.reference_id)))
        OR (n.reference_type <> 'COMMENT' AND n.post_id IS NOT NULL);
    IF bad > 0 THEN
        RAISE EXCEPTION 'Seed hong: % thong bao pha luat post_id cua V72', bad;
    END IF;
END $$;""")

    f.sql("""
SELECT setval('socialapp.q_notifications_id',
              GREATEST((SELECT COALESCE(MAX(id), 0) FROM socialapp.t_notifications), 1), true);""")
    return f


# ═══ Kế hoạch chat (Stream Chat) ═══════════════════════════════════════════════════════════════

CHAT_GROUPS = [
    ("backend-vn", "Backend Việt Nam", "BACKEND"),
    ("frontend-guild", "Frontend Guild", "FRONTEND"),
    ("devops-vanhanh", "DevOps & Vận hành", "DEVOPS"),
    ("dulieu-ml", "Dữ liệu & Học máy", "DATA_ML"),
    ("bao-mat", "An toàn thông tin", "SECURITY"),
    ("kiem-thu", "Kiểm thử & Chất lượng", "QA"),
    ("mobile-vn", "Mobile Việt Nam", "MOBILE"),
    ("review-code", "Review code cùng nhau", None),
]

CHAT_OPENERS = [
    "Chào cả nhà, mình mới tham gia.",
    "Có ai rảnh xem giúp mình một đoạn không?",
    "Vừa đọc được bài này hay, chia sẻ lại.",
    "Hôm nay deploy suôn sẻ, nhẹ cả người.",
    "Ai đã dùng bản mới chưa, có gì đáng chú ý không?",
    "Mình đang phân vân giữa hai cách, mọi người nghĩ sao?",
    "Cảm ơn mọi người hôm qua nhé, sửa được rồi.",
    "Có buổi chia sẻ tuần sau, ai đi không?",
]
CHAT_REPLIES = [
    "Mình nghĩ nên thử cách thứ hai trước.",
    "Chỗ đó bên mình cũng gặp, để mình gửi link.",
    "Nghe hợp lý đấy.",
    "Cẩn thận phần cấu hình nhé, dễ quên.",
    "Đã đọc, để mình thử rồi báo lại.",
    "Chuẩn rồi bạn.",
    "Mình không chắc lắm, nhưng thử thì mất gì đâu.",
    "Để mai mình xem kỹ hơn rồi trả lời nhé.",
    "Cái này mình từng viết ghi chú, để mình tìm lại.",
    "Đồng ý, làm gọn trước rồi tối ưu sau.",
]


def write_chat_plan(rng, people, edges, blocks):
    """Kế hoạch chat để `scripts/seed/seed-stream-chat.mjs` thực thi.

    Vì sao là một file kế hoạch chứ không để script tự truy vấn database: script chạy trên máy có
    key Stream, không nhất thiết là máy có database — và nếu nó tự chọn cặp trò chuyện thì hai bên
    lệch nhau được. Ở đây danh sách phòng 1-1 lấy TRỰC TIẾP từ cùng tập cạnh đã sinh ra
    V82__seed_social_graph.sql và friend-graph.cypher, nên chat không thể có phòng giữa hai người
    chưa từng là bạn.
    """
    by_id = {p["id"]: p for p in people}
    role_of = {p["id"]: p["primary_role"] for p in people if p["primary_role"]}

    users = [
        {
            "id": str(p["id"]),
            # Đúng quy ước StreamChatClient.displayName: full_name, lùi về username nếu trống.
            "name": p["full_name"] or p["username"],
            "image": p["avatar"],
        }
        for p in people
    ]

    # 120 phòng 1-1 giữa các cặp ĐÃ LÀ BẠN. Ưu tiên cạnh chạm tới hai tài khoản demo để buổi trình
    # bày chắc chắn có hội thoại mở sẵn.
    demo_edges = [e for e in edges if DEMO_EXPERT in e or DEMO_NEWCOMER in e]
    other_edges = [e for e in edges if DEMO_EXPERT not in e and DEMO_NEWCOMER not in e]
    chosen = demo_edges[:20] + rng.sample(other_edges, 120 - min(20, len(demo_edges)))
    dms = [{"members": [str(a), str(b)]} for a, b in chosen]

    groups = []
    for slug, name, role in CHAT_GROUPS:
        pool = [uid for uid, r in role_of.items() if r == role] if role else list(role_of)
        members = rng.sample(pool, min(len(pool), rng.randint(12, 28)))
        if DEMO_EXPERT not in members:
            members.append(DEMO_EXPERT)
        groups.append({
            "id": f"seed-{slug}",
            "name": name,
            "createdBy": str(members[0]),
            "members": [str(m) for m in members],
        })

    # Tin nhắn trải 18 tháng. Một phần phòng cố ý dừng ở tin của NGƯỜI KHÁC và gần hiện tại, để
    # danh sách hội thoại có phòng chưa đọc — nếu phòng nào cũng kết thúc bằng tin của chính mình
    # thì huy hiệu chưa đọc không bao giờ hiện.
    messages = []
    for index, room in enumerate(dms + groups):
        members = room["members"]
        count = rng.randint(4, 26)
        age = rng.randint(1, 540)
        for i in range(count):
            sender = members[i % len(members)] if len(members) == 2 else rng.choice(members)
            text = (CHAT_OPENERS[(index + i) % len(CHAT_OPENERS)] if i == 0
                    else CHAT_REPLIES[(index * 3 + i) % len(CHAT_REPLIES)])
            messages.append({
                "channelId": room.get("id"),
                "members": members if "id" not in room else None,
                "sender": sender,
                "text": text,
                "daysAgo": max(0, age - (count - i)),
            })

    plan = {
        "_generated_by": "scripts/seed/generate_seed.py",
        "_note": "Sinh tự động cùng lượt với V82. Đừng sửa tay.",
        "users": users,
        "dms": dms,
        "groups": groups,
        # Đẩy chặn TRƯỚC khi tạo phòng: StreamChatService.createGroupChat lọc thành viên theo quan
        # hệ chặn, nên làm ngược thứ tự thì bộ lọc không có gì để lọc và hai bên lệch nhau.
        "blocks": [{"blocker": str(a), "blocked": str(b)} for a, b in blocks],
        "messages": messages,
    }
    path = ROOT / "scripts" / "seed" / "chat-plan.json"
    path.write_text(json.dumps(plan, ensure_ascii=False, indent=1), encoding="utf-8", newline="\n")
    return path.name, len(dms) + len(groups), len(messages)


def write_manifest():
    """Manifest ảnh: key ⇢ (loại, nguồn tải).

    Cả hai bên tiêu thụ file này đều đọc manifest thay vì tự grep key ra từ SQL — máy dev qua
    docker/minio/generate-seed-objects.py, production qua MinIOSeedObjectInitializer.java. Grep SQL
    là cách cũ và nó vỡ mỗi khi định dạng SQL đổi — mà định dạng SQL thì do generator quyết định,
    nên hai bên có thể lệch nhau mà không ai biết. Manifest là hợp đồng giữa hai bên, ghi ra cùng
    một lần chạy với chính các file SQL, và nằm trong resources để đóng được vào jar.

    Cột `nguồn` để trống nghĩa là không có ảnh thật để tải (nội dung sách PDF/EPUB) — bên kia sẽ
    sinh file mẫu.
    """
    MINIO_MANIFEST.parent.mkdir(parents=True, exist_ok=True)
    lines = [
        "# key\tloại\tnguồn (rỗng = sinh file mẫu)",
        "# SINH TỰ ĐỘNG bởi scripts/seed/generate_seed.py — đừng sửa tay.",
    ]
    for key in sorted(MANIFEST):
        kind, url = MANIFEST[key]
        lines.append(f"{key}\t{kind}\t{url or ''}")
    MINIO_MANIFEST.write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")
    return MINIO_MANIFEST.name, len(MANIFEST)


def write_id_map(people, posts, books, projects, roadmaps, eng):
    """Bảng ID mốc — để cập nhật kịch bản demo của frontend CÙNG LƯỢT với bộ seed.

    Kịch bản 12 phút bên DATN-frontend trích dẫn id trên sân khấu. Đổi bộ seed mà quên file đó thì
    người trình bày bấm vào một id không còn tồn tại, giữa buổi bảo vệ.
    """
    fx = eng["fixtures"]
    demo = [p for p in people if p["id"] in (DEMO_EXPERT, DEMO_NEWCOMER, 9499, 9500)]
    lines = [
        "# Bảng ID mốc của bộ seed",
        "",
        "SINH TỰ ĐỘNG bởi `scripts/seed/generate_seed.py` — đừng sửa tay.",
        "",
        "Dùng file này để cập nhật `DATN-frontend/docs/demo-script.md` **cùng lượt** với mỗi lần",
        "sinh lại bộ seed. Kịch bản demo trích dẫn id trên sân khấu; quên cập nhật là bấm vào một",
        "id không còn tồn tại, giữa buổi bảo vệ.",
        "",
        "## Tài khoản",
        "",
        "| id | username | vai | ghi chú |",
        "|---|---|---|---|",
    ]
    roles = {DEMO_EXPERT: "cao thủ", DEMO_NEWCOMER: "người mới",
             9499: "admin phụ", 9500: "admin chính"}
    for p in sorted(demo, key=lambda x: x["id"]):
        lines.append(f"| {p['id']} | `{p['username']}` | {roles[p['id']]} | {p['full_name']} |")
    lines += [
        "",
        "Mật khẩu: tài khoản thường `12qwaszx`, ADMIN `1234qwer`.",
        "Email: `<username>@" + EMAIL_DOMAIN + "`",
        "",
        "## Dải id",
        "",
        "| Thực thể | Dải |",
        "|---|---|",
        f"| `t_users` | {USER_ID_FIRST}–{USER_ID_LAST} |",
        "| `t_hashtags` | 1001–1120 (thường), 1201+ (dấu fixture) |",
        f"| `t_roadmaps` | {roadmaps[0]['id']}–{roadmaps[-1]['id']} |",
        f"| `t_books` | {books[0]['id']}–{books[-1]['id']} |",
        f"| `t_projects` | {projects[0]['id']}–{projects[-1]['id']} |",
        f"| `t_posts` | {posts[0]['id']}–{posts[-1]['id']} (+102998, 102999 fixture) |",
        f"| `t_comments` | {COMMENT_ID_FIRST}+ |",
        f"| `t_explanations` | {EXPLANATION_ID_FIRST}+ |",
        "",
        "## Bài fixture (tìm bằng hashtag, không bằng id)",
        "",
        "| Hashtag | id hiện tại | Ca kiểm |",
        "|---|---|---|",
        f"| `fixture_zero_comments` | {fx['zero_comments']} | bài không có bình luận nào |",
        f"| `fixture_one_comment` | {fx['one_comment']} | đúng một bình luận gốc |",
        f"| `fixture_two_comments` | {fx['two_comments']} | đúng hai bình luận gốc |",
        f"| `fixture_many_comments` | {fx['many_comments']} | từ năm bình luận gốc trở lên |",
        f"| `fixture_zero_reactions` | {fx['zero_reactions']} | bài không có cảm xúc nào |",
        "| `fixture_long_content` | (tra theo dấu) | bài từ 1200 ký tự |",
        "| `fixture_medium_content` | (tra theo dấu) | bài 550–750 ký tự |",
        "| `fixture_missing_image` | (tra theo dấu) | ảnh trỏ vào object không tồn tại |",
        "| `fixture_blocked_thread` | 102998 | sau khi lọc chặn, hai bình luận cũ nhất đều là trả lời |",
        "| `fixture_mixed_levels` | 102999 | luồng bình luận có nhiều hạng uy tín |",
        "",
        "## Lộ trình",
        "",
        "| id | Tên | Số nút |",
        "|---|---|---|",
    ]
    for r in roadmaps:
        count = sum(1 for _ in ROADMAPS[r["id"] - 2001][3])
        lines.append(f"| {r['id']} | {r['name']} | {count} |")
    lines += [
        "",
        "## Sách mốc",
        "",
        "| id | Tựa | Giá |",
        "|---|---|---|",
    ]
    for b in books[:5]:
        price = f"{b['price']:,}₫".replace(",", ".") if b["price"] else "miễn phí"
        lines.append(f"| {b['id']} | {b['title']} | {price} |")

    ID_MAP.write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")
    return ID_MAP.name


def main():
    guard_version_collisions(GENERATED)
    nums = ", ".join(f"V{v}" for v in sorted(GENERATED))
    print(f"  sinh {nums} — không đụng db/migration hay file db/seed viết tay")

    rng = random.Random(SEED)
    people = build_people(rng)

    edges = build_edges(rng, people)
    # Lời mời chưa/không thành và quan hệ chặn đều lấy từ các cặp CHƯA là bạn, cấp một lượt để
    # không cặp nào bị dùng lại ở hai vai trò mâu thuẫn nhau.
    spare = build_non_friend_pairs(rng, people, edges, 340)
    pending, rejected, cancelled, blocks = spare[:180], spare[180:250], spare[250:300], spare[300:340]

    posts, quiz_posts, author_weights = build_posts(rng, people, edges)
    eng = build_engagement(rng, people, posts, quiz_posts, edges, author_weights)
    books = build_books(rng, people, posts)
    reviews, purchases = build_bookstore(rng, people, books)
    explanations, notes = build_knowledge(rng, people, posts)
    projects, positions, applications = build_projects(rng, people)
    roadmaps, nodes, progress = build_roadmaps(rng, people)
    logs, violations, bans, reports, admins = build_moderation(rng, people, posts)

    files = [
        emit_users(people),
        emit_social_graph(people, edges, pending, rejected, cancelled, blocks),
        emit_posts(rng, people, posts, quiz_posts),
        emit_engagement(eng),
        emit_bookstore(books, reviews, purchases),
        emit_knowledge(explanations, notes),
        emit_projects(projects, positions, applications),
        emit_roadmaps(roadmaps, nodes, progress),
        emit_moderation(logs, violations, bans, reports, admins),
        emit_reputation_and_notifications(rng, people, posts, eng),
    ]
    for f in files:
        label, rows, size = f.write()
        print(f"  {label:<52} {rows:>7,} hàng  {size / 1024:>7.1f} KB")

    label, rooms, msgs = write_chat_plan(rng, people, edges, blocks)
    print(f"  {label:<52} {rooms:>7,} phòng, {msgs:,} tin nhắn")

    label, n = write_manifest()
    print(f"  {label:<52} {n:>7,} object")
    print(f"  {write_id_map(people, posts, books, projects, roadmaps, eng):<52}   bảng ID mốc")

    label, count = write_cypher(people, edges)
    degrees = 2 * len(edges) / len([p for p in people if p["primary_role"]])
    print(f"  {label:<52} {count:>7,} cạnh  (bậc trung bình {degrees:.1f})")



if __name__ == "__main__":
    main()
