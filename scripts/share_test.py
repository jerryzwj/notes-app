"""分享功能联调脚本：登录 -> 建笔记 -> 建分享 -> 免密读取 -> 取消分享 -> 失效

用法：python scripts/share_test.py [base] [password]
默认 base=http://127.0.0.1:8787，password 取环境变量 NOTES_PASSWORD 或 .dev.vars 里的值。
"""

import json
import os
import sys
import urllib.request
import urllib.error

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8787"
PASSWORD = sys.argv[2] if len(sys.argv) > 2 else os.environ.get("NOTES_PASSWORD", "")

if not PASSWORD:
    devvars = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), ".dev.vars")
    if os.path.exists(devvars):
        for line in open(devvars, encoding="utf-8"):
            if line.strip().startswith("APP_PASSWORD="):
                PASSWORD = line.split("=", 1)[1].strip()
                break

UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126 Safari/537.36"


def req(method, path, body=None, token=None):
    data = None if body is None else json.dumps(body).encode()
    r = urllib.request.Request(BASE + path, data=data, method=method)
    r.add_header("user-agent", UA)
    if data is not None:
        r.add_header("content-type", "application/json")
    if token:
        r.add_header("authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(r, timeout=25) as resp:
            raw = resp.read()
            ctype = resp.headers.get("content-type", "")
            if "json" in ctype:
                return resp.status, json.loads(raw or b"{}")
            return resp.status, raw.decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            return e.code, json.loads(raw or b"{}")
        except Exception:
            return e.code, raw.decode("utf-8", "replace")


results = []


def check(name, cond, extra=""):
    results.append((name, bool(cond), extra))
    print(("PASS " if cond else "FAIL ") + name + ("" if cond else " -> " + str(extra)))


# 1. 登录
status, body = req("POST", "/api/login", {"password": PASSWORD})
check("登录成功", status == 200 and body.get("token"), (status, body))
token = body.get("token")

# 2. 建一条测试笔记
status, body = req(
    "POST",
    "/api/notes",
    {"title": "分享测试", "body": "# 标题\n\n- 第一项\n- 第二项\n\n**加粗** 和 `代码`", "tags": ["测试", "分享"]},
    token,
)
check("新建笔记", status == 201 and body.get("note", {}).get("id"), (status, body))
note_id = body["note"]["id"]

# 3. 生成分享
status, body = req("POST", f"/api/notes/{note_id}/share", token=token)
check("生成分享链接", status == 200 and body.get("slug") and body.get("url"), (status, body))
slug = body.get("slug")

# 4. 匿名读取（不带 token）
status, body = req("GET", f"/api/share/{slug}")
check("免密读取分享", status == 200 and body.get("title") == "分享测试" and "第一项" in body.get("body", ""), (status, body))

# 5. 分享页可访问
status, body = req("GET", f"/s/{slug}")
check("分享页返回 HTML", status == 200 and "share-body" in str(body), (status, str(body)[:60]))

# 6. 错误 slug 404
status, body = req("GET", "/api/share/abcdefgh12345678")
check("伪造 slug 返回 404", status == 404, (status, body))

# 7. 重复生成返回同一个链接
status, body = req("POST", f"/api/notes/{note_id}/share", token=token)
check("重复生成复用同一 slug", body.get("slug") == slug and body.get("existed") is True, (status, body))

# 8. 取消分享
status, body = req("DELETE", f"/api/notes/{note_id}/share", token=token)
check("取消分享", status == 200 and body.get("revoked") is True, (status, body))

# 9. 取消后访问失效
status, body = req("GET", f"/api/share/{slug}")
check("取消后链接失效", status == 404, (status, body))

# 10. 回收站里的笔记不能分享
status, body = req("DELETE", f"/api/notes/{note_id}", token=token)
check("移入回收站", status == 200, (status, body))
status, body = req("POST", f"/api/notes/{note_id}/share", token=token)
check("回收站笔记拒绝分享", status == 400, (status, body))
req("POST", f"/api/notes/{note_id}/restore", token=token)

# 11. 清理
status, body = req("DELETE", f"/api/notes/{note_id}?purge=1", token=token)
check("彻底删除测试笔记", status == 200, (status, body))

failed = [n for n, ok, _ in results if not ok]
print("\n%d passed, %d failed" % (len(results) - len(failed), len(failed)))
sys.exit(1 if failed else 0)
