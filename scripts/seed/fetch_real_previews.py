#!/usr/bin/env python3
"""Nạp đè văn xuôi THẬT vào hai trang "mở đầu chương một" mà crawl_book_previews.py đã sinh.

VÌ SAO CÓ FILE NÀY. crawl_book_previews.py cố tình không dùng `excerpts` mà Open Library trả về
(đó là trích nguyên văn có bản quyền) và thay bằng văn xuôi MÁY SINH — xem docstring của file đó.
File này đi xa hơn: với những quyển có nguồn xem trước THẬT SỰ hợp pháp (sách toàn văn tác giả/NXB
tự công khai miễn phí, hoặc sample chapter PDF NXB tự đăng để tải), nó thay văn xuôi máy sinh bằng
đúng văn bản thật của quyển đó. Quyết định 2026-09-03, sau khi rà tay từng nguồn — KHÔNG suy đoán
URL, KHÔNG dùng Google Books/Look Inside (điều khoản dịch vụ cấm tải lại), KHÔNG dùng trang lậu.

10 QUYỂN CÓ NGUỒN THẬT — xem REAL_SOURCES bên dưới để biết URL đã kiểm chứng (còn sống, đúng đầu
sách, content-type/license hợp lệ) của từng quyển.

67 QUYỂN CÒN LẠI không có nguồn thật đã xác minh. Thay vì giữ văn xuôi máy sinh, mỗi quyển MƯỢN
một đoạn văn thật từ một trong 10 nguồn trên (chọn xác định theo ISBN của chính quyển đó, nên chạy
lại vẫn ra đúng kết quả cũ) — một đoạn văn thật của quyển KHÁC còn hơn một đoạn máy sinh vô hồn.
Trường "pooledFrom" trong book-previews.json ghi rõ mượn từ ISBN nào, và trang bìa lót tự nói rõ
điều đó — không để người đọc tưởng nhầm đó là văn của chính quyển.

NGUỒN ĐÃ THỬ VÀ LOẠI, GHI LẠI ĐỂ KHỎI THỬ LẠI:
  - "The Go Programming Language" (9780134190440) — Pearson có sample PDF thật (tải được, xem
    scripts/seed/book_catalog.py), nhưng font nhúng trong đúng file PDF này làm pypdf tách chữ sai
    thành từng cụm vô nghĩa ("Thisc hapt eri sat ouro" thay vì "This chapter is a tour") ở MỌI
    extraction_mode đã thử (plain, layout) — lỗi nằm ở bảng width/cmap của font, không phải ở cách
    gọi. Không đáng công sửa cho một quyển.
  - "Penetration Testing" (9781593275648) và "Linux Basics for Hackers" (9781718503540) — No Starch
    Press có sample chapter PDF thật (nostarch.com/download/PenetrationTesting_ch09.pdf và
    nostarch.com/download/LinuxBasicsForHackers_Sample_Ch8.pdf), nhưng nostarch.com chặn bằng
    Cloudflare bot-check (trả trang "Just a moment..." thay vì PDF) — không tải được bằng script,
    chỉ tải được qua trình duyệt thật. Muốn thêm hai quyển này thì tải tay một lần rồi bỏ file .pdf
    vào cạnh script này, sửa REAL_SOURCES trỏ vào file cục bộ.

CHẠY MỘT LẦN LÚC SOẠN, giống crawl_book_previews.py — không chạy lúc `docker compose up`. Yêu cầu
`pip install pypdf`; generate-seed-objects.py chạy trong Docker image vẫn không cần package ngoài
vì nó chỉ đọc book-previews.json đã có sẵn, không tự tải hay tự phân tích PDF.

    python scripts/seed/crawl_book_previews.py     # bước 1: dữ kiện thư mục (nếu chưa chạy)
    pip install -r scripts/seed/requirements.txt
    python scripts/seed/fetch_real_previews.py     # bước 2: nạp đè nội dung chương thật (file này)

CACHE Ở `scripts/seed/.cache/real-previews/`, giống crawl_book_previews.py, vì lý do giống hệt:
lần chạy thứ hai không cần mạng.
"""

import json
import random
import re
import sys
import urllib.error
import urllib.request
from io import BytesIO
from pathlib import Path

sys.stdout.reconfigure(encoding="utf-8")
sys.stderr.reconfigure(encoding="utf-8")

ROOT = Path(__file__).resolve().parents[2]
PREVIEWS_PATH = ROOT / "src" / "main" / "resources" / "db" / "seed" / "book-previews.json"
CACHE_DIR = Path(__file__).resolve().parent / ".cache" / "real-previews"

USER_AGENT = "Mozilla/5.0 (compatible; elitenexus-seed/1.0)"
TIMEOUT_SECONDS = 25

# Mỗi trang "chương" hiển thị PARAGRAPHS_PER_PAGE đoạn (khớp docker/minio/generate-seed-objects.py
# và crawl_book_previews.py, để cỡ chữ/độ dài trang không đổi khi đổi nguồn nội dung).
PARAGRAPHS_PER_PAGE = 4
MIN_PARAGRAPHS_TO_ACCEPT_SOURCE = 4  # ít hơn thì trang thứ hai sẽ trống trơn, thà bỏ nguồn đó.

# (isbn, loại, url, ghi_chú_kiểm_chứng) — mỗi dòng đã tự tay mở URL kiểm tra còn sống và đúng nội
# dung trước khi đưa vào đây, không suy đoán.
REAL_SOURCES = [
    ("9781718503106", "html_p", "https://doc.rust-lang.org/book/foreword.html",
     "The Rust Programming Language — MIT/Apache-2.0, doc.rust-lang.org"),
    ("9781593279929", "html_p", "https://automatetheboringstuff.com/2e/chapter0/",
     "Automate the Boring Stuff — CC BY-NC-SA, tác giả tự đăng"),
    ("9781593279509", "html_p", "https://eloquentjavascript.net/00_intro.html",
     "Eloquent JavaScript — CC BY-NC, tác giả tự đăng"),
    ("9781484200766", "html_p", "https://git-scm.com/book/en/v2/Getting-Started-About-Version-Control",
     "Pro Git — CC BY-NC-SA 3.0, git-scm.com"),
    ("9781492082798", "html_p", "https://abseil.io/resources/swe-book/html/ch01.html",
     "Software Engineering at Google — Google tự đăng free"),
    ("9781491929124", "html_p", "https://sre.google/sre-book/introduction/",
     "Site Reliability Engineering — Google tự đăng free"),
    ("9781593273897", "html_p", "https://linuxcommand.org/tlcl.php",
     "The Linux Command Line — CC BY-NC-ND, tác giả tự đăng (trang giới thiệu, không phải chương 1)"),
    ("9780262035613", "html_reading_order", "https://www.deeplearningbook.org/contents/intro.html",
     "Deep Learning (Goodfellow/Bengio/Courville) — MIT Press cho đọc free vĩnh viễn"),
    ("9780132350884", "pdf", "https://ptgmedia.pearsoncmg.com/images/9780132350884/samplepages/9780132350884.pdf",
     "Clean Code — sample chapter chính thức của Pearson/InformIT"),
    ("9780321278654", "pdf", "https://ptgmedia.pearsoncmg.com/images/9780321278654/samplepages/9780321278654.pdf",
     "Extreme Programming Explained — sample chapter chính thức của Pearson/InformIT"),
]


# ── Tải, có cache ──────────────────────────────────────────────────────────────────────────────

def fetch_bytes(url):
    cached = CACHE_DIR / re.sub(r"[^A-Za-z0-9]+", "_", url)
    if cached.is_file():
        return cached.read_bytes()
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=TIMEOUT_SECONDS) as response:
        data = response.read()
    CACHE_DIR.mkdir(parents=True, exist_ok=True)
    cached.write_bytes(data)
    return data


# ── Dọn chữ ────────────────────────────────────────────────────────────────────────────────────

def clean_ws(text):
    return re.sub(r"\s+", " ", text).strip()


def strip_tags(html_fragment):
    text = re.sub(r"<[^>]+>", " ", html_fragment)
    return (text.replace("&amp;", "&").replace("&quot;", '"').replace("&#39;", "'")
            .replace("&rsquo;", "'").replace("&lsquo;", "'").replace("&ldquo;", '"')
            .replace("&rdquo;", '"').replace("&mdash;", "-").replace("&ndash;", "-")
            .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">"))


def fix_pdf_word_glitches(text):
    """PDF của Pearson tách quotation mark khỏi chữ ("What's" -> "What? s"); nối lại cho đọc được.

    Ligature fi/fl (ﬁ, ﬂ) thì KHÔNG cần sửa ở đây — pypdf trả đúng ký tự Unicode ligature thật, và
    ascii_only() bên generate-seed-objects.py đã tự decompose chúng về "fi"/"fl" qua NFKD.
    """
    return re.sub(r"(\w)\?\s(s|t|d|ll|re|ve|m)\b", r"\1'\2", text)


def sentence_chunks(text, target_chars=380, max_chunks=10):
    """Chia một khối văn liền thành các 'đoạn' cỡ target_chars, ngắt ở cuối câu.

    Dùng cho nguồn không có ranh giới đoạn thật (PDF trích xuất theo dòng, hoặc trang pdf2htmlEX
    ghép lại theo thứ tự đọc) — khác với html_p, nơi mỗi <p> đã là một đoạn thật.
    """
    text = clean_ws(text)
    sentences = re.split(r"(?<=[.!?])\s+", text)
    chunks, current = [], ""
    for sentence in sentences:
        candidate = sentence if not current else current + " " + sentence
        if len(candidate) >= target_chars and current:
            chunks.append(current)
            current = sentence
        else:
            current = candidate
    if current:
        chunks.append(current)
    return chunks[:max_chunks]


# ── Trích theo loại nguồn ──────────────────────────────────────────────────────────────────────

def extract_html_p(html, skip_prefixes=(), strip_substrings=(), min_len=60):
    html = re.sub(r"<(script|style)[^>]*>.*?</\1>", " ", html, flags=re.S | re.I)
    out = []
    for raw in re.findall(r"<p[^>]*>(.*?)</p>", html, flags=re.S | re.I):
        text = clean_ws(strip_tags(raw))
        for junk in strip_substrings:
            text = clean_ws(text.replace(junk, ""))
        if len(text) < min_len:
            continue
        if any(text.startswith(prefix) for prefix in skip_prefixes):
            continue
        out.append(text)
    return out


def extract_html_reading_order(html):
    """Dựng lại văn bản theo đúng thứ tự đọc từ trang pdf2htmlEX (mỗi dòng PDF là một <div>).

    Trang deeplearningbook.org/contents/*.html là PDF-to-HTML: không có <p> thật, mỗi dòng gốc của
    PDF là một `<div class="t ...">`. Thứ tự các div trong HTML trùng thứ tự đọc của trang gốc (PDF
    một cột, không multi-column), nên nối lại đơn giản là đủ — đã kiểm bằng mắt lúc soạn file này.
    """
    fragments = re.findall(r'<div class="t [^"]*">(.*?)</div>', html, flags=re.S)
    joined = " ".join(strip_tags(fragment) for fragment in fragments)
    return sentence_chunks(joined)


def looks_like_front_or_back_matter(page_text):
    """True nếu một trang PDF nhiều khả năng là bìa/lời khen/mục lục/index/trang trắng.

    Heuristic, không hoàn hảo — mục tiêu là bỏ qua đủ số trang rõ ràng KHÔNG PHẢI văn xuôi để hai
    trang đầu tiên còn lại luôn là nội dung thật, không phải chấm điểm chính xác từng trang.
    """
    if len(page_text) < 700:
        return True
    low = page_text.lower()
    if "this page intentionally left blank" in low:
        return True
    if "library of congress" in low:
        return True
    if low[:80].startswith("praise for"):
        return True
    if page_text.count("...") > 3:
        return True
    lines = [l for l in page_text.split("\n") if l.strip()]
    if len(lines) > 8:
        short_ratio = sum(1 for l in lines if len(l.strip()) < 45) / len(lines)
        if short_ratio > 0.6:  # mục lục / index: nhiều dòng ngắn liên tiếp
            return True
    return False


def extract_pdf(data):
    from pypdf import PdfReader  # import cục bộ: script chỉ đổ vỡ ở đây nếu thiếu, không phải lúc import file

    reader = PdfReader(BytesIO(data))
    prose_pages = []
    for page in reader.pages:
        text = (page.extract_text() or "").strip()
        if not looks_like_front_or_back_matter(text):
            prose_pages.append(text)
        if len(prose_pages) >= 2:
            break
    combined = fix_pdf_word_glitches("\n".join(prose_pages))
    return sentence_chunks(combined)


def extract(kind, url):
    if kind == "pdf":
        return extract_pdf(fetch_bytes(url))
    html = fetch_bytes(url).decode("utf-8", errors="replace")
    if kind == "html_reading_order":
        return extract_html_reading_order(html)
    if "doc.rust-lang.org" in url:
        # mdBook nhét cả nhãn nút chọn theme ("Auto Light Rust Coal Navy Ayu") vào chung một <p>
        # với đoạn văn thật đầu tiên — cắt bỏ cụm đó, giữ nguyên phần còn lại.
        return extract_html_p(
            html, skip_prefixes=("Press ",),
            strip_substrings=("Auto Light Rust Coal Navy Ayu The Rust Programming Language",),
        )
    return extract_html_p(html)


# ── Ráp vào cấu trúc trang đã có ──────────────────────────────────────────────────────────────

def split_two_pages(paragraphs):
    half = max(1, (len(paragraphs) + 1) // 2)
    first = paragraphs[:half][:PARAGRAPHS_PER_PAGE] or paragraphs[:1]
    second = paragraphs[half:][:PARAGRAPHS_PER_PAGE] or paragraphs[-1:]
    return first, second


def apply_content(book, paragraphs, is_real, source_label):
    first, second = split_two_pages(paragraphs)
    book["pages"][2]["paragraphs"] = first
    book["pages"][3]["paragraphs"] = second
    book["realContent"] = is_real
    if is_real:
        book["contentSource"] = source_label
        book["pages"][0]["paragraphs"][-1] = (
            "Sample pages prepared for the EliteNexus seed catalogue. The bibliographic details, "
            "the table of contents, and the excerpt on the following pages are all real, from "
            f"{source_label}."
        )
    else:
        book["pooledFrom"] = source_label
        book["pages"][0]["paragraphs"][-1] = (
            "Sample pages prepared for the EliteNexus seed catalogue. The bibliographic details "
            "and the table of contents are the real ones, from Open Library; no legally clear "
            "preview of THIS book was found, so the excerpt on the following pages is real prose "
            f"borrowed from another book in the catalogue ({source_label}) — not this book's own text."
        )


def main():
    payload = json.loads(PREVIEWS_PATH.read_text(encoding="utf-8"))
    books = payload["books"]

    real_paragraphs = {}
    print("Nguồn thật (%d):" % len(REAL_SOURCES))
    for isbn, kind, url, note in REAL_SOURCES:
        try:
            paragraphs = extract(kind, url)
        except (urllib.error.URLError, OSError, ValueError) as exc:
            print("  [--] %s  LỖI: %s" % (isbn, exc))
            continue
        status = "OK " if len(paragraphs) >= MIN_PARAGRAPHS_TO_ACCEPT_SOURCE else "ÍT "
        print("  [%s] %s  %d đoạn  %s" % (status, isbn, len(paragraphs), note))
        if len(paragraphs) >= MIN_PARAGRAPHS_TO_ACCEPT_SOURCE:
            real_paragraphs[isbn] = (paragraphs, url)

    if not real_paragraphs:
        sys.exit("DỪNG — không nguồn thật nào tải được, kiểm tra mạng rồi chạy lại.")

    pool_items = list(real_paragraphs.items())
    real_count = pooled_count = 0
    for isbn, book in books.items():
        if isbn in real_paragraphs:
            paragraphs, url = real_paragraphs[isbn]
            apply_content(book, paragraphs, is_real=True, source_label=url)
            real_count += 1
        else:
            pool_isbn, (paragraphs, _url) = pool_items[random.Random(int(isbn)).randrange(len(pool_items))]
            apply_content(book, paragraphs, is_real=False, source_label=pool_isbn)
            pooled_count += 1

    payload["realContentNote"] = (
        "SINH bởi scripts/seed/fetch_real_previews.py trên nền book-previews.json đã có. "
        "%d quyển dùng đúng văn bản thật của chính quyển (xem contentSource từng quyển); "
        "%d quyển còn lại mượn đoạn văn thật của một quyển khác trong 10 nguồn đã xác minh "
        "(xem pooledFrom từng quyển) — không phải văn xuôi máy sinh nữa."
        % (real_count, pooled_count)
    )
    PREVIEWS_PATH.write_text(
        json.dumps(payload, ensure_ascii=False, indent=1) + "\n", encoding="utf-8", newline="\n"
    )

    print()
    print("%s — %d/%d quyển có văn bản thật, %d quyển dùng đoạn mượn"
          % (PREVIEWS_PATH.relative_to(ROOT), real_count, len(books), pooled_count))


if __name__ == "__main__":
    main()
