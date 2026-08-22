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

FROM eclipse-temurin:17-jre
WORKDIR /app

# Chạy bằng người dùng không đặc quyền. Mặc định container chạy bằng root, nghĩa là một lỗ hổng
# thực thi mã tuỳ ý trong ứng dụng sẽ có luôn quyền root bên trong container — và với những cấu
# hình sai phổ biến (mount docker socket, thêm capability) thì đó là bàn đạp ra khỏi container.
RUN groupadd --system --gid 1001 socialapp \
 && useradd --system --uid 1001 --gid socialapp --no-create-home socialapp

COPY --from=build /app/build/libs/*.jar app.jar

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
# start-period 90s vì lần khởi động đầu phải chạy Flyway trên 44 migration.
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
  CMD curl -fsS http://localhost:8080/actuator/health/readiness || exit 1

# Dạng shell, không dùng exec form, để $JAVA_OPTS được khai triển. Dùng `exec` để java trở thành
# PID 1 và nhận trực tiếp SIGTERM từ `docker stop` — nếu không, shell giữ PID 1, nuốt tín hiệu, và
# container luôn bị giết cứng sau 10 giây thay vì tắt êm.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
