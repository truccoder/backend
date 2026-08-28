#!/usr/bin/env node
/**
 * Nạp dữ liệu chat vào Stream Chat cho bộ seed.
 *
 *     STREAM_API_KEY=... STREAM_API_SECRET=... node scripts/seed/seed-stream-chat.mjs
 *
 * KHÔNG NẰM TRONG `docker compose up`, có chủ đích. Stream Chat là dịch vụ SaaS: mỗi lần chạy tiêu
 * quota thật của một tài khoản thật, và không có bản chạy cục bộ để trỏ vào. Một bước tự động âm
 * thầm đốt quota mỗi lần ai đó gõ `docker compose up` là thứ không nên tồn tại — nên đây là bước
 * bạn chủ động chạy, một lần, khi đã có key.
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
 *   1. Upsert người dùng   — Stream phải biết một người trước khi người đó vào phòng.
 *   2. Đẩy quan hệ chặn    — TRƯỚC khi tạo phòng. StreamChatService.createGroupChat lọc thành
 *                            viên theo quan hệ chặn; làm ngược lại thì bộ lọc không có gì để lọc.
 *   3. Tạo phòng 1-1 và nhóm.
 *   4. Rải tin nhắn.
 *
 * Chạy lại được: upsert và tạo phòng đều là thao tác đặt-lại-trạng-thái. TIN NHẮN THÌ KHÔNG —
 * chạy hai lần là mỗi phòng có hai bản tin nhắn. Dùng `--skip-messages` khi chỉ muốn đồng bộ lại
 * hồ sơ và phòng.
 */

import crypto from "node:crypto";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const HERE = dirname(fileURLToPath(import.meta.url));
const PLAN_PATH = join(HERE, "chat-plan.json");
const BASE_URL = process.env.STREAM_BASE_URL ?? "https://chat.stream-io-api.com";

// Khớp StreamChatClient.GROUP_CHANNEL_TYPE. Stream phân biệt loại kênh, và một loại khác sẽ tạo
// ra những phòng mà frontend không truy vấn tới.
const CHANNEL_TYPE = "messaging";

// Khớp StreamChatClient.MAX_USERS_PER_UPSERT. Stream từ chối lô lớn hơn.
const MAX_USERS_PER_UPSERT = 100;

const apiKey = process.env.STREAM_API_KEY;
const apiSecret = process.env.STREAM_API_SECRET;
const skipMessages = process.argv.includes("--skip-messages");
const dryRun = process.argv.includes("--dry-run");

if (!dryRun && (!apiKey || !apiSecret)) {
  console.error(
    "DUNG - thieu STREAM_API_KEY hoac STREAM_API_SECRET.\n" +
      "Hai bien nay lay tu bang dieu khien Stream, cung hai gia tri ma backend dung o\n" +
      "stream.chat.api-key / stream.chat.api-secret. Chay thu khong can key:\n" +
      "    node scripts/seed/seed-stream-chat.mjs --dry-run",
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
    return { ok: false };
  }
  return { ok: true, data: await response.json().catch(() => ({})) };
}

/** Id phòng 1-1: hai id người dùng nối bằng dấu gạch, sắp xếp để cùng một cặp luôn ra một phòng. */
function dmChannelId(members) {
  return `seed-dm-${[...members].sort((a, b) => Number(a) - Number(b)).join("-")}`;
}

function isoDaysAgo(days) {
  return new Date(Date.now() - days * 86_400_000).toISOString();
}

async function main() {
  let plan;
  try {
    plan = JSON.parse(readFileSync(PLAN_PATH, "utf8"));
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
  if (dryRun) {
    console.log("CHAY THU - khong goi Stream, chi kiem ke hoach va dem so luot goi.\n");
  }

  // ── 1. Upsert người dùng ───────────────────────────────────────────────────────────────────
  console.log("1/4  Dong bo ho so nguoi dung...");
  for (let from = 0; from < plan.users.length; from += MAX_USERS_PER_UPSERT) {
    const batch = plan.users.slice(from, from + MAX_USERS_PER_UPSERT);
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

  for (const dm of plan.dms) {
    const id = dmChannelId(dm.members);
    channelOf.set(dm.members.join(","), id);
    await call(`/channels/${CHANNEL_TYPE}/${id}/query`, {
      data: {
        created_by_id: dm.members[0],
        members: dm.members.map((user_id) => ({ user_id })),
      },
      state: false,
    });
  }

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
