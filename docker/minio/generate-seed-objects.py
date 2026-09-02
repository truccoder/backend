#!/usr/bin/env python3
"""Chuẩn bị file cho mọi object MinIO mà bộ seed SQL trỏ tới.

TẠI SAO PHẢI CÓ BƯỚC NÀY. Flyway ghi vào `t_books.*_key`, `t_users.profile_picture_url`,
`t_users.cover_image_url` và `t_posts.images` những chuỗi trỏ tới object trong MinIO, mà SQL thì
không tạo được object. Không có bước này thì danh sách sách vẫn hiện đủ nhưng bìa, avatar và ảnh
bài viết đều 404 — tệ hơn cả việc không seed, vì URL vẫn tồn tại nên trình duyệt hiện ảnh vỡ thay
vì rơi về chữ viết tắt.

ĐỌC MANIFEST, KHÔNG GREP SQL NỮA. Bản trước quét key bằng biểu thức chính quy trên chính file .sql.
Cách đó vỡ mỗi khi định dạng SQL đổi — mà định dạng SQL do generator quyết định, nên hai bên lệch
nhau được mà không ai biết. Nay `scripts/seed/generate_seed.py` xuất
`src/main/resources/db/seed/seed-manifest.tsv` trong cùng một lần chạy với các file SQL, và đây là
hợp đồng giữa hai bên. Manifest thiếu thì dừng ngay, không đoán.

CHỈ CÓ Ở MÁY DEV. File này chạy trong docker-compose của repo backend. Production không chạy compose
nào của repo này — bên đó `MinIOSeedObjectInitializer` (Java) làm đúng việc này lúc khởi động, đọc
cùng manifest đã đóng vào jar. Sửa logic ở đây thì soi lại lớp Java kia.

ẢNH THẬT, CÓ DỰ PHÒNG. Mỗi dòng manifest có thể kèm một URL nguồn (DiceBear, Pravatar, Picsum, bìa
sách Open Library). Tải về được thì dùng ảnh thật; mất mạng, hết giờ hay 404 thì rơi về bộ sinh
PNG/PDF/EPUB nội tuyến bên dưới. KHÔNG BAO GIỜ để trống một key, và luôn in ra bảng tổng kết bao
nhiêu ảnh thật / bao nhiêu ảnh dự phòng — đó là thứ duy nhất cho biết bộ ảnh đang xem là thật hay
là ô màu.

CHỈ DÙNG THƯ VIỆN CHUẨN (urllib, zlib, struct, zipfile) nên image `python:*-alpine` chạy được ngay,
không cần `pip install`.

CACHE Ở `/cache` (bind mount `./docker/minio/.cache`). Lần chạy thứ hai không cần mạng và cho ra
đúng bộ ảnh cũ, nên `docker compose up` vẫn tự chủ khi ngắt mạng — chỉ lần đầu tiên là cần.
"""

import os
import struct
import sys
import urllib.error
import urllib.request
import zipfile
import zlib
from concurrent.futures import ThreadPoolExecutor
from io import BytesIO

MANIFEST_PATH = "/manifest/seed-manifest.tsv"
OUT_ROOT = "/objects"
CACHE_ROOT = "/cache"

# Đủ lâu để một ảnh bìa sách tải xong trên mạng chậm, đủ ngắn để một nguồn treo không giữ chỗ mãi.
TIMEOUT_SECONDS = 8

# TẢI SONG SONG, và con số 4 là con số ĐO ĐƯỢC, không phải chọn cho đẹp.
#
# Bản tuần tự chạy khoảng 22 object mỗi phút — hơn NỬA TIẾNG cho 1.139 object, trong khi
# `minio-init` chờ service này bằng `service_completed_successfully`. Nghĩa là `docker compose up`
# đứng im suốt ngần ấy thời gian và trông y hệt như treo.
#
# Nhưng tăng số luồng KHÔNG đơn điệu tốt lên. Đo trên 120 object đầu của manifest, cache trống:
#
#      4 luồng   120/120 ảnh thật   14 giây
#      8 luồng   118/120            17 giây
#     12 luồng   101/120            10 giây
#     24 luồng    24/120             4 giây
#
# Nút thắt là giới hạn tốc độ THEO NGUỒN, không phải băng thông. Bốn nguồn đều là dịch vụ công
# cộng miễn phí; đẩy mạnh tay thì chúng bắt đầu từ chối, mọi ảnh bị từ chối rơi về bản dự phòng,
# và ta mất đúng thứ đang cố lấy — nhanh hơn để nhận về một bộ ô màu thì nhanh để làm gì.
#
# 4 luồng cho 1.139 object rơi vào khoảng hai tới bốn phút. Số đo ở trên lấy trên phần đầu
# manifest vốn toàn avatar DiceBear; bìa sách Open Library chậm hơn, nên hãy coi đây là cận dưới.
FETCH_WORKERS = 4

# Ảnh tải về nhỏ hơn ngưỡng này bị coi là hỏng. Open Library trả HTTP 200 kèm một ảnh placeholder
# 1x1 khi không có bìa cho ISBN đó, nên chỉ kiểm mã trạng thái là không đủ.
MIN_REAL_IMAGE_BYTES = 2000

# Bìa mỗi thứ một màu, để nhìn danh sách phân biệt được ngay chứ không phải một dãy ô giống hệt.
PALETTE = [
    (37, 99, 235), (5, 150, 105), (219, 39, 119), (217, 119, 6),
    (124, 58, 237), (13, 148, 136), (190, 24, 93), (2, 132, 199),
]


def minimal_pdf(title):
    """PDF một trang hợp lệ, dựng thủ công để không cần thư viện ngoài.

    Bảng xref phải trỏ đúng byte offset của từng object, nên offset được ghi lại trong lúc nối
    chuỗi chứ không tính trước — sai một byte là trình đọc PDF từ chối mở file.
    """
    objs = [
        b"<< /Type /Catalog /Pages 2 0 R >>",
        b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] "
        b"/Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R >>",
        None,
        b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
    ]
    text = (
        b"BT /F1 16 Tf 60 760 Td (" + title.encode("ascii", "replace") + b") Tj ET\n"
        b"BT /F1 11 Tf 60 730 Td (File mau cho bo seed - khong phai noi dung that.) Tj ET\n"
    )
    objs[3] = b"<< /Length " + str(len(text)).encode() + b" >>\nstream\n" + text + b"endstream"

    out = bytearray(b"%PDF-1.4\n")
    offsets = []
    for i, body in enumerate(objs, start=1):
        offsets.append(len(out))
        out += str(i).encode() + b" 0 obj\n" + body + b"\nendobj\n"

    xref_at = len(out)
    out += b"xref\n0 " + str(len(objs) + 1).encode() + b"\n0000000000 65535 f \n"
    for off in offsets:
        out += ("%010d 00000 n \n" % off).encode()
    out += (
        b"trailer\n<< /Size " + str(len(objs) + 1).encode() + b" /Root 1 0 R >>\n"
        b"startxref\n" + str(xref_at).encode() + b"\n%%EOF\n"
    )
    return bytes(out)


def minimal_epub(title):
    """EPUB hợp lệ tối thiểu.

    Đặc tả EPUB bắt buộc entry đầu tiên phải là `mimetype`, KHÔNG nén và không có extra field —
    trình đọc dựa vào đúng byte đó để nhận dạng định dạng. Vì vậy entry này ghi bằng ZIP_STORED
    trước mọi entry khác.
    """
    buf = BytesIO()
    with zipfile.ZipFile(buf, "w") as z:
        z.writestr(zipfile.ZipInfo("mimetype"), "application/epub+zip",
                   compress_type=zipfile.ZIP_STORED)
        z.writestr(
            "META-INF/container.xml",
            '<?xml version="1.0"?>\n'
            '<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">\n'
            '  <rootfiles><rootfile full-path="OEBPS/content.opf" '
            'media-type="application/oebps-package+xml"/></rootfiles>\n</container>\n',
        )
        z.writestr(
            "OEBPS/content.opf",
            '<?xml version="1.0" encoding="utf-8"?>\n'
            '<package xmlns="http://www.idpf.org/2007/opf" version="3.0" '
            'unique-identifier="bookid">\n'
            '  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">\n'
            '    <dc:identifier id="bookid">seed-{ident}</dc:identifier>\n'
            "    <dc:title>{title}</dc:title>\n"
            "    <dc:language>vi</dc:language>\n  </metadata>\n"
            '  <manifest><item id="c1" href="chapter1.xhtml" '
            'media-type="application/xhtml+xml"/></manifest>\n'
            '  <spine><itemref idref="c1"/></spine>\n</package>\n'.format(
                ident=abs(hash(title)) % 10**8, title=title),
        )
        z.writestr(
            "OEBPS/chapter1.xhtml",
            '<?xml version="1.0" encoding="utf-8"?>\n'
            '<html xmlns="http://www.w3.org/1999/xhtml"><head><title>'
            "{title}</title></head><body><h1>{title}</h1>"
            "<p>File mẫu cho bộ seed — không phải nội dung thật.</p>"
            "</body></html>\n".format(title=title),
        )
    return buf.getvalue()


def solid_png(width, height, rgb):
    """PNG đặc một màu, ghi thủ công (zlib + struct) để không cần Pillow."""
    raw = b"".join(b"\x00" + bytes(rgb) * width for _ in range(height))

    def chunk(tag, data):
        return (struct.pack(">I", len(data)) + tag + data
                + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF))

    return (b"\x89PNG\r\n\x1a\n"
            + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw, 9))
            + chunk(b"IEND", b""))


def read_manifest():
    if not os.path.isfile(MANIFEST_PATH):
        sys.exit(
            "DUNG - khong tim thay " + MANIFEST_PATH + "\n"
            "Manifest do scripts/seed/generate_seed.py sinh ra cung luc voi cac file SQL.\n"
            "Chay `python scripts/seed/generate_seed.py` truoc, roi `docker compose up` lai."
        )
    rows = []
    with open(MANIFEST_PATH, encoding="utf-8") as fh:
        for line in fh:
            line = line.rstrip("\n")
            if not line or line.startswith("#"):
                continue
            parts = line.split("\t")
            key = parts[0]
            kind = parts[1] if len(parts) > 1 else ""
            url = parts[2] if len(parts) > 2 and parts[2] else None
            rows.append((key, kind, url))
    return rows


def fetch(url):
    """Tải một ảnh về, trả bytes hoặc None. Không bao giờ ném ra ngoài."""
    try:
        request = urllib.request.Request(url, headers={"User-Agent": "elitenexus-seed/1.0"})
        with urllib.request.urlopen(request, timeout=TIMEOUT_SECONDS) as response:
            if response.status != 200:
                return None
            data = response.read()
        return data if len(data) >= MIN_REAL_IMAGE_BYTES else None
    except (urllib.error.URLError, OSError, ValueError):
        return None


def cache_path(key):
    return os.path.join(CACHE_ROOT, key.replace("/", "__"))


def load_cached(key):
    path = cache_path(key)
    if os.path.isfile(path) and os.path.getsize(path) >= MIN_REAL_IMAGE_BYTES:
        with open(path, "rb") as fh:
            return fh.read()
    return None


def store_cached(key, data):
    try:
        os.makedirs(CACHE_ROOT, exist_ok=True)
        with open(cache_path(key), "wb") as fh:
            fh.write(data)
    except OSError:
        pass  # Cache là tiện nghi, không phải điều kiện. Hỏng thì lần sau tải lại.


def placeholder_for(key, index):
    colour = PALETTE[index % len(PALETTE)]
    stem = os.path.splitext(os.path.basename(key))[0].replace("-", " ").title()
    if key.startswith("posts/") or key.startswith("covers-user/"):
        # Ngang 16:9 — ảnh trong bài và ảnh bìa trang cá nhân hiện trong khung ngang; một ảnh dọc
        # ở đó bị cắt trên dưới nên không kiểm được bố cục thật.
        return solid_png(640, 360, colour)
    if key.startswith("avatars/"):
        # Vuông: avatar bị cắt tròn ở client, ảnh dọc cắt tròn trông như ảnh lỗi.
        return solid_png(256, 256, colour)
    if key.startswith("covers/"):
        return solid_png(400, 560, colour)
    if key.endswith(".epub"):
        return minimal_epub(stem)
    return minimal_pdf(stem)


def prepare(item):
    """Lấy nội dung cho một object. Trả về (key, bytes, nguồn) với nguồn thuộc real/cache/gen.

    Chạy trong thread pool nên KHÔNG in gì và KHÔNG ném ra ngoài: một ảnh hỏng chỉ được rơi về bản
    dự phòng, chứ không được làm đổ cả lượt chuẩn bị.
    """
    index, (key, kind, url) = item
    if url:
        data = load_cached(key)
        if data is not None:
            return key, data, "cache"
        data = fetch(url)
        if data is not None:
            store_cached(key, data)
            return key, data, "real"
    return key, placeholder_for(key, index), "gen"


def main():
    rows = read_manifest()
    to_fetch = sum(1 for _, _, url in rows if url)
    cached_already = sum(1 for key, _, url in rows if url and load_cached(key) is not None)

    # MỌI DÒNG print CỦA FILE NÀY DÙNG ASCII, có chủ ý. Trong container Alpine thì stdout là UTF-8
    # và tiếng Việt in ra bình thường, nhưng cũng chính script này được chạy tay trên Windows để
    # thử — nơi stdout mặc định là cp1252 và một dấu tiếng Việt làm nó ném UnicodeEncodeError giữa
    # chừng, sau khi đã ghi được một phần số file. Chú thích thì tiếng Việt thoải mái; phần in ra
    # thì không.
    print("    manifest: %d object, %d can tai ve" % (len(rows), to_fetch), flush=True)
    if cached_already < to_fetch:
        # Nói trước, vì đây là chỗ `docker compose up` đứng lâu nhất, và im lặng ở đây trông y hệt
        # như treo. Đo thực tế: 1.139 object với cache trống mất khoảng 5-6 phút.
        print(
            "    %d object chua co trong cache - lan chay nay mat vai phut."
            " Lan sau doc cache, gan nhu tuc thi." % (to_fetch - cached_already),
            flush=True,
        )

    real = cached = generated = 0
    done = 0
    with ThreadPoolExecutor(max_workers=FETCH_WORKERS) as pool:
        for key, data, source in pool.map(prepare, enumerate(rows)):
            if source == "real":
                real += 1
            elif source == "cache":
                cached += 1
            else:
                generated += 1

            dest = os.path.join(OUT_ROOT, key)
            os.makedirs(os.path.dirname(dest), exist_ok=True)
            with open(dest, "wb") as fh:
                fh.write(data)

            done += 1
            if done % 200 == 0:
                print("    %d/%d" % (done, len(rows)), flush=True)

    total_real = real + cached
    print("    anh that:    %d  (tai moi %d, doc cache %d)" % (total_real, real, cached), flush=True)
    print("    anh du phong: %d" % generated, flush=True)
    if generated and total_real == 0:
        print("    LUU Y: khong tai duoc anh that nao. Kiem tra mang, hoac chap nhan anh sinh.",
              flush=True)


if __name__ == "__main__":
    main()
