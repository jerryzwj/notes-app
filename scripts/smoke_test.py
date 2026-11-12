"""
记事本 API 冒烟测试。
用法：
    set NOTES_BASE=https://note.wenjun.eu.org
    set NOTES_PASSWORD=<你的访问密码>
    python scripts/smoke_test.py
"""
import json
import os
import socket
import time
import urllib.error
import urllib.request

# 本机 IPv6 到 Cloudflare 不通，强制走 IPv4，避免每次请求卡满超时
_orig_getaddrinfo = socket.getaddrinfo


def _ipv4_only(host, port, family=0, type=0, proto=0, flags=0):
    return _orig_getaddrinfo(host, port, socket.AF_INET, type, proto, flags)


socket.getaddrinfo = _ipv4_only

BASE = os.environ.get("NOTES_BASE", "https://note.wenjun.eu.org").rstrip("/")
PASSWORD = os.environ.get("NOTES_PASSWORD", "")
if not PASSWORD:
    raise SystemExit("先设置 NOTES_PASSWORD 环境变量再跑")


def call(path, method="GET", body=None, token=None, retries=3):
    url = BASE + path
    data = None
    headers = {
        "user-agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
        "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
    }
    if body is not None:
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        headers["content-type"] = "application/json; charset=utf-8"
    if token:
        headers["authorization"] = "Bearer " + token

    last = None
    for _ in range(retries):
        try:
            req = urllib.request.Request(url, data=data, headers=headers, method=method)
            with urllib.request.urlopen(req, timeout=20) as resp:
                raw = resp.read()
                status = resp.status
            try:
                return status, json.loads(raw.decode("utf-8"))
            except ValueError:
                return status, {"raw_len": len(raw)}
        except urllib.error.HTTPError as e:
            raw = e.read().decode("utf-8", "replace")
            try:
                return e.code, json.loads(raw or "{}")
            except ValueError:
                return e.code, {"raw": raw[:300]}
        except Exception as e:  # 网络抖动重试一次
            last = e
            time.sleep(1.5)
    raise last


def main():
    ok = True

    def step(name, cond, detail=""):
        nonlocal ok
        ok = ok and bool(cond)
        print(("PASS  " if cond else "FAIL  ") + name + ("  " + detail if detail else ""))

    status, body = call("/api/ping")
    step("ping", status == 200 and body.get("ok"), f"http={status}")

    status, body = call("/api/login", "POST", {"password": PASSWORD + "-wrong"})
    step("错误密码被拒绝", status == 401, f"http={status}")

    status, body = call("/api/login", "POST", {"password": PASSWORD})
    token = body.get("token")
    step("登录成功", status == 200 and bool(token))

    status, body = call("/api/notes")
    step("伪造令牌被拒绝", status == 401, f"http={status}")

    payload = {
        "title": "第一条测试笔记",
        "body": "# 标题\n\n你好，这是记事本。\n\n- [ ] 待办一\n- [x] 已完成\n\n```\ncode\n```",
        "tags": ["测试", "生活"],
        "pinned": True,
    }
    status, body = call("/api/notes", "POST", payload, token)
    note_id = body.get("note", {}).get("id")
    step("新建笔记", status == 201 and bool(note_id), f"id={note_id}")
    if not note_id:
        print("\n结果：新建失败，后续用例跳过")
        return 1

    status, body = call("/api/notes", token=token)
    notes = body.get("notes", [])
    first = notes[0] if notes else {}
    step("列表返回", status == 200 and body.get("count") >= 1,
         f"count={body.get('count')} title={first.get('title')} pinned={first.get('pinned')} tags={first.get('tags')}")

    status, body = call("/api/notes/" + note_id, token=token)
    step("单条读取", status == 200 and body.get("note", {}).get("body", "").startswith("# 标题"))

    status, body = call("/api/notes/" + note_id, "PUT",
                        {"title": "改过的标题", "body": "更新后的正文"}, token)
    step("更新笔记", status == 200 and body.get("note", {}).get("title") == "改过的标题")

    status, body = call("/api/notes/" + note_id, "DELETE", token=token)
    status2, body2 = call("/api/notes", token=token)
    deleted = [n for n in body2.get("notes", []) if n.get("id") == note_id]
    step("移入回收站", status == 200 and deleted and deleted[0].get("deleted") is True)

    status, body = call(f"/api/notes/{note_id}/restore", "POST", token=token)
    step("从回收站恢复", status == 200 and body.get("ok"))

    status, body = call("/api/notes/" + note_id + "?purge=1", "DELETE", token=token)
    step("彻底删除", status == 200 and body.get("purged"))

    status, body = call("/api/notes", token=token)
    ids = [n.get("id") for n in body.get("notes", [])]
    step("彻底删除后不再出现", status == 200 and note_id not in ids, f"count={body.get('count')}")

    status, body = call("/api/export", token=token)
    step("导出接口", status == 200 and isinstance(body.get("notes"), list))

    status, body = call("/api/notes/not-a-real-id", token=token)
    step("不存在的笔记返回 404", status == 404, f"http={status}")

    print("\n结果：" + ("全部通过" if ok else "存在失败项"))
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
