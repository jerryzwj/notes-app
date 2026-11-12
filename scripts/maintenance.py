"""
记事本维护脚本。

    python scripts/maintenance.py list            # 列出全部笔记
    python scripts/maintenance.py export out.json # 导出到文件
    python scripts/maintenance.py clear --yes     # 清空全部笔记（危险）

环境变量：NOTES_BASE（默认 https://note.wenjun.eu.org）、NOTES_PASSWORD
"""
import json
import os
import socket
import sys
import time
import urllib.error
import urllib.request

_orig_getaddrinfo = socket.getaddrinfo


def _ipv4_only(host, port, family=0, type=0, proto=0, flags=0):
    return _orig_getaddrinfo(host, port, socket.AF_INET, type, proto, flags)


socket.getaddrinfo = _ipv4_only

BASE = os.environ.get("NOTES_BASE", "https://note.wenjun.eu.org").rstrip("/")
PASSWORD = os.environ.get("NOTES_PASSWORD", "")
UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
)


def call(path, method="GET", body=None, token=None, retries=3):
    headers = {"user-agent": UA}
    data = None
    if body is not None:
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        headers["content-type"] = "application/json; charset=utf-8"
    if token:
        headers["authorization"] = "Bearer " + token

    last = None
    for _ in range(retries):
        try:
            req = urllib.request.Request(BASE + path, data=data, headers=headers, method=method)
            with urllib.request.urlopen(req, timeout=25) as resp:
                return resp.status, resp.read()
        except urllib.error.HTTPError as e:
            return e.code, e.read()
        except Exception as e:
            last = e
            time.sleep(2)
    raise last


def login():
    status, raw = call("/api/login", "POST", {"password": PASSWORD})
    if status != 200:
        raise SystemExit(f"登录失败 http={status} {raw[:200]!r}")
    return json.loads(raw)["token"]


def main():
    if len(sys.argv) < 2 or sys.argv[1] not in {"list", "export", "clear"}:
        print(__doc__)
        return 1

    token = login()
    cmd = sys.argv[1]

    if cmd == "list":
        status, raw = call("/api/notes", token=token)
        notes = json.loads(raw)["notes"]
        print(f"共 {len(notes)} 条")
        for n in notes:
            flag = " [回收站]" if n["deleted"] else ""
            print(f"  {n['updatedAt']}  {n['title'] or '(无标题)'}{flag}  #{n['id']}")
        return 0

    if cmd == "export":
        target = sys.argv[2] if len(sys.argv) > 2 else "notes-export.json"
        status, raw = call("/api/export", token=token)
        with open(target, "wb") as f:
            f.write(raw)
        print(f"已导出 {len(raw)} 字节 -> {target}")
        return 0

    if cmd == "clear":
        if "--yes" not in sys.argv:
            print("清空操作需要显式确认：python scripts/maintenance.py clear --yes")
            return 1
        status, raw = call("/api/notes", token=token)
        notes = json.loads(raw)["notes"]
        for n in notes:
            call(f"/api/notes/{n['id']}?purge=1", "DELETE", token=token)
        print(f"已清空 {len(notes)} 条笔记")
        return 0

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
