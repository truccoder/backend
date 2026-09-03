#!/usr/bin/env python3
"""Lấy dữ kiện thật của 80 quyển sách trong danh mục và dựng sẵn nội dung vài trang xem thử.

VÌ SAO CÓ FILE NÀY. Gian sách của bộ seed có bìa thật và tiêu đề thật, nhưng file PDF/EPUB đằng
sau chỉ là một trang trắng ghi đúng một dòng "File mau cho bo seed". Bấm "Xem thử" trong buổi bảo
vệ là mở ra đúng cái trang trắng ấy — bìa thật lại càng làm chỗ rỗng nổi bật hơn. Ở đây ta dựng
cho mỗi quyển bốn trang: trang bìa lót, trang mục lục, và hai trang mở đầu chương một.

CHỈ CRAWL DỮ KIỆN, KHÔNG CRAWL VĂN BẢN CỦA SÁCH. Open Library trả về tiêu đề, tiêu đề phụ, tác giả,
nhà xuất bản, năm, số trang, chủ đề và MỤC LỤC. Đó là dữ kiện thư mục, không phải tác phẩm — chép
lại không đụng bản quyền, mà lại chính là thứ khiến bản xem thử trông đúng là quyển sách đó chứ
không phải một file mẫu chung chung. Phần văn xuôi trong hai trang chương một do máy sinh (xem
`body_paragraphs`), lấy chủ đề thật của quyển sách làm nguyên liệu. API cũng trả `excerpts` —
CỐ Ý KHÔNG DÙNG: đó là trích nguyên văn từ sách.

CHẠY MỘT LẦN LÚC SOẠN, KHÔNG PHẢI LÚC SEED. Kết quả ghi ra
`src/main/resources/db/seed/book-previews.json` và được commit. Cả hai bên dựng file — máy dev qua
`docker/minio/generate-seed-objects.py`, production qua `MinIOSeedObjectInitializer.java` — chỉ
đọc file đã có sẵn đó và sắp chữ, không gọi mạng. Cùng lý do với ảnh nướng sẵn trong Dockerfile:
một buổi demo không được phụ thuộc vào việc openlibrary.org có sống hay không.

CACHE Ở `scripts/seed/.cache/openlibrary/`. Lần chạy thứ hai không cần mạng và cho ra đúng kết quả
cũ. Xoá thư mục đó nếu muốn lấy lại dữ liệu mới.

    python scripts/seed/crawl_book_previews.py

ISBN nào Open Library không biết (hoặc không có mục lục) vẫn có đủ bốn trang: mục lục được dựng từ
chủ đề, và dòng tổng kết cuối lần chạy nói rõ bao nhiêu quyển có mục lục thật — im lặng ở chỗ này
là kiểu hỏng khó thấy nhất.
"""

import json
import random
import re
import sys
import time
import urllib.error
import urllib.request
from datetime import date
from pathlib import Path

# Console mặc định của Windows là cp1252 và mọi dòng log dưới đây đều có dấu tiếng Việt — không
# đặt lại thì script chạy xong hết việc rồi mới đổ ở lệnh print cuối cùng.
sys.stdout.reconfigure(encoding="utf-8")
sys.stderr.reconfigure(encoding="utf-8")

sys.path.insert(0, str(Path(__file__).resolve().parent))
from book_catalog import CATALOG  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
OUT_PATH = ROOT / "src" / "main" / "resources" / "db" / "seed" / "book-previews.json"
CACHE_DIR = Path(__file__).resolve().parent / ".cache" / "openlibrary"

API = "https://openlibrary.org/api/books?bibkeys=ISBN:{isbn}&format=json&jscmd=data"
USER_AGENT = "elitenexus-seed/1.0"
TIMEOUT_SECONDS = 20

# Tuần tự và có nghỉ giữa hai lần gọi. 80 quyển là hơn một phút, chấp nhận được cho một script chạy
# tay vài tháng một lần; đổi lại openlibrary.org không bóp mình giữa chừng và bỏ trống mục lục của
# một nửa danh mục — thứ chỉ lộ ra ở dòng tổng kết chứ không làm gì đổ.
POLITE_DELAY_SECONDS = 0.4

# Bao nhiêu dòng mục lục in ra trang 2. Nhiều quyển có mục lục tới hàng trăm mục (Clean Code có
# ngót 300 vì kể cả tiểu mục cấp 2); một trang A4 chữ 11pt chứa được chừng này.
TOC_MAX_ENTRIES = 26

# Số trang nội dung sau trang bìa lót và trang mục lục.
CHAPTER_PAGES = 2

# Mỗi trang chương chứa ngần này đoạn văn. Bốn đoạn ngắn lấp đầy một trang A4 mà không tràn.
PARAGRAPHS_PER_PAGE = 4


# ── Crawl ──────────────────────────────────────────────────────────────────────────────────────

def fetch_record(isbn):
    """Trả về bản ghi Open Library của một ISBN, hoặc None. Không bao giờ ném ra ngoài."""
    cached = CACHE_DIR / (isbn + ".json")
    if cached.is_file():
        try:
            return json.loads(cached.read_text(encoding="utf-8")) or None
        except ValueError:
            pass  # Cache hỏng thì tải lại, không phải lý do để dừng.

    try:
        request = urllib.request.Request(API.format(isbn=isbn),
                                         headers={"User-Agent": USER_AGENT})
        with urllib.request.urlopen(request, timeout=TIMEOUT_SECONDS) as response:
            payload = json.loads(response.read().decode("utf-8"))
    except (urllib.error.URLError, OSError, ValueError):
        return None
    finally:
        time.sleep(POLITE_DELAY_SECONDS)

    record = payload.get("ISBN:" + isbn) or {}
    CACHE_DIR.mkdir(parents=True, exist_ok=True)
    cached.write_text(json.dumps(record, ensure_ascii=False, indent=1), encoding="utf-8")
    return record or None


def fetch_work_subjects(isbn):
    """Chủ đề lấy ở mức TÁC PHẨM, cho những bản in mà bản ghi ấn bản không khai chủ đề nào.

    Sáu quyển trong danh mục rơi vào diện này, và chủ đề là nguyên liệu dựng tên chương ở trang mục
    lục khi không có mục lục thật — thiếu nó thì trang mục lục thành "Chapter 1 Overview / Chapter 2
    Background", đúng kiểu chung chung mà cả việc này đang tránh. Hai lần gọi thêm, chỉ cho những
    quyển thật sự thiếu, nên không đáng kể so với 80 lần gọi chính.
    """
    cached = CACHE_DIR / (isbn + ".work.json")
    if cached.is_file():
        try:
            return json.loads(cached.read_text(encoding="utf-8"))
        except ValueError:
            pass

    def get(url):
        request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
        with urllib.request.urlopen(request, timeout=TIMEOUT_SECONDS) as response:
            return json.loads(response.read().decode("utf-8"))

    try:
        edition = get("https://openlibrary.org/isbn/" + isbn + ".json")
        works = edition.get("works") or []
        if not works:
            return []
        subjects = get("https://openlibrary.org" + works[0]["key"] + ".json").get("subjects") or []
    except (urllib.error.URLError, OSError, ValueError, KeyError, TypeError):
        return []
    finally:
        time.sleep(POLITE_DELAY_SECONDS)

    CACHE_DIR.mkdir(parents=True, exist_ok=True)
    cached.write_text(json.dumps(subjects, ensure_ascii=False, indent=1), encoding="utf-8")
    return subjects


def clean(text):
    """Một dòng, không khoảng trắng thừa. Mục lục của Open Library hay dính xuống dòng và tab."""
    return re.sub(r"\s+", " ", str(text or "")).strip()


def toc_entries(record):
    """Mục lục thật, phẳng hoá về dạng (nhãn, tiêu đề, số trang).

    CHỈ LẤY CẤP 0 VÀ 1. Cấp 2 là tiểu mục bên trong một chương — in ra thì trang mục lục thành một
    cột chữ dài dằng dặc không còn ra hình mục lục nữa. Mục không có tiêu đề bị bỏ: Open Library có
    những dòng chỉ mang số trang.
    """
    out = []
    for item in record.get("table_of_contents") or []:
        if not isinstance(item, dict) or (item.get("level") or 0) > 1:
            continue
        title = clean(item.get("title"))
        if not title:
            continue
        out.append((clean(item.get("label")), title, clean(item.get("pagenum"))))
    return out


def subjects_of(record):
    """Chủ đề thật, đã lọc trùng và bỏ những nhãn thư viện quá dài để làm tên chương."""
    seen, out = set(), []
    for item in record.get("subjects") or []:
        name = clean(item.get("name") if isinstance(item, dict) else item)
        key = name.lower()
        if not name or key in seen or len(name) > 48:
            continue
        seen.add(key)
        # Viết hoa chữ đầu: Open Library trả cả 'machine learning' lẫn 'Machine learning', và
        # chủ đề được dùng làm TÊN CHƯƠNG ở trang mục lục — 'Chapter 1  machine learning' lộ ngay
        # ra là ghép máy.
        out.append(name[0].upper() + name[1:])
    return out


# ── Văn xuôi sinh ra ───────────────────────────────────────────────────────────────────────────
#
# TIẾNG ANH, KHÔNG PHẢI TIẾNG VIỆT, và cũng không phải lorem ipsum. Hai lý do. Một: PDF dựng bằng
# font Helvetica chuẩn của PDF, vốn không có ký tự tiếng Việt — mọi dấu sẽ thành `?` trên trang
# giấy. Hai: cả 80 quyển trong danh mục đều là sách kỹ thuật tiếng Anh, nên vài trang mở đầu bằng
# tiếng Anh mới đúng là thứ người ta chờ thấy khi bấm "Xem thử"; lorem ipsum thì nhìn một giây là
# biết chỗ trống.

OPENERS = [
    "This chapter sets out what {topic} means in day-to-day work, and why the usual "
    "definitions leave out the part that costs the most.",
    "Before we can talk about {topic} usefully, we have to agree on what a good outcome "
    "actually looks like.",
    "Most teams meet {topic} the same way: not as a decision, but as a consequence of "
    "decisions nobody remembers making.",
    "The material in this chapter is ordinary. What makes it worth a chapter is how "
    "reliably it gets skipped.",
    "There is a version of {topic} that fits on a slide, and a version that survives contact "
    "with a real system. This book is about the second one.",
]

MIDDLES = [
    "The examples here are deliberately small. A small example you can hold in your head is "
    "worth more than a realistic one you have to keep re-reading.",
    "Notice what the previous paragraph did not say. It did not say the technique is free, and "
    "it did not promise the trade-off disappears once you understand it.",
    "In practice the hard part is not applying the rule. It is noticing that you are in a "
    "situation where the rule applies at all.",
    "Two teams can follow the same advice and end up in different places, because the advice "
    "was never the whole input; the shape of the existing system was.",
    "It helps to write down what you expect to happen before you run anything. Half the value "
    "of the exercise is discovering that you had no expectation.",
    "This is where {topic} stops being a matter of taste. The cost shows up later, in work "
    "somebody else has to do, which is exactly why it is easy to discount now.",
    "The rest of the chapter works through the same idea from three directions: what to do, "
    "what it costs, and how to tell when you have gone too far.",
    "Readers coming from {neighbour} will recognise the pattern under a different name. The "
    "vocabulary differs; the failure mode does not.",
]

CLOSERS = [
    "Keep that distinction in mind for the next few pages, because everything after this rests "
    "on it.",
    "We will come back to this once the machinery is in place. For now it is enough to see that "
    "the question is worth asking.",
    "If only one sentence from this chapter survives, let it be that one.",
    "The next section makes the same point with code, which is where it stops being arguable.",
]


def body_paragraphs(rng, topics, count):
    """Sinh `count` đoạn văn, mỗi đoạn 2-3 câu, lấy chủ đề thật của sách làm nguyên liệu.

    Xác định theo `rng` (gieo từ ISBN), nên chạy lại cho ra đúng chữ cũ và diff của file JSON chỉ
    đổi ở những quyển thật sự đổi.
    """
    primary = topics[0] if topics else "the subject of this book"
    neighbour = topics[1] if len(topics) > 1 else "adjacent fields"
    middles = rng.sample(MIDDLES, len(MIDDLES))
    out = []
    for i in range(count):
        sentences = [rng.choice(OPENERS)] if i == 0 else []
        sentences.append(middles[(i * 2) % len(middles)])
        if i % 2 == 1 or i == count - 1:
            sentences.append(middles[(i * 2 + 1) % len(middles)])
        if i == count - 1:
            sentences.append(rng.choice(CLOSERS))
        out.append(" ".join(s.format(topic=primary, neighbour=neighbour) for s in sentences))
    return out


# ── Dựng trang ─────────────────────────────────────────────────────────────────────────────────

def compose(isbn, title, writer, record):
    """Dựng danh sách trang cho một quyển. Mỗi trang là {heading, paragraphs}."""
    rng = random.Random(int(isbn))
    record = record or {}

    subtitle = clean(record.get("subtitle"))
    publishers = record.get("publishers") or []
    publisher = clean(publishers[0].get("name")) if publishers else ""
    published = clean(record.get("publish_date"))
    total_pages = (record.get("number_of_pages")
                   if isinstance(record.get("number_of_pages"), int) else None)
    subjects = subjects_of(record)
    if not subjects:
        subjects = subjects_of({"subjects": fetch_work_subjects(isbn)})
    toc = toc_entries(record)

    # ── Trang 1: bìa lót ──
    imprint = ", ".join(part for part in (publisher, published) if part)
    front = [part for part in (subtitle, writer, imprint, "ISBN " + isbn) if part]
    front.append(
        "Sample pages prepared for the EliteNexus seed catalogue. The bibliographic details and "
        "the table of contents are the real ones, from Open Library; the prose on the following "
        "pages is generated filler and is not the published text of this book."
    )
    pages = [{"heading": title, "paragraphs": front}]

    # ── Trang 2: mục lục ──
    #
    # Số trang đi trong ngoặc sau tiêu đề chứ không xếp cột phải. Cột phải cần đo bề rộng chuỗi
    # theo font, mà bên sinh PDF của docker dựng file bằng tay và không đo được — hai bên sẽ ra
    # hai kiểu căn khác nhau trên cùng một dữ liệu.
    if toc:
        lines = []
        for label, entry_title, pagenum in toc[:TOC_MAX_ENTRIES]:
            line = (label + "  " + entry_title) if label else entry_title
            lines.append(line + "  (p. " + pagenum + ")" if pagenum else line)
        real_toc = True
    else:
        # Không có mục lục thật thì dựng từ chủ đề thật. Vẫn là dữ kiện của chính quyển sách đó,
        # không phải một danh sách chương bịa cho có.
        heads = subjects[:8] or ["Overview", "Background", "Practice", "Further reading"]
        lines = ["Foreword", "Introduction"]
        lines += ["Chapter %d  %s" % (n, name) for n, name in enumerate(heads, start=1)]
        lines += ["Afterword", "Index"]
        real_toc = False
    pages.append({"heading": "Contents", "paragraphs": lines})

    # ── Trang 3-4: mở đầu chương một ──
    first = next(((label + "  " + entry_title) for label, entry_title, _ in toc
                  if label.lower().startswith("chapter")), None)
    chapter_heading = first or ("Chapter 1  " + (subjects[0] if subjects else "Introduction"))
    for index in range(CHAPTER_PAGES):
        pages.append({
            "heading": chapter_heading if index == 0 else "",
            "paragraphs": body_paragraphs(rng, subjects, PARAGRAPHS_PER_PAGE),
        })

    return {
        "title": title,
        "author": writer,
        "subtitle": subtitle,
        "publisher": publisher,
        "published": published,
        "totalPages": total_pages,
        "realToc": real_toc,
        "subjects": subjects,
        "pages": pages,
    }


def main():
    books, hits, with_toc, with_pagecount, with_subjects = {}, 0, 0, 0, 0
    for index, (isbn, title, writer) in enumerate(CATALOG, start=1):
        record = fetch_record(isbn)
        entry = compose(isbn, title, writer, record)
        books[isbn] = entry
        hits += 1 if record else 0
        with_toc += 1 if entry["realToc"] else 0
        with_pagecount += 1 if entry["totalPages"] else 0
        with_subjects += 1 if entry["subjects"] else 0
        print("  [%2d/%d] %s  %s%s %4sp  %s" % (
            index, len(CATALOG), isbn,
            "OK " if record else "-- ",
            "toc" if entry["realToc"] else "   ",
            entry["totalPages"] or "?", title[:52]))

    payload = {
        "generated": date.today().isoformat(),
        "source": "openlibrary.org /api/books (jscmd=data)",
        "note": (
            "SINH TỰ ĐỘNG bởi scripts/seed/crawl_book_previews.py — đừng sửa tay. "
            "Tiêu đề, tác giả, nhà xuất bản, năm, số trang và mục lục là dữ kiện thư mục lấy từ "
            "Open Library. Văn xuôi trong các trang chương do máy sinh, KHÔNG phải nội dung thật "
            "của sách."
        ),
        "books": books,
    }
    OUT_PATH.parent.mkdir(parents=True, exist_ok=True)
    OUT_PATH.write_text(json.dumps(payload, ensure_ascii=False, indent=1) + "\n",
                        encoding="utf-8", newline="\n")

    print()
    print("%s  —  %d quyển, %.0f KB" % (
        OUT_PATH.relative_to(ROOT), len(books), OUT_PATH.stat().st_size / 1024))
    print("Open Library trả dữ liệu: %d/%d   mục lục thật: %d/%d   số trang thật: %d/%d   "
          "chủ đề thật: %d/%d" % (
              hits, len(CATALOG), with_toc, len(CATALOG), with_pagecount, len(CATALOG),
              with_subjects, len(CATALOG)))
    if hits < len(CATALOG):
        print("Những quyển không có dữ liệu vẫn đủ bốn trang (mục lục dựng từ chủ đề). "
              "Xoá scripts/seed/.cache/openlibrary/ rồi chạy lại nếu nghi do mạng.")


if __name__ == "__main__":
    main()
