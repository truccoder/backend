#!/usr/bin/env bash
#
# Nạp file mẫu lên MinIO cho bộ seed gian sách.
#
# V55__seed_bookstore.sql ghi vào t_books các cột file_key / cover_image_key / preview_file_key —
# đó là object trong MinIO, mà SQL thì không tạo được object. Không chạy script này thì danh sách
# sách vẫn hiện đầy đủ, nhưng bấm tải hoặc xem thử sẽ lỗi vì object không tồn tại.
#
# V66__seed_dev_avatars.sql (seed-dev) làm điều tương tự với t_users.profile_picture_url, và
# V69__seed_dev_post_images.sql với t_posts.images / article_details.coverImage /
# link_details.thumbnailUrl / t_users.cover_image_url — nên script này nạp cả ảnh đại diện lẫn
# ảnh trong bài.
#
# Các key KHÔNG chép tay vào đây mà được đọc thẳng từ file SQL, nên sửa danh sách sách trong SQL
# rồi chạy lại script là khớp, không có đường nào để hai bên lệch nhau.
#
# HAI key cố ý KHÔNG được nạp, cả hai đều để dựng lại một nhánh lỗi:
#
#   - quyển sách 3021 trong V65 (nhánh "kho lưu trữ hỏng"). Nằm ngoài diện quét vì script không
#     đọc V65 — nếu sau này thêm file nguồn thì đừng thêm V65 vào danh sách.
#   - bài 5310 trong V69 (nhánh "ảnh không tải được"). Key này NẰM TRONG file đang được quét,
#     nên nó phải được loại ra bằng tay — xem bộ lọc 'khong-ton-tai' bên dưới. Bỏ bộ lọc đó đi
#     là im lặng làm mất một ca kiểm thử, vì ảnh sẽ tải được.
#
#   bash scripts/seed/load-minio-objects.sh
#
# Ghi đè cấu hình khi cần:
#   MINIO_ALIAS_URL=http://localhost:9000 MINIO_ROOT_USER=... MINIO_ROOT_PASSWORD=... bash ...
#
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SQL_FILE="$REPO_ROOT/src/main/resources/db/seed/V55__seed_bookstore.sql"
AVATAR_SQL_FILE="$REPO_ROOT/src/main/resources/db/seed-dev/V66__seed_dev_avatars.sql"
POST_IMAGE_SQL_FILE="$REPO_ROOT/src/main/resources/db/seed-dev/V69__seed_dev_post_images.sql"

# Mặc định khớp docker-compose.yml dùng cho máy dev. Deployment thật dùng credential khác và
# script này cũng không dành cho deployment thật.
MINIO_CONTAINER="${MINIO_CONTAINER:-minio}"
MINIO_ROOT_USER="${MINIO_ROOT_USER:-minio_admin}"
MINIO_ROOT_PASSWORD="${MINIO_ROOT_PASSWORD:-minio_admin_password}"

BOOKS_BUCKET=books
COVERS_BUCKET=book-covers
# Khớp PROFILE_PICTURES_BUCKET trong ProfileService — bucket duy nhất trong ba cái được mở đọc
# công khai, vì ảnh đại diện phát thẳng bằng URL trong t_users chứ không qua presigned URL.
PICTURES_BUCKET=profile-pictures
# Khớp MEDIA_BUCKET trong MediaService — bucket của POST /v1/api/media. Công khai vì cùng lý do
# với profile-pictures: URL nằm thẳng trong t_posts.images và trình duyệt tải trực tiếp.
MEDIA_BUCKET=post-media

command -v docker >/dev/null || { echo "Cần có docker."; exit 1; }
PY="$(command -v python3 || command -v python || true)"
# Console mặc định của Windows là cp1252 và sẽ ném UnicodeEncodeError khi in tiếng Việt — sau khi
# file đã sinh xong, nên trông như script hỏng trong khi thực ra đã chạy gần đủ.
export PYTHONIOENCODING=utf-8
[ -n "$PY" ] || { echo "Cần có python3 để sinh file mẫu."; exit 1; }
[ -f "$SQL_FILE" ] || { echo "Không tìm thấy $SQL_FILE"; exit 1; }
[ -f "$AVATAR_SQL_FILE" ] || { echo "Không tìm thấy $AVATAR_SQL_FILE"; exit 1; }
[ -f "$POST_IMAGE_SQL_FILE" ] || { echo "Không tìm thấy $POST_IMAGE_SQL_FILE"; exit 1; }

# mc chạy trong container và nối vào ĐÚNG mạng docker mà MinIO đang ở, rồi gọi tới nó bằng tên
# service. Cách này tránh được chuyện host.docker.internal có trên Docker Desktop nhưng không có
# trên Linux, và cũng không phụ thuộc vào việc cổng 9000 có được publish ra host hay không.
NETWORK="$(docker inspect -f '{{range $k, $v := .NetworkSettings.Networks}}{{$k}}{{end}}' "$MINIO_CONTAINER" 2>/dev/null || true)"
if [ -z "$NETWORK" ]; then
  echo "Không tìm thấy container MinIO tên '$MINIO_CONTAINER'."
  echo "Khởi động hạ tầng dev trước:  docker compose up -d minio-server"
  exit 1
fi
MINIO_ALIAS_URL="${MINIO_ALIAS_URL:-http://${MINIO_CONTAINER}:9000}"

STAGING="$(mktemp -d)"
trap 'rm -rf "$STAGING"' EXIT

echo "==> Đọc object key từ $(basename "$SQL_FILE"), $(basename "$AVATAR_SQL_FILE") và $(basename "$POST_IMAGE_SQL_FILE")"

# Lấy mọi chuỗi dạng books/…, previews/…, covers/… trong file SQL sách, và avatars/… trong file
# ảnh đại diện. Quét riêng từng file chứ không gộp một biểu thức: mỗi tiền tố đi về một bucket
# khác nhau, nên một key xuất hiện nhầm file là một object nằm sai chỗ.
{
  grep -oE "(books|previews|covers)/[0-9]+/[A-Za-z0-9._-]+" "$SQL_FILE"
  grep -oE "avatars/[0-9]+/[A-Za-z0-9._-]+" "$AVATAR_SQL_FILE"
  # 'khong-ton-tai' là key của bài 5310, cố ý để trống trên kho lưu trữ — xem ghi chú đầu file.
  grep -oE "posts/[0-9]+/[A-Za-z0-9._-]+" "$POST_IMAGE_SQL_FILE" | grep -v "khong-ton-tai"
} | sort -u > "$STAGING/keys.txt"
echo "    tìm thấy $(wc -l < "$STAGING/keys.txt") key"

echo "==> Sinh file mẫu"

"$PY" - "$STAGING" <<'PYEOF'
import os, struct, sys, zlib, zipfile

staging = sys.argv[1]
keys = [k.strip() for k in open(os.path.join(staging, "keys.txt"), encoding="utf-8") if k.strip()]


def minimal_pdf(title: str) -> bytes:
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
    out += (b"trailer\n<< /Size " + str(len(objs) + 1).encode() + b" /Root 1 0 R >>\n"
            b"startxref\n" + str(xref_at).encode() + b"\n%%EOF\n")
    return bytes(out)


def minimal_epub(title: str) -> bytes:
    """EPUB hợp lệ tối thiểu.

    Đặc tả EPUB bắt buộc entry đầu tiên phải là `mimetype`, KHÔNG nén và không có extra field —
    trình đọc dựa vào đúng byte đó để nhận dạng định dạng. Vì vậy entry này ghi bằng ZIP_STORED
    trước mọi entry khác.
    """
    from io import BytesIO
    buf = BytesIO()
    with zipfile.ZipFile(buf, "w") as z:
        z.writestr(zipfile.ZipInfo("mimetype"), "application/epub+zip", compress_type=zipfile.ZIP_STORED)
        z.writestr("META-INF/container.xml",
                   '<?xml version="1.0"?>\n'
                   '<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">\n'
                   '  <rootfiles><rootfile full-path="OEBPS/content.opf" '
                   'media-type="application/oebps-package+xml"/></rootfiles>\n</container>\n')
        z.writestr("OEBPS/content.opf",
                   '<?xml version="1.0" encoding="utf-8"?>\n'
                   '<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bookid">\n'
                   '  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">\n'
                   f'    <dc:identifier id="bookid">seed-{abs(hash(title)) % 10**8}</dc:identifier>\n'
                   f'    <dc:title>{title}</dc:title>\n'
                   '    <dc:language>vi</dc:language>\n  </metadata>\n'
                   '  <manifest><item id="c1" href="chapter1.xhtml" media-type="application/xhtml+xml"/></manifest>\n'
                   '  <spine><itemref idref="c1"/></spine>\n</package>\n')
        z.writestr("OEBPS/chapter1.xhtml",
                   '<?xml version="1.0" encoding="utf-8"?>\n'
                   '<html xmlns="http://www.w3.org/1999/xhtml"><head><title>'
                   f'{title}</title></head><body><h1>{title}</h1>'
                   '<p>File mẫu cho bộ seed dev — không phải nội dung thật.</p>'
                   '</body></html>\n')
    return buf.getvalue()


def solid_png(width: int, height: int, rgb: tuple) -> bytes:
    """PNG đặc một màu, ghi thủ công (zlib + struct) để không cần Pillow."""
    raw = b"".join(b"\x00" + bytes(rgb) * width for _ in range(height))

    def chunk(tag: bytes, data: bytes) -> bytes:
        return (struct.pack(">I", len(data)) + tag + data
                + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF))

    return (b"\x89PNG\r\n\x1a\n"
            + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw, 9))
            + chunk(b"IEND", b""))


# Bìa mỗi cuốn một màu khác nhau, để nhìn danh sách sách phân biệt được ngay chứ không phải một
# dãy ô giống hệt nhau.
PALETTE = [(37, 99, 235), (5, 150, 105), (219, 39, 119), (217, 119, 6),
           (124, 58, 237), (13, 148, 136), (190, 24, 93), (2, 132, 199)]

for i, key in enumerate(keys):
    dest = os.path.join(staging, "objects", key)
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    stem = os.path.splitext(os.path.basename(key))[0].replace("seed-", "").replace("-", " ").title()

    if key.startswith("posts/"):
        # Ngang 16:9, khác cả avatar (vuông) lẫn bìa sách (dọc): ảnh trong bài hiện trong một
        # lưới ngang, và một ảnh dọc ở đó sẽ bị cắt trên dưới nên không kiểm được bố cục thật.
        data = solid_png(640, 360, PALETTE[i % len(PALETTE)])
    elif key.startswith("avatars/"):
        # Vuông, không phải tỉ lệ bìa sách: avatar được cắt tròn ở client, và một ảnh 400x560
        # cắt tròn sẽ mất phần trên dưới, khiến ảnh trông như bị lỗi chứ không như ảnh đại diện.
        data = solid_png(256, 256, PALETTE[i % len(PALETTE)])
    elif key.endswith(".png"):
        data = solid_png(400, 560, PALETTE[i % len(PALETTE)])
    elif key.endswith(".epub"):
        data = minimal_epub(stem)
    else:
        data = minimal_pdf(stem)

    with open(dest, "wb") as fh:
        fh.write(data)

print(f"    đã sinh {len(keys)} file")
PYEOF

echo "==> Tải lên MinIO qua $MINIO_ALIAS_URL (mạng docker: $NETWORK)"

# Đưa file vào container bằng LUỒNG TAR chứ không bind mount.
#
# Bind mount đòi đường dẫn đúng quy ước của Docker Engine, mà Git Bash trên Windows lại đưa ra
# đường dẫn kiểu MSYS (/tmp/...) — Docker Desktop hiểu đó là đường dẫn trong VM Linux, thấy thư
# mục rỗng, và mc báo "Object does not exist". `docker cp -` nhận tar qua stdin nên hoàn toàn
# không dính tới cách hệ điều hành viết đường dẫn.
#
# Bucket book-covers và books đều KHÔNG mở công khai ở đây: ảnh bìa lẫn nội dung sách đều được
# phát qua presigned URL do backend ký (BookStorageService), nên không cần policy công khai nào.
#
# profile-pictures và post-media thì ngược lại và bắt buộc phải mở: t_users.profile_picture_url là URL trần,
# không ký, trình duyệt tải thẳng — đúng như ProfileService.ensurePublicReadPolicy làm sau mỗi
# lần đổi ảnh. Không đặt policy thì ba tài khoản có ảnh sẽ hiện ảnh vỡ chứ không rơi về chữ
# viết tắt, vì URL vẫn tồn tại, chỉ là trả về 403.
UPLOAD_SCRIPT="
  set -e
  mc alias set seedminio '$MINIO_ALIAS_URL' '$MINIO_ROOT_USER' '$MINIO_ROOT_PASSWORD' >/dev/null
  mc mb --ignore-existing seedminio/$BOOKS_BUCKET    >/dev/null
  mc mb --ignore-existing seedminio/$COVERS_BUCKET   >/dev/null
  mc mb --ignore-existing seedminio/$PICTURES_BUCKET >/dev/null
  mc mb --ignore-existing seedminio/$MEDIA_BUCKET    >/dev/null
  mc cp --recursive --quiet /objects/books/    seedminio/$BOOKS_BUCKET/books/       >/dev/null
  mc cp --recursive --quiet /objects/previews/ seedminio/$BOOKS_BUCKET/previews/    >/dev/null
  mc cp --recursive --quiet /objects/covers/   seedminio/$COVERS_BUCKET/covers/     >/dev/null
  mc cp --recursive --quiet /objects/avatars/  seedminio/$PICTURES_BUCKET/avatars/  >/dev/null
  mc cp --recursive --quiet /objects/posts/    seedminio/$MEDIA_BUCKET/posts/       >/dev/null
  mc anonymous set download seedminio/$PICTURES_BUCKET >/dev/null
  mc anonymous set download seedminio/$MEDIA_BUCKET    >/dev/null
  echo \"    $BOOKS_BUCKET:            \$(mc ls --recursive seedminio/$BOOKS_BUCKET    | wc -l) object\"
  echo \"    $COVERS_BUCKET:      \$(mc ls --recursive seedminio/$COVERS_BUCKET   | wc -l) object\"
  echo \"    $PICTURES_BUCKET: \$(mc ls --recursive seedminio/$PICTURES_BUCKET | wc -l) object\"
  echo \"    $MEDIA_BUCKET:       \$(mc ls --recursive seedminio/$MEDIA_BUCKET    | wc -l) object\"
"

CID="$(docker create --network "$NETWORK" --entrypoint sh minio/mc:latest -c "$UPLOAD_SCRIPT")"
trap 'rm -rf "$STAGING"; docker rm -f "$CID" >/dev/null 2>&1 || true' EXIT

tar -C "$STAGING" -cf - objects | docker cp - "$CID:/"
docker start -a "$CID"

echo "==> Xong."
