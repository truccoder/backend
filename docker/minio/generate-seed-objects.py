#!/usr/bin/env python3
"""Sinh file mẫu cho các object key mà bộ seed SQL trỏ tới.

TẠI SAO PHẢI CÓ BƯỚC NÀY. Flyway ghi vào `t_books.file_key`, `t_users.profile_picture_url` và
`t_posts.images` những chuỗi trỏ tới object trong MinIO, mà SQL thì không tạo được object. Không
có bước này thì danh sách sách vẫn hiện đủ nhưng bìa, avatar và ảnh bài viết đều 404 — tệ hơn cả
việc không seed, vì URL vẫn tồn tại nên trình duyệt hiện ảnh vỡ thay vì rơi về chữ viết tắt.

KEY KHÔNG CHÉP TAY MÀ ĐỌC THẲNG TỪ FILE SQL. Sửa danh sách sách trong SQL rồi `docker compose up`
là khớp; không có đường nào để hai bên lệch nhau.

CHỈ DÙNG THƯ VIỆN CHUẨN (zlib, struct, zipfile) nên image `python:*-alpine` chạy được ngay, không
cần `pip install` — tức `docker compose up` không phụ thuộc vào mạng.

Trước đây đây là `scripts/seed/load-minio-objects.sh`, chạy bằng tay. Nó bị xoá vì một bước bắt
buộc mà phải nhớ gọi thì sớm muộn cũng có người quên: mọi máy dev chưa chạy nó đều thấy gian sách
hỏng. Giờ phần sinh file nằm ở đây và phần tải lên nằm trong `minio-init`, cả hai chạy từ
`docker compose up`.
"""

import os
import re
import struct
import sys
import zipfile
import zlib
from io import BytesIO

SQL_ROOT = "/sql"
OUT_ROOT = "/objects"

# Quét riêng từng file chứ không gộp một biểu thức: mỗi tiền tố đi về một bucket khác nhau, nên
# một key xuất hiện nhầm file là một object nằm sai chỗ.
SOURCES = [
    ("seed/V55__seed_bookstore.sql", r"(?:books|previews|covers)/[0-9]+/[A-Za-z0-9._-]+"),
    ("seed-dev/V66__seed_dev_avatars.sql", r"avatars/[0-9]+/[A-Za-z0-9._-]+"),
    ("seed-dev/V69__seed_dev_post_images.sql", r"posts/[0-9]+/[A-Za-z0-9._-]+"),
]

# HAI key cố ý KHÔNG được nạp, cả hai đều để dựng lại một nhánh lỗi:
#
#   - quyển sách 3021 trong V65 (nhánh "kho lưu trữ hỏng"). Nằm ngoài diện quét vì V65 không có
#     trong SOURCES — nếu sau này thêm file nguồn thì đừng thêm V65 vào.
#   - bài 5310 trong V69 (nhánh "ảnh không tải được"). Key này NẰM TRONG file đang được quét nên
#     phải loại ra bằng tay. Bỏ bộ lọc này đi là im lặng làm mất một ca kiểm thử, vì ảnh sẽ tải
#     được.
EXCLUDE = "khong-ton-tai"

# Bìa mỗi cuốn một màu khác nhau, để nhìn danh sách sách phân biệt được ngay chứ không phải một
# dãy ô giống hệt nhau.
PALETTE = [
    (37, 99, 235),
    (5, 150, 105),
    (219, 39, 119),
    (217, 119, 6),
    (124, 58, 237),
    (13, 148, 136),
    (190, 24, 93),
    (2, 132, 199),
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
        None,  # nội dung, điền bên dưới
        b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
    ]
    text = (
        b"BT /F1 16 Tf 60 760 Td (" + title.encode("ascii", "replace") + b") Tj ET\n"
        b"BT /F1 11 Tf 60 730 Td (File mau cho bo seed dev - khong phai noi dung that.) Tj ET\n"
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
        z.writestr(
            zipfile.ZipInfo("mimetype"),
            "application/epub+zip",
            compress_type=zipfile.ZIP_STORED,
        )
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
                ident=abs(hash(title)) % 10**8, title=title
            ),
        )
        z.writestr(
            "OEBPS/chapter1.xhtml",
            '<?xml version="1.0" encoding="utf-8"?>\n'
            '<html xmlns="http://www.w3.org/1999/xhtml"><head><title>'
            "{title}</title></head><body><h1>{title}</h1>"
            "<p>File mẫu cho bộ seed dev — không phải nội dung thật.</p>"
            "</body></html>\n".format(title=title),
        )
    return buf.getvalue()


def solid_png(width, height, rgb):
    """PNG đặc một màu, ghi thủ công (zlib + struct) để không cần Pillow."""
    raw = b"".join(b"\x00" + bytes(rgb) * width for _ in range(height))

    def chunk(tag, data):
        return (
            struct.pack(">I", len(data))
            + tag
            + data
            + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)
        )

    return (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
        + chunk(b"IDAT", zlib.compress(raw, 9))
        + chunk(b"IEND", b"")
    )


def collect_keys():
    keys = set()
    for relative, pattern in SOURCES:
        path = os.path.join(SQL_ROOT, relative)
        if not os.path.isfile(path):
            sys.exit("Không tìm thấy %s" % path)
        with open(path, encoding="utf-8") as fh:
            for match in re.findall(pattern, fh.read()):
                if EXCLUDE not in match:
                    keys.add(match)
    return sorted(keys)


def main():
    keys = collect_keys()
    print("    tìm thấy %d key" % len(keys), flush=True)

    for i, key in enumerate(keys):
        dest = os.path.join(OUT_ROOT, key)
        os.makedirs(os.path.dirname(dest), exist_ok=True)
        stem = os.path.splitext(os.path.basename(key))[0]
        stem = stem.replace("seed-", "").replace("-", " ").title()

        if key.startswith("posts/"):
            # Ngang 16:9, khác cả avatar (vuông) lẫn bìa sách (dọc): ảnh trong bài hiện trong một
            # lưới ngang, và một ảnh dọc ở đó sẽ bị cắt trên dưới nên không kiểm được bố cục thật.
            data = solid_png(640, 360, PALETTE[i % len(PALETTE)])
        elif key.startswith("avatars/"):
            # Vuông, không phải tỉ lệ bìa sách: avatar được cắt tròn ở client, và một ảnh 400x560
            # cắt tròn sẽ mất phần trên dưới, trông như ảnh lỗi chứ không như ảnh đại diện.
            data = solid_png(256, 256, PALETTE[i % len(PALETTE)])
        elif key.endswith(".png"):
            data = solid_png(400, 560, PALETTE[i % len(PALETTE)])
        elif key.endswith(".epub"):
            data = minimal_epub(stem)
        else:
            data = minimal_pdf(stem)

        with open(dest, "wb") as fh:
            fh.write(data)

    print("    đã sinh %d file" % len(keys), flush=True)


if __name__ == "__main__":
    main()
