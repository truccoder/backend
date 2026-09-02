FROM eclipse-temurin:17-jdk AS build
WORKDIR /app
COPY gradle gradle
COPY gradlew build.gradle settings.gradle ./
# Nạp sẵn phụ thuộc thành một lớp riêng, để lần build sau chỉ tải lại khi build.gradle đổi.
# `|| true` là có chủ đích: task `dependencies` được chạy thuần tuý để làm nóng cache, và nó thất
# bại trong vài trường hợp vô hại (ví dụ chưa có thư mục src). Bước bootJar bên dưới mới là bước
# thật sự phải thành công.
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon || true
COPY src src
RUN ./gradlew bootJar -x test --no-daemon

# ── Ảnh seed: tải NGAY TRÊN RUNNER rồi nướng vào image ────────────────────────────────────────
#
# generate-seed-objects.py chỉ dùng thư viện chuẩn (urllib/zlib/struct/zipfile) nên python:*-alpine
# chạy được ngay, không cần pip. Chạy ở ĐÂY — trên máy build, nơi đường ra
# DiceBear/Picsum/Pravatar/Open Library sạch và nhanh — thay vì để MinIOSeedObjectInitializer tải
# ~980 ảnh từ VPS lúc khởi động: ở đó các nguồn công cộng bóp băng thông IP datacenter, timeout,
# và một phần lớn ảnh rơi về ô màu. Kết quả copy vào /app/seed-objects; initializer đọc thẳng từ
# đó (minio.seed-objects-dir), không gọi mạng.
#
# `|| true`: script tự rơi về file mẫu khi một nguồn hỏng và vẫn thoát 0; thêm `|| true` chỉ để
# một lỗi hạ tầng hiếm gặp không làm đỏ cả bản build — initializer trên VPS vẫn còn đường tải lại.
FROM python:3.12-alpine AS seed-objects
COPY docker/minio/generate-seed-objects.py /seed/generate-seed-objects.py
COPY src/main/resources/db/seed/seed-manifest.tsv /manifest/seed-manifest.tsv
RUN mkdir -p /objects /cache && python /seed/generate-seed-objects.py || true

FROM eclipse-temurin:17-jre
WORKDIR /app

# Chạy bằng người dùng không đặc quyền. Mặc định container chạy bằng root, nghĩa là một lỗ hổng
# thực thi mã tuỳ ý trong ứng dụng sẽ có luôn quyền root bên trong container — và với những cấu
# hình sai phổ biến (mount docker socket, thêm capability) thì đó là bàn đạp ra khỏi container.
RUN groupadd --system --gid 1001 socialapp \
 && useradd --system --uid 1001 --gid socialapp --no-create-home socialapp

COPY --from=build /app/build/libs/*.jar app.jar

# Ảnh seed thật, tải sẵn ở stage trên. MinIOSeedObjectInitializer đọc từ đây trước khi nghĩ tới
# việc gọi mạng (xem minio.seed-objects-dir trong application-prod.yml).
COPY --from=seed-objects /objects /app/seed-objects

# TomcatConfig ghi thư mục làm việc của Tomcat vào ./.tomcat-temp, tương đối so với thư mục hiện
# hành. Người dùng socialapp phải ghi được vào đó, nếu không ứng dụng chết ngay lúc khởi động.
RUN mkdir -p /app/.tomcat-temp && chown -R socialapp:socialapp /app
USER socialapp

EXPOSE 8080

# MaxRAMPercentage thay cho -Xmx cố định: JVM đọc giới hạn bộ nhớ của cgroup nên con số này bám
# theo `mem_limit` đặt ở compose, không phải sửa lại ở hai nơi.
#
# 75% chứ không phải 100%: ngoài heap, tiến trình còn cần metaspace, stack luồng, bộ đệm trực
# tiếp và code cache. Đặt sát trần là bị OOM-killer của nhân hệ điều hành giết — kiểu chết không
# để lại stack trace nào, chỉ là container biến mất.
#
# Không có tuỳ chọn này, JVM lấy mặc định 25% RAM mà nó NHÌN THẤY. Trên VPS 4 GB không đặt
# mem_limit, cả sáu dịch vụ đều tưởng mình được dùng 4 GB và cùng nhau vượt quá.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"

# Hỏi nhóm readiness, không hỏi /actuator/health tổng.
#
# Khác biệt quan trọng: nhóm readiness gồm readinessState và db (xem application.yml), nên
# healthcheck này báo hỏng đúng khi container thật sự không phục vụ được. /actuator/health tổng
# thì gộp cả Redis, Neo4j và MinIO — một cú chớp của Redis sẽ làm container bị đánh dấu unhealthy
# dù phần lớn API vẫn chạy tốt.
#
# start-period dài vì LẦN KHỞI ĐỘNG ĐẦU TIÊN trên một database trống phải chạy Flyway qua 54
# migration CỘNG khoảng 15 MB dữ liệu seed — application-prod.yml có `classpath:db/seed`, nên
# production nạp cả bộ 500 người dùng chứ không chỉ schema. Đo trên Testcontainers cục bộ là ~25
# giây; qua pooler của Supabase thì chậm hơn nhiều lần.
#
# Trong start-period, một lần kiểm thất bại KHÔNG tính vào retries và container vẫn ở trạng thái
# "starting". Nên đặt rộng tay ở đây không làm chậm gì khi mọi thứ nhanh: container chuyển sang
# healthy ngay ở lần kiểm thành công đầu tiên. Đặt hẹp thì ngược lại — container bị đánh dấu
# unhealthy giữa lúc Flyway vẫn đang chạy đúng, và deploy quay lui một bản hoàn toàn lành lặn.
#
# 240s nằm gọn trong ngân sách 300s mà .github/workflows/deploy.yml chờ.
HEALTHCHECK --interval=30s --timeout=5s --start-period=240s --retries=3 \
  CMD curl -fsS http://localhost:8080/actuator/health/readiness || exit 1

# Dạng shell, không dùng exec form, để $JAVA_OPTS được khai triển. Dùng `exec` để java trở thành
# PID 1 và nhận trực tiếp SIGTERM từ `docker stop` — nếu không, shell giữ PID 1, nuốt tín hiệu, và
# container luôn bị giết cứng sau 10 giây thay vì tắt êm.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
