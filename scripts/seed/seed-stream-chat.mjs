#!/usr/bin/env node
/**
 * Nạp dữ liệu chat vào Stream Chat cho bộ seed.
 *
 *     STREAM_API_KEY=... STREAM_API_SECRET=... node scripts/seed/seed-stream-chat.mjs --reset
 *
 * KHÔNG NẰM TRONG `docker compose up`, có chủ đích. Stream Chat là dịch vụ SaaS: mỗi lần chạy tiêu
 * quota thật của một tài khoản thật, và không có bản chạy cục bộ để trỏ vào. Một bước tự động âm
 * thầm đốt quota mỗi lần ai đó gõ `docker compose up` là thứ không nên tồn tại — nên đây là bước
 * bạn chủ động chạy, một lần, khi đã có key.
 *
 * ── Vì sao `--reset` tồn tại, và vì sao thiếu nó thì seed mới VÔ NGHĨA ───────────────────────
 * Id người dùng phía Stream chính là id số bên Postgres: `StreamChatService.issueToken` trả
 * `String.valueOf(user.getId())` và `StreamTokenSigner.userToken` ký claim `user_id` từ đúng số
 * đó. Các thế hệ seed đều nằm trong dải 9001+, tức là TRÙM LÊN NHAU. Nạp bộ mới mà không dọn
 * Stream thì người mới id 9005 nhận đúng token của người cũ id 9005, và
 * `queryChannels({ members: { $in: [myUserId] } })` phía frontend trả về nguyên phòng lẫn tin nhắn
 * của người cũ — "đổi tài khoản mà chat vẫn y như cũ".
 *
 * `V80__seed_reset.sql` dọn được chuyện này bên Postgres, nhưng Flyway không với tới một SaaS bên
 * ngoài. Không có bước nào khác trong repo chạm được vào Stream. Nên nó nằm ở đây.
 *
 * MẶC ĐỊNH TẮT, vì đây là thao tác phá huỷ trên một tài khoản thật. Đừng chạy vào app Stream có
 * người dùng thật.
 *
 * ── Vì sao KHÔNG dùng gói `stream-chat` ──────────────────────────────────────────────────────
 * Kế hoạch ban đầu là dùng SDK chính thức. Nó kéo theo `package.json`, `node_modules` và một
 * lockfile vào một repo Gradle thuần — cho một script chạy vài lần trong đời dự án. Node 18+ có
 * sẵn `fetch` toàn cục và `node:crypto` ký được HS256 trong mươi dòng, mà Stream chỉ cần đúng hai
 * thứ đó. Không phụ thuộc ngoài nghĩa là không có gì để cài, không có gì hỏng khi cài, và script
 * chạy được trên máy vừa clone repo về.
 *
 * ── Dữ liệu đến từ đâu ───────────────────────────────────────────────────────────────────────
 * Script này KHÔNG tự quyết định ai nhắn với ai. Nó đọc `scripts/seed/chat-plan.json`, do
 * `generate_seed.py` xuất ra CÙNG LƯỢT với `V82__seed_social_graph.sql` và `friend-graph.cypher`,
 * từ cùng một tập cạnh. Nhờ vậy không thể có phòng 1-1 giữa hai người chưa từng là bạn — một sai
 * lệch mà Stream không bao giờ báo, vì với Stream thì đó là một phòng hợp lệ.
 *
 * ── Thứ tự các bước là bắt buộc, không phải tuỳ ──────────────────────────────────────────────
 *   0. Dọn (chỉ khi --reset) — xoá cứng người dùng của dải seed rồi quét nốt phòng còn sót.
 *   1. Upsert người dùng   — Stream phải biết một người trước khi người đó vào phòng.
 *   2. Đẩy quan hệ chặn    — TRƯỚC khi tạo phòng. StreamChatService.createGroupChat lọc thành
 *                            viên theo quan hệ chặn; làm ngược lại thì bộ lọc không có gì để lọc.
 *   3. Tạo phòng 1-1 và nhóm.
 *   4. Rải tin nhắn.
 *
 * Chạy lại được KHI CÓ `--reset`: bước 0 đưa Stream về trạng thái trắng trước mỗi lượt nạp, nên
 * hai lần chạy cho ra đúng một kết quả. KHÔNG CÓ `--reset` thì upsert và tạo phòng vẫn là thao tác
 * đặt-lại-trạng-thái, nhưng TIN NHẮN THÌ KHÔNG — chạy hai lần là mỗi phòng có hai bản tin nhắn.
 * Dùng `--skip-messages` khi chỉ muốn đồng bộ lại hồ sơ và phòng.
 */

import crypto from "node:crypto";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const HERE = dirname(fileURLToPath(import.meta.url));
const PLAN_PATH = join(HERE, "chat-plan.json");
const BASE_URL = process.env.STREAM_BASE_URL ?? "https://chat.stream-io-api.com";

/**
 * Giá trị đổ vào `${minioUrl}` trong `chat-plan.json`.
 *
 * KẾ HOẠCH GIỮ PLACEHOLDER CHỨ KHÔNG GIỮ URL THẬT, cùng lý do với các file `.sql`: địa chỉ MinIO
 * khác nhau giữa máy dev và production nên không ghi cứng được. Flyway thay hộ placeholder cho
 * SQL (`spring.flyway.placeholders.minioUrl` trong `application.yml`); phía này không có Flyway,
 * nên script tự thay — và phải thay, nếu không avatar đẩy lên Stream là chuỗi `${minioUrl}/...`
 * thô và mọi ảnh trong chat đều vỡ.
 *
 * Mặc định khớp đúng `${MINIO_URL:http://localhost:9000}` bên `application.yml`, để máy dev không
 * phải khai gì. Sai giá trị này thì KHÔNG có gì báo lỗi: URL vẫn hợp lệ, chỉ trỏ vào nơi không có
 * object.
 */
const MINIO_URL = process.env.MINIO_URL ?? "http://localhost:9000";

// Khớp StreamChatClient.GROUP_CHANNEL_TYPE. Stream phân biệt loại kênh, và một loại khác sẽ tạo
// ra những phòng mà frontend không truy vấn tới.
const CHANNEL_TYPE = "messaging";

// Khớp StreamChatClient.MAX_USERS_PER_UPSERT. Stream từ chối lô lớn hơn.
const MAX_USERS_PER_UPSERT = 100;

// Dải id của bộ seed. Rộng hơn dải thật (9001-9500) đúng 99 chỗ, khớp V80__seed_reset.sql — để nếu
// sau này ai đó nới bộ seed thêm vài chục tài khoản thì bước dọn không phải sửa theo.
const SEED_ID_FROM = 9001;
const SEED_ID_TO = 9599;

// Stream chặn lô lớn hơn cho cả /users/delete lẫn /channels/delete.
const MAX_PER_DELETE = 100;

// Truy vấn phòng phía máy chủ trả tối đa 30 phòng một lượt.
const CHANNELS_PER_PAGE = 30;

// Xoá cứng là tác vụ bất đồng bộ: Stream trả task_id rồi làm sau. Chờ có giới hạn — quá hạn thì
// cảnh báo và đi tiếp, vì bỏ cuộc giữa chừng để lại trạng thái khó gỡ hơn nhiều so với chờ thiếu.
const TASK_POLL_ATTEMPTS = 60;
const TASK_POLL_INTERVAL_MS = 1000;

const apiKey = process.env.STREAM_API_KEY;
const apiSecret = process.env.STREAM_API_SECRET;
const skipMessages = process.argv.includes("--skip-messages");
const dryRun = process.argv.includes("--dry-run");
const reset = process.argv.includes("--reset");

if (!dryRun && (!apiKey || !apiSecret)) {
  console.error(
    "DUNG - thieu STREAM_API_KEY hoac STREAM_API_SECRET.\n" +
      "Hai bien nay lay tu bang dieu khien Stream, cung hai gia tri ma backend dung o\n" +
      "stream.chat.api-key / stream.chat.api-secret. Chay thu khong can key:\n" +
      "    node scripts/seed/seed-stream-chat.mjs --reset --dry-run",
  );
  process.exit(1);
}

/**
 * Token máy chủ cho Stream: JWT HS256 với đúng một claim `server: true`.
 *
 * Giống hệt StreamTokenSigner.serverToken() phía Java. Không có thời hạn, và đó là đúng đặc tả của
 * Stream cho token máy chủ — nó được bảo vệ bằng việc api_secret không rời khỏi máy chủ.
 */
function serverToken() {
  const b64 = (obj) =>
    Buffer.from(JSON.stringify(obj)).toString("base64url");
  const head = b64({ alg: "HS256", typ: "JWT" });
  const body = b64({ server: true });
  const sig = crypto
    .createHmac("sha256", apiSecret)
    .update(`${head}.${body}`)
    .digest("base64url");
  return `${head}.${body}.${sig}`;
}

const token = dryRun ? "dry-run" : serverToken();

let calls = 0;
let failures = 0;

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

async function call(path, body, { method = "POST" } = {}) {
  calls += 1;
  if (dryRun) {
    return { ok: true };
  }
  const url = `${BASE_URL}${path}${path.includes("?") ? "&" : "?"}api_key=${apiKey}`;
  const response = await fetch(url, {
    method,
    headers: {
      Authorization: token,
      "Stream-Auth-Type": "jwt",
      "Content-Type": "application/json",
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (!response.ok) {
    failures += 1;
    const text = await response.text();
    // In ra rồi ĐI TIẾP thay vì dừng: một phòng hỏng không đáng để mất toàn bộ phần còn lại, và
    // dừng giữa chừng để lại một trạng thái khó hơn nhiều so với một trạng thái đầy đủ có vài lỗ.
    console.error(`  ! ${response.status} ${path} — ${text.slice(0, 160)}`);
    return { ok: false, status: response.status, text };
  }
  return { ok: true, data: await response.json().catch(() => ({})) };
}

function isoDaysAgo(days) {
  return new Date(Date.now() - days * 86_400_000).toISOString();
}

/** Cắt một mảng thành các lô kích thước cố định. */
function batched(items, size) {
  const out = [];
  for (let from = 0; from < items.length; from += size) {
    out.push(items.slice(from, from + size));
  }
  return out;
}

/**
 * Chờ một tác vụ xoá chạy xong.
 *
 * Xoá cứng ở Stream là bất đồng bộ. Không chờ thì bước upsert ngay sau đó có thể chạy TRƯỚC khi
 * người dùng cũ thực sự biến mất, và Stream sẽ dựng lại đúng những gì vừa yêu cầu xoá.
 */
async function waitForTask(taskId, label) {
  if (dryRun || !taskId) return;
  for (let attempt = 0; attempt < TASK_POLL_ATTEMPTS; attempt += 1) {
    await sleep(TASK_POLL_INTERVAL_MS);
    const result = await call(`/tasks/${taskId}`, undefined, { method: "GET" });
    const status = result.ok ? result.data?.status : null;
    if (status === "completed") return;
    if (status === "failed") {
      console.error(`  ! tac vu ${label} that bai: ${JSON.stringify(result.data?.error ?? {})}`);
      return;
    }
  }
  console.warn(`  ! tac vu ${label} chua xong sau ${TASK_POLL_ATTEMPTS}s - di tiep.`);
}

/** Những id trong dải seed mà Stream thực sự đang biết. */
async function existingSeedUserIds() {
  const all = [];
  for (let id = SEED_ID_FROM; id <= SEED_ID_TO; id += 1) {
    all.push(String(id));
  }

  const found = [];
  // Hỏi theo lô thay vì nhét cả 599 id vào một URL: /users là GET, và payload đi trong query
  // string nên một lô lớn sẽ vượt giới hạn độ dài URL trước khi Stream kịp trả lời.
  for (const batch of batched(all, MAX_PER_DELETE)) {
    const payload = encodeURIComponent(
      JSON.stringify({ filter_conditions: { id: { $in: batch } }, limit: MAX_PER_DELETE }),
    );
    const result = await call(`/users?payload=${payload}`, undefined, { method: "GET" });
    if (!result.ok) {
      console.error("DUNG - khong liet ke duoc nguoi dung tren Stream, khong the --reset an toan.");
      process.exit(1);
    }
    for (const user of result.data?.users ?? []) {
      found.push(user.id);
    }
  }
  return found;
}

/** Mọi phòng `messaging` Stream đang giữ, lấy theo trang. */
async function allChannelCids() {
  const cids = [];
  for (let offset = 0; ; offset += CHANNELS_PER_PAGE) {
    const result = await call("/channels", {
      filter_conditions: { type: CHANNEL_TYPE },
      limit: CHANNELS_PER_PAGE,
      offset,
      state: false,
    });
    if (!result.ok) {
      console.error("DUNG - khong liet ke duoc phong tren Stream, khong the --reset an toan.");
      process.exit(1);
    }
    const page = result.data?.channels ?? [];
    for (const entry of page) {
      const cid = entry.channel?.cid;
      if (cid) cids.push(cid);
    }
    if (page.length < CHANNELS_PER_PAGE) break;
  }
  return cids;
}

/**
 * Bước 0 — đưa Stream về trạng thái trắng cho dải id của bộ seed.
 *
 * XOÁ CỨNG, KHÔNG PHẢI XOÁ MỀM, và đây không phải tuỳ chọn. Xoá mềm không giải phóng id: user
 * 9005 vẫn tồn tại ở trạng thái đã xoá và lần upsert sau không hồi sinh được — tức là kẹt lại đúng
 * cái trạng thái mà `--reset` sinh ra để thoát khỏi. Nên nếu Stream từ chối xoá cứng (app chưa bật
 * permanent user deletion), script DỪNG và nói rõ, chứ không âm thầm lùi về xoá mềm.
 *
 * Hai lượt, và cần cả hai. `conversations: "hard"` kéo theo những phòng do chính người đó tạo, tức
 * gần hết; lượt quét sau dọn nốt phòng do người ngoài dải tạo mà có thành viên trong dải.
 */
async function resetSeedData() {
  console.log(`0/4  --reset: don du lieu cu cua dai ${SEED_ID_FROM}-${SEED_ID_TO}...`);

  const userIds = await existingSeedUserIds();
  console.log(`     tim thay ${userIds.length} nguoi dung tren Stream, dang xoa cung...`);

  for (const batch of batched(userIds, MAX_PER_DELETE)) {
    const result = await call("/users/delete", {
      user_ids: batch,
      user: "hard",
      messages: "hard",
      conversations: "hard",
    });
    if (!result.ok) {
      console.error(
        "\nDUNG - Stream tu choi xoa cung nguoi dung.\n" +
          "Xoa mem KHONG giai phong id nen khong dung duoc o day: user 9005 se ket lai o trang\n" +
          "thai da xoa va lan upsert sau khong hoi sinh duoc.\n" +
          "Bat 'permanent user deletion' cho app trong bang dieu khien Stream roi chay lai.",
      );
      process.exit(1);
    }
    await waitForTask(result.data?.task_id, "xoa nguoi dung");
  }

  const cids = await allChannelCids();
  if (cids.length > 0) {
    console.log(`     con ${cids.length} phong sot lai, dang xoa cung...`);
    for (const batch of batched(cids, MAX_PER_DELETE)) {
      const result = await call("/channels/delete", { cids: batch, hard_delete: true });
      if (!result.ok) {
        console.error("DUNG - Stream tu choi xoa cung phong. Xem loi o tren.");
        process.exit(1);
      }
      await waitForTask(result.data?.task_id, "xoa phong");
    }
  }

  console.log(`     xong: ${userIds.length} nguoi dung, ${cids.length} phong.\n`);
}

async function main() {
  let plan;
  try {
    // Thay placeholder ở TẦNG ĐỌC FILE, trước khi parse — giống hệt cách Flyway làm với các file
    // `.sql`. Làm vậy thì không có chỗ nào phía dưới phải nhớ gọi hàm thay thế.
    plan = JSON.parse(readFileSync(PLAN_PATH, "utf8").replaceAll("${minioUrl}", MINIO_URL));
  } catch (error) {
    console.error(
      `DUNG - khong doc duoc ${PLAN_PATH}\n` +
        "File nay do scripts/seed/generate_seed.py sinh ra cung luot voi cac file .sql.\n" +
        "Chay `python scripts/seed/generate_seed.py` truoc.",
    );
    process.exit(1);
  }

  console.log(
    `Ke hoach: ${plan.users.length} nguoi dung, ${plan.dms.length} phong 1-1, ` +
      `${plan.groups.length} nhom, ${plan.blocks.length} quan he chan, ` +
      `${plan.messages.length} tin nhan.`,
  );
  console.log(`Anh lay tu ${MINIO_URL} (dat MINIO_URL de doi).`);
  if (dryRun) {
    console.log("CHAY THU - khong goi Stream, chi kiem ke hoach va dem so luot goi.");
  }
  if (!reset) {
    console.log(
      "KHONG CO --reset - du lieu chat cu tren Stream se o lai, va tin nhan se bi nhan doi\n" +
        "neu script nay da tung chay.",
    );
  }
  console.log("");

  // ── 0. Dọn ─────────────────────────────────────────────────────────────────────────────────
  if (reset) {
    await resetSeedData();
  }

  // ── 1. Upsert người dùng ───────────────────────────────────────────────────────────────────
  console.log("1/4  Dong bo ho so nguoi dung...");
  for (const batch of batched(plan.users, MAX_USERS_PER_UPSERT)) {
    const users = {};
    for (const user of batch) {
      users[user.id] = user.image
        ? { id: user.id, name: user.name, image: user.image }
        : { id: user.id, name: user.name };
    }
    await call("/users", { users });
  }

  // ── 2. Quan hệ chặn, TRƯỚC khi tạo phòng ───────────────────────────────────────────────────
  //
  // Chặn ở sản phẩm này là hai chiều, còn mô hình của Stream thì một chiều — khối chặn thuộc về
  // `user_id`. StreamChatService gửi cả hai chiều, nên ở đây cũng vậy.
  console.log(`2/4  Day ${plan.blocks.length * 2} quan he chan (hai chieu)...`);
  for (const block of plan.blocks) {
    await call("/users/block", {
      user_id: block.blocker,
      blocked_user_id: block.blocked,
    });
    await call("/users/block", {
      user_id: block.blocked,
      blocked_user_id: block.blocker,
    });
  }

  // ── 3. Phòng ───────────────────────────────────────────────────────────────────────────────
  console.log(`3/4  Tao ${plan.dms.length} phong 1-1 va ${plan.groups.length} nhom...`);
  const channelOf = new Map();

  /**
   * PHÒNG 1-1 KHÔNG ĐƯỢC ĐẶT ID, và đây là chỗ script phải khớp với frontend chứ không được tự
   * quyết. `useConversations.startConversation` mở phòng bằng
   * `client.channel('messaging', { members: [a, b] })` — tức hỏi Stream phòng DISTINCT theo thành
   * viên, một phòng `!members-<hash>` do Stream tự đặt tên. Một phòng tạo bằng id tường minh
   * (`seed-dm-9001-9019`, cách làm cũ) là một phòng KHÁC HẲN: nó vẫn hiện ở sidebar vì
   * `queryChannels` lọc theo thành viên, nhưng bấm "Nhắn tin" từ trang cá nhân lại mở ra một
   * phòng rỗng thứ hai bên cạnh nó. Bỏ id đi là cách duy nhất để hai bên gặp nhau.
   *
   * Bỏ luôn đoạn id trên đường dẫn: `POST /channels/{type}/query` không kèm id chính là động từ
   * get-or-create cho phòng distinct.
   */
  for (const dm of plan.dms) {
    const result = await call(`/channels/${CHANNEL_TYPE}/query`, {
      data: {
        created_by_id: dm.members[0],
        members: dm.members.map((user_id) => ({ user_id })),
      },
      state: false,
    });
    // Chạy thử không có phản hồi để đọc id, nên bịa một id để bước 4 vẫn đếm được số lượt gọi.
    const id = dryRun ? `dry-${dm.members.join("-")}` : result.data?.channel?.id;
    if (id) {
      channelOf.set(dm.members.join(","), id);
    }
  }

  // Nhóm thì NGƯỢC LẠI: giữ id tường minh. Nhóm không distinct theo thành viên — hai người có thể
  // muốn hai nhóm khác nhau với cùng một tập người, đúng lý do StreamChatService.createGroupChat
  // sinh `grp-<uuid>` ngẫu nhiên thay vì suy ra từ danh sách thành viên.
  for (const group of plan.groups) {
    await call(`/channels/${CHANNEL_TYPE}/${group.id}/query`, {
      data: {
        created_by_id: group.createdBy,
        name: group.name,
        members: group.members.map((user_id) => ({ user_id })),
      },
      state: false,
    });
  }

  // ── 4. Tin nhắn ────────────────────────────────────────────────────────────────────────────
  if (skipMessages) {
    console.log("4/4  Bo qua tin nhan (--skip-messages).");
  } else {
    console.log(`4/4  Rai ${plan.messages.length} tin nhan...`);
    let sent = 0;
    for (const message of plan.messages) {
      const id =
        message.channelId ?? channelOf.get(message.members.join(","));
      if (!id) {
        continue;
      }
      await call(`/channels/${CHANNEL_TYPE}/${id}/message`, {
        message: {
          text: message.text,
          user_id: message.sender,
          // Stream chỉ nhận created_at cho tin nhắn khi gọi bằng token máy chủ. Không có nó thì
          // mọi tin đều mang dấu thời gian của lúc chạy script, và toàn bộ 18 tháng lịch sử hội
          // thoại dồn vào một phút.
          created_at: isoDaysAgo(message.daysAgo),
          // "attachments" là trường TUỲ CHỌN trên kế hoạch — phần lớn tin nhắn không có. Chuyển
          // thẳng nguyên văn cho Stream: mỗi phần tử theo đúng schema attachment của Stream Chat
          // ({type: "image", image_url: ...} hoặc {type: "file", asset_url: ..., title: ...}).
          // Thêm bởi scripts/seed/extend_chat_plan_v108.py, xem V108__seed_..._extra.sql.
          ...(message.attachments ? { attachments: message.attachments } : {}),
        },
      });
      sent += 1;
      if (sent % 250 === 0) {
        console.log(`     ${sent}/${plan.messages.length}`);
      }
    }
  }

  console.log(
    `\nXong. ${calls} luot goi Stream, ${failures} loi.` +
      (failures
        ? "\nCac loi o tren khong lam dung script: mot phong hong khong dang de mat phan con lai."
        : ""),
  );
  if (failures > 0) {
    process.exitCode = 1;
  }
}

await main();
