"""One-off, hand-run script: adds extra chat rooms/messages for 9001 (duonghaigiang) and 9133
(truongthithao) to scripts/seed/chat-plan.json.

NOT part of generate_seed.py's regular run — chat-plan.json is normally regenerated wholesale by
that script (see its own "Đừng sửa tay V81-V90" warning), which would wipe this addition. Kept as
a script rather than a one-time inline edit so it's obvious how the numbers below were produced,
and rerunnable if chat-plan.json gets regenerated and this needs to be re-applied.

Run once: python scripts/seed/extend_chat_plan_v108.py
"""
import json
from pathlib import Path

PLAN_PATH = Path(__file__).parent / "chat-plan.json"

FRIENDS_9001 = [9005, 9010, 9012, 9023, 9044, 9048, 9065, 9073]
FRIENDS_9133 = [9006, 9013, 9027, 9034, 9061, 9096, 9122, 9155]

OPENERS = [
    "Chào bạn, dạo này dự án thế nào rồi?",
    "Mình vừa đọc bài bạn đăng, hay quá!",
    "Rảnh không, hỏi nhanh cái này chút.",
    "Cuối tuần có buổi offline không nhỉ?",
    "Gửi bạn tài liệu mình hứa hôm trước nè.",
]
REPLIES = [
    "Ổn bạn ơi, đang tới giai đoạn cuối rồi.",
    "Cảm ơn bạn nhé, để mình đọc kỹ lại.",
    "Được chứ, bạn hỏi đi.",
    "Có đó, mình sẽ nhắn địa chỉ sau.",
    "Nhận được rồi, cảm ơn bạn nhiều!",
    "Ừm để mình xem qua rồi phản hồi sau nhé.",
]

IMAGE_ATTACHMENT = {
    "type": "image",
    "image_url": "https://picsum.photos/seed/{seed}/640/480",
}
FILE_ATTACHMENT = {
    "type": "file",
    "asset_url": "https://www.w3.org/WAI/ER/tests/xhtml/testfiles/resources/pdf/dummy.pdf",
    "title": "tai-lieu-du-an.pdf",
}


def build_dm_messages(members, count, seed_prefix, with_attachment):
    messages = []
    for i in range(count):
        sender = members[i % 2]
        text = OPENERS[i % len(OPENERS)] if i == 0 else REPLIES[(i * 3) % len(REPLIES)]
        msg = {
            "members": members,
            "sender": sender,
            "text": text,
            "daysAgo": max(0, 20 - i),
        }
        if with_attachment and i == count - 2:
            msg["attachments"] = [
                {**IMAGE_ATTACHMENT, "image_url": IMAGE_ATTACHMENT["image_url"].format(seed=f"{seed_prefix}{i}")}
            ]
        if with_attachment and i == count - 1:
            msg["attachments"] = [FILE_ATTACHMENT]
        messages.append(msg)
    return messages


def main():
    plan = json.loads(PLAN_PATH.read_text(encoding="utf-8"))

    new_dms = [{"members": ["9001", "9133"]}]
    new_dms += [{"members": ["9001", str(u)]} for u in FRIENDS_9001]
    new_dms += [{"members": ["9133", str(u)]} for u in FRIENDS_9133]

    group_members = ["9001", "9133"] + [str(u) for u in FRIENDS_9001[:4]] + [str(u) for u in FRIENDS_9133[:4]]
    new_group = {
        "id": "seed-extra-v108-giang-thao",
        "name": "Backend x Mobile — Giang & Thao",
        "createdBy": "9001",
        "members": group_members,
    }

    new_messages = []
    # 9001 <-> 9133: cuộc trò chuyện dài nhất, kèm cả ảnh lẫn file đính kèm.
    new_messages += build_dm_messages(["9001", "9133"], 16, "giang-thao", with_attachment=True)
    for u in FRIENDS_9001:
        new_messages += build_dm_messages(["9001", str(u)], 6, f"giang{u}", with_attachment=(u == FRIENDS_9001[0]))
    for u in FRIENDS_9133:
        new_messages += build_dm_messages(["9133", str(u)], 6, f"thao{u}", with_attachment=(u == FRIENDS_9133[0]))

    for i, member in enumerate(group_members):
        text = OPENERS[i % len(OPENERS)] if i == 0 else REPLIES[(i * 2) % len(REPLIES)]
        new_messages.append({
            "channelId": new_group["id"],
            "sender": member,
            "text": text,
            "daysAgo": max(0, 10 - i),
        })

    plan.setdefault("_manual_additions", []).append(
        "V108: +%d DM, +1 group, +%d tin nhắn (kèm ảnh/file) cho 9001/9133 — xem "
        "scripts/seed/extend_chat_plan_v108.py. Bị XOÁ nếu generate_seed.py chạy lại."
        % (len(new_dms), len(new_messages))
    )
    plan["dms"] = plan["dms"] + new_dms
    plan["groups"] = plan["groups"] + [new_group]
    plan["messages"] = plan["messages"] + new_messages

    PLAN_PATH.write_text(json.dumps(plan, ensure_ascii=False, indent=1), encoding="utf-8", newline="\n")
    print(f"+{len(new_dms)} dm, +1 group, +{len(new_messages)} messages")


if __name__ == "__main__":
    main()
